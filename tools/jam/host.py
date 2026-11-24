#!/usr/bin/env python3
"""
Plays the Windows app's host's side of a jam against a jam relay, for testing the phone as a
guest (and as one of the person's devices steering a "PC"). What it sends and how it answers
are Windows' src/jam.rs: `host_start`, the host's poll loop, `jam_asked` (add, remove, skip,
previous, pause, play, seek, jump, move, clear, shuffle, repeat, hello, queue, load, volume),
`jam_sync` (the state, with `SHOWN`, `by`/`by_id`, `more`) and the device's room presence.

Nothing here knows an address or a key: the relay's address is the first argument and its
host key is read from the JAM_KEY environment variable. Run it against a relay of your own
(see docs/TESTING.md, "Jams and linked devices").

    JAM_KEY=... tools/jam/host.py http://127.0.0.1:8099 [--songs songs.json] [--name Jo]
        [--no-control] [--room ROOM --word WORD --device-id ID --device-name NAME]
        [--commands FIFO]

It prints the jam's link, then one line per thing that happens. Lines typed on its standard
input (or written to --commands) steer it as its own person would: `play`, `pause`, `skip`,
`add <id>`, `control on|off`, `remove-guest <id>`, `end`, `state` (print it), `sleep <s>`.
"""
import argparse
import json
import os
import random
import sys
import threading
import time
import urllib.error
import urllib.request

SHOWN, FOUND, MOST_ADDED, MOST_HANDED = 100, 40, 50, 3000

DEFAULT_SONGS = [
    {"id": f"made-up-{i}", "title": title, "artist": artist, "album": album, "cover": "", "duration": 180 + i}
    for i, (title, artist, album) in enumerate([
        ("Blue Hour", "Sable Arcade", "Low Tide"), ("Red Kite", "Sable Arcade", "Low Tide"),
        ("Green Room", "Paper Moths", "Attic"), ("Gold Leaf", "Paper Moths", "Attic"),
        ("Silver Line", "North Pier", "Harbour"), ("Copper Sun", "North Pier", "Harbour"),
    ])
]


class Relay:
    def __init__(self, base):
        self.base = base.rstrip("/")

    def call(self, method, path, auth="", body=None, wait=20):
        data = body if isinstance(body, bytes) else None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(f"{self.base}/{path}", data=data, method=method)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if auth:
            request.add_header("Authorization", f"Bearer {auth}")
        try:
            with urllib.request.urlopen(request, timeout=wait) as answer:
                return answer.status, json.loads(answer.read() or b"null")
        except urllib.error.HTTPError as error:
            try:
                return error.code, json.loads(error.read() or b"null")
            except ValueError:
                return error.code, None
        except Exception as error:  # noqa: BLE001 - a test tool says what happened and goes on
            return 0, str(error)


class Host:
    def __init__(self, args):
        self.relay = Relay(args.relay)
        self.key = os.environ.get("JAM_KEY", "")
        self.name = args.name
        self.control = not args.no_control
        self.library = json.load(open(args.songs)) if args.songs else DEFAULT_SONGS
        self.known = {song["id"]: song for song in self.library}
        # The queue in playing order: the current song first. Each entry a song and whether
        # it was queued by hand ("Next in queue").
        self.order = [dict(song, queued=False) for song in self.library[:4]]
        self.cursor = 0
        self.playing = False
        self.position = 0.0
        self.at = time.monotonic()
        self.shuffle, self.repeat, self.volume = False, 0, 1.0
        self.context = "Test list"
        self.added = {}  # song id -> (guest id, name)
        self.lock = threading.Lock()
        self.jam = self.secret = None
        self.guests = []
        self.owners = set()
        self.word = args.word
        self.room, self.device_id, self.device_name = args.room, args.device_id, args.device_name
        self.stop = False

    def say(self, *words):
        print(time.strftime("%H:%M:%S"), *words, flush=True)

    # ---- playback, as Windows' playback.rs moves it ----
    def now_position(self):
        return self.position + (time.monotonic() - self.at if self.playing else 0.0)

    def current(self):
        return self.order[self.cursor] if self.cursor < len(self.order) else None

    def ahead(self):
        return self.order[self.cursor + 1:]

    def queued_ahead(self):
        n = 0
        for song in self.ahead():
            if not song["queued"]:
                break
            n += 1
        return n

    def set_playing(self, on):
        self.position, self.at, self.playing = self.now_position(), time.monotonic(), on

    def go(self, cursor):
        self.cursor, self.position, self.at = cursor, 0.0, time.monotonic()

    def entry(self, at, song_id):
        ahead = self.ahead()
        if isinstance(at, int) and 0 <= at < len(ahead) and ahead[at]["id"] == song_id:
            return at
        places = [i for i, s in enumerate(ahead) if s["id"] == song_id]
        if not places:
            raise ValueError("That song is no longer in the queue.")
        return min(places, key=lambda p: abs(p - (at if isinstance(at, int) else 0)))

    def state(self):
        now = self.current()
        queue = []
        for song in self.ahead()[:SHOWN]:
            entry = {k: song[k] for k in ("id", "title", "artist", "album", "cover", "duration")}
            if song["queued"]:
                entry["queued"] = True
                if song["id"] in self.added:
                    entry["by_id"], entry["by"] = self.added[song["id"]]
            queue.append(entry)
        return {
            "playing": self.playing and now is not None, "position": self.now_position(), "control": self.control,
            "now": None if now is None else {k: now[k] for k in ("id", "title", "artist", "album", "cover", "duration")},
            "queue": queue, "more": max(0, len(self.ahead()) - SHOWN), "context": self.context,
            "shuffle": self.shuffle, "repeat": self.repeat, "volume": self.volume,
        }

    def tell(self):
        with self.lock:
            state = self.state()
        status, _ = self.relay.call("POST", f"api/host/{self.jam}/state", self.secret, state)
        if status != 200:
            self.say("state not taken:", status)

    # ---- the host's side of the relay ----
    def start(self):
        status, body = self.relay.call("POST", "api/host/start", self.key, {"name": self.name})
        if status != 200:
            sys.exit(f"the relay wouldn't start a jam: {status} {body}")
        self.jam, self.secret = body["jam"], body["secret"]
        self.say("jam link:", f"{self.relay.base}/j/{self.jam}")
        self.tell()

    def poll(self):
        after = 0
        while not self.stop:
            status, body = self.relay.call("GET", f"api/host/{self.jam}/poll?after={after}", self.secret, wait=40)
            if status in (401, 404):
                self.say("the relay no longer has the jam:", status)
                return
            if status != 200:
                time.sleep(1)
                continue
            self.guests = body.get("guests", [])
            for event in body.get("events", []):
                after = max(after, event.get("seq", 0))
                kind = event.get("kind")
                if kind == "joined":
                    self.say("joined:", event["guest"]["name"])
                elif kind == "ask":
                    threading.Thread(target=self.answer, args=(event,), daemon=True).start()
                elif kind == "cover":
                    self.relay.call("POST", f"api/host/{self.jam}/cover?cover={event['cover']}&size={event['size']}&missing=1", self.secret, b"")
                elif kind == "guests":
                    self.say("guests now:", [g["name"] for g in self.guests])

    def answer(self, event):
        ask, action, data = event["id"], event["action"], event.get("data") or {}
        guest = (event["guest"]["id"], event["guest"]["name"])
        own = self.word is not None and data.get("owner") == self.word
        if own:
            self.owners.add(guest[0])
        control = own or self.control
        try:
            with self.lock:
                done = self.act(action, data, guest, control, own)
            reply = {"id": ask, "ok": True, "data": done}
        except ValueError as why:
            reply = {"id": ask, "ok": False, "error": str(why)}
        self.say("asked:", guest[1], action, json.dumps(data)[:120], "->", "ok" if reply["ok"] else reply["error"])
        self.relay.call("POST", f"api/host/{self.jam}/reply", self.secret, reply)
        self.tell()

    def act(self, action, data, guest, control, own):
        if action == "search":
            words = str(data.get("q", ""))[:100].lower().split()
            found = [s for s in self.library if all(w in (s["title"] + " " + s["artist"] + " " + s["album"]).lower() for w in words)]
            return {"songs": found[:FOUND]}
        if action == "add":
            ids = [i for i in data.get("ids", []) if isinstance(i, str)][:MOST_ADDED] if "ids" in data else [data.get("id")]
            songs = [self.known[i] for i in ids if i in self.known]
            empty = not self.order
            waiting = {s["id"] for s in ([self.current()] if self.current() else []) + self.ahead()[:self.queued_ahead()]}
            fresh = []
            for s in songs:
                if s["id"] not in waiting:
                    waiting.add(s["id"])
                    fresh.append(s)
            if not fresh:
                raise ValueError("That song isn't in the host's library." if not ids else "That's already in the queue.")
            if not own:
                for s in fresh:
                    self.added[s["id"]] = guest
            place = self.cursor + 1 + (0 if control and data.get("next") else self.queued_ahead())
            for offset, s in enumerate(fresh):
                self.order.insert(place + offset, dict(s, queued=True))
            if empty and not self.playing:
                self.set_playing(True)
            return {"added": len(fresh)}
        if action == "remove":
            place = self.entry(data.get("at"), data.get("id"))
            if not control:
                theirs = self.added.get(data.get("id"), (None,))[0] == guest[0]
                if not (place < self.queued_ahead() and theirs):
                    raise ValueError("You can take out the songs you added.")
            del self.order[self.cursor + 1 + place]
            return {}
        if action in ("hello", "queue", "load", "volume") and not own:
            raise ValueError("That's for your own devices.")
        if action == "hello":
            return {}
        if action == "queue":
            songs = [{"id": s["id"], "queued": s["queued"]} for s in self.order[self.cursor:]][:MOST_HANDED]
            return {"songs": songs, "index": 0, "position": self.now_position(), "playing": self.playing and self.current() is not None, "context": self.context}
        if action == "load":
            start = data.get("index", 0)
            songs, begin = [], 0
            for place, entry in enumerate(data.get("songs", [])[:MOST_HANDED]):
                if place == start:
                    begin = len(songs)
                if entry.get("id") in self.known:
                    songs.append(dict(self.known[entry["id"]], queued=entry.get("queued") is True))
            if not songs:
                raise ValueError("This device's library has none of those songs.")
            self.order, self.cursor = songs, min(begin, len(songs) - 1)
            self.position, self.at = float(data.get("position", 0.0)), time.monotonic()
            self.playing = data.get("playing") is not False
            self.context = str(data.get("context", ""))
            return {}
        if action == "volume":
            self.volume = float(data.get("to", 1.0))
            return {}
        steering = ("skip", "previous", "pause", "play", "seek", "jump", "move", "clear", "shuffle", "repeat")
        if action not in steering:
            raise ValueError("That isn't something a guest can do.")
        if not control:
            raise ValueError("The host is choosing what plays. You can still add songs.")
        if self.current() is None:
            raise ValueError("Nothing is playing.")
        if action == "skip":
            self.go(self.cursor + 1 if self.cursor + 1 < len(self.order) else 0)
        elif action == "previous":
            if self.now_position() > 3 or self.cursor == 0:
                self.go(self.cursor)
            else:
                self.go(self.cursor - 1)
        elif action in ("pause", "play"):
            self.set_playing(action == "play")
        elif action == "seek":
            self.position, self.at = max(0.0, float(data.get("to", 0.0))), time.monotonic()
        elif action == "jump":
            place = self.entry(data.get("at"), data.get("id"))
            self.go(self.cursor + 1 + place)
        elif action == "move":
            place = self.entry(data.get("from"), data.get("id"))
            last = max(0, len(self.order) - (self.cursor + 2))
            to = min(int(data.get("to", 0)), last)
            moved = self.order.pop(self.cursor + 1 + place)
            moved["queued"] = data.get("queued") is True
            self.order.insert(self.cursor + 1 + to, moved)
        elif action == "clear":
            ahead = self.queued_ahead()
            if data.get("queued") is True:
                del self.order[self.cursor + 1:self.cursor + 1 + ahead]
            else:
                del self.order[self.cursor + 1 + ahead:]
        elif action == "shuffle":
            self.shuffle = data.get("on") is True
        elif action == "repeat":
            self.repeat = min(2, int(data.get("mode", 0)))
        return {}

    # ---- the room, as a linked device ----
    def presence(self):
        while not self.stop:
            with self.lock:
                now = self.current()
                status = {"session": self.jam, "title": now and now["title"], "artist": now and now["artist"], "playing": self.playing and now is not None, "steering": None}
            self.relay.call("POST", f"api/room/{self.room}/enter", self.key, {"id": self.device_id, "name": self.device_name, "status": status})
            time.sleep(5)

    there = None

    def reach(self, target, how):
        status, body = self.relay.call("GET", f"api/room/{self.room}/poll?device={self.device_id}&v=0", self.key, wait=10)
        device = next((d for d in (body or {}).get("devices", []) if d["name"] == target or d["id"] == target), None)
        if device is None or not device["status"].get("session"):
            self.say("no such device ready:", target, [d["name"] for d in (body or {}).get("devices", [])])
            return
        session = device["status"]["session"]
        status, joined = self.relay.call("POST", f"api/jam/{session}/join", "", {"name": self.device_name})
        if status != 200:
            self.say("couldn't join:", status, joined)
            return
        self.there = (session, joined["token"])
        self.say("steering", device["name"])
        if how == "send-to":
            with self.lock:
                handed = [{"id": s["id"], "queued": s["queued"]} for s in self.order[self.cursor:]]
                data = {"songs": handed, "index": 0, "position": self.now_position(), "playing": True, "context": self.context}
                self.set_playing(False)
            self.ask_there("load", data)
        else:
            self.ask_there("hello", {})
        threading.Thread(target=self.watch_there, daemon=True).start()

    def ask_there(self, action, data):
        session, token = self.there
        data = dict(data, owner=self.word)
        status, body = self.relay.call("POST", f"api/jam/{session}/ask", token, {"action": action, "data": data})
        self.say("asked the device:", action, "->", status, json.dumps(body)[:200])

    def watch_there(self):
        version = 0
        while self.there and not self.stop:
            session, token = self.there
            status, body = self.relay.call("GET", f"api/jam/{session}/state?v={version}", token, wait=40)
            if status != 200:
                self.say("the device's session:", status, body)
                return
            version = body.get("v", 0)
            state = body.get("state") or {}
            now = state.get("now") or {}
            self.say("the device plays:", now.get("title"), "playing" if state.get("playing") else "paused", f"at {state.get('position', 0):.0f}s", f"{len(state.get('queue', []))} to come", f"volume {state.get('volume')}")

    def commands(self, lines):
        for line in lines:
            words = line.split()
            if not words:
                continue
            with self.lock:
                if words[0] in ("play", "pause"):
                    self.set_playing(words[0] == "play")
                elif words[0] == "skip":
                    self.go(self.cursor + 1 if self.cursor + 1 < len(self.order) else 0)
                elif words[0] == "add" and len(words) > 1 and words[1] in self.known:
                    self.order.insert(self.cursor + 1 + self.queued_ahead(), dict(self.known[words[1]], queued=True))
                elif words[0] == "control":
                    self.control = words[-1] == "on"
                elif words[0] == "remove-guest":
                    self.relay.call("POST", f"api/host/{self.jam}/remove", self.secret, {"guest": words[1]})
                elif words[0] == "state":
                    print(json.dumps(self.state(), indent=1), flush=True)
                elif words[0] == "guests":
                    print(json.dumps(self.guests), flush=True)
                elif words[0] in ("send-to", "steer") and len(words) > 1:
                    # As a PC does with "Connect to a device": joins that device's session with
                    # the word that proves it is the person's own, and hands it this queue
                    # (send-to) or only says hello, then asks what is given after (steer).
                    threading.Thread(target=self.reach, args=(" ".join(words[1:]) if words[0] == "steer" else " ".join(words[1:]), words[0]), daemon=True).start()
                elif words[0] == "ask" and len(words) > 1 and self.there:
                    threading.Thread(target=self.ask_there, args=(words[1], json.loads(" ".join(words[2:]) or "{}")), daemon=True).start()
                elif words[0] == "end":
                    self.relay.call("POST", f"api/host/{self.jam}/end", self.secret, {})
                    self.stop = True
                    return
            if words[0] == "sleep":
                time.sleep(float(words[1]))
            self.tell()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("relay")
    parser.add_argument("--songs")
    parser.add_argument("--name", default="Jo")
    parser.add_argument("--no-control", action="store_true")
    parser.add_argument("--room")
    parser.add_argument("--word")
    parser.add_argument("--device-id", default="%08x" % random.getrandbits(32))
    parser.add_argument("--device-name", default="Test PC")
    parser.add_argument("--commands")
    args = parser.parse_args()
    host = Host(args)
    host.start()
    threading.Thread(target=host.poll, daemon=True).start()
    if args.room:
        threading.Thread(target=host.presence, daemon=True).start()

    def retell():
        while not host.stop:
            time.sleep(30)
            host.tell()
    threading.Thread(target=retell, daemon=True).start()
    source = open(args.commands) if args.commands else sys.stdin
    host.commands(iter(source.readline, ""))
    while not host.stop:
        time.sleep(1)


if __name__ == "__main__":
    main()
