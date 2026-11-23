#!/usr/bin/env python3
"""Sets up the test server through its API once the library is in place.

Creates the test account (and a second one), waits for the server to read the library,
then adds liked songs, ratings, playlists and a little listening history, so every page
of the app has something to show. Safe to run again: it starts from a wiped server
(scripts/server reset) or leaves an already set-up one alone.

    setup.py <server address> <library folder>            set up
    setup.py <server address> <library folder> --status   only say what is there

Runs inside the rig container (scripts/server start). Standard library only.
"""
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

# The test account. Made up, and only ever valid on this throwaway server.
USER, PASSWORD = "tester", "tester-pass-1"
SECOND_USER, SECOND_PASSWORD = "guest", "guest-pass-1"


def http(method, url, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=dict(headers or {}))
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw[:1] in (b"{", b"[") else raw)
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, (json.loads(raw) if raw[:1] in (b"{", b"[") else raw)


def form(url, encoded):
    req = urllib.request.Request(url, data=encoded.encode(), method="POST",
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw[:1] in (b"{", b"[") else raw)
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, (json.loads(raw) if raw[:1] in (b"{", b"[") else raw)


LONG_PREFIX = "Long list ("
LONG_BATCH = 2000


def long_list(api, count):
    """A playlist of `count` songs, the library's 100 over and over, for measuring a long
    list: opening, scrolling, starting it. Replaces one made before. Each time round starts
    one song later than the last, so that the song 100 places on is never the same song: a
    queue that is a hundred places out (the player's window moves by that much) shows."""
    remove_long(api)
    found = api.call("search3", query="", songCount=1000, albumCount=0, artistCount=0)["searchResult3"]["song"]
    ordered = sorted(found, key=lambda s: (s["album"], s.get("discNumber", 1), s.get("track", 0)))
    ids = [ordered[(n + n // len(ordered)) % len(ordered)]["id"] for n in range(count)]
    name = "%s%s songs)" % (LONG_PREFIX, format(count, ","))
    # A few thousand at a time: the server refuses a request with too many parameters.
    made = api.call("createPlaylist", name=name, songId=ids[:LONG_BATCH])["playlist"]
    for start in range(LONG_BATCH, count, LONG_BATCH):
        api.call("updatePlaylist", playlistId=made["id"], songIdToAdd=ids[start:start + LONG_BATCH])
    total = api.call("getPlaylist", id=made["id"])["playlist"].get("songCount", 0) if count > LONG_BATCH else made.get("songCount", 0)
    print("made \"%s\": %d songs (id %s)" % (name, total, made["id"]))


def remove_long(api):
    for playlist in api.call("getPlaylists")["playlists"].get("playlist", []):
        if playlist["name"].startswith(LONG_PREFIX):
            api.call("deletePlaylist", id=playlist["id"])
            print("removed \"%s\"" % playlist["name"])


class Subsonic:
    def __init__(self, base, user, password):
        self.base, self.user, self.password = base, user, password

    def call(self, method, /, **params):
        salt = "rigsalt"
        token = hashlib.md5((self.password + salt).encode()).hexdigest()
        query = [("u", self.user), ("t", token), ("s", salt), ("v", "1.16.1"), ("c", "rig"), ("f", "json")]
        for key, value in params.items():
            for item in (value if isinstance(value, (list, tuple)) else [value]):
                query.append((key, str(item)))
        encoded = urllib.parse.urlencode(query)
        if len(encoded) > 1800:
            # A long list of ids goes in the body (the OpenSubsonic "formPost" extension).
            status, body = form("%s/rest/%s" % (self.base, method), encoded)
        else:
            status, body = http("GET", "%s/rest/%s?%s" % (self.base, method, encoded))
        answer = body.get("subsonic-response", {}) if isinstance(body, dict) else {}
        if answer.get("status") != "ok":
            raise SystemExit("server refused %s: %s" % (method, answer.get("error") or body))
        return answer


def main():
    if len(sys.argv) not in (3, 4):
        raise SystemExit(__doc__)
    base, library = sys.argv[1].rstrip("/"), sys.argv[2]
    if sys.argv[3:] == ["--status"]:
        return summary(Subsonic(base, USER, PASSWORD), base)
    if sys.argv[3:4] and sys.argv[3].startswith("--long="):
        value = sys.argv[3].split("=", 1)[1]
        api = Subsonic(base, USER, PASSWORD)
        return remove_long(api) if value == "remove" else long_list(api, int(value))
    manifest = json.load(open(os.path.join(library, "manifest.json"), encoding="utf-8"))
    want = len(manifest["songs"])

    for _ in range(60):
        try:
            if http("GET", base + "/ping")[0] == 200:
                break
        except OSError:
            pass
        time.sleep(1)
    else:
        raise SystemExit("the server did not answer at %s" % base)

    status, body = http("POST", base + "/auth/createAdmin", dict(username=USER, password=PASSWORD))
    fresh = status == 200
    if not fresh:
        status, body = http("POST", base + "/auth/login", dict(username=USER, password=PASSWORD))
        if status != 200:
            raise SystemExit("could not sign in as %s (%s); run scripts/server reset" % (USER, status))
    admin = {"X-ND-Authorization": "Bearer " + body["token"]}
    api = Subsonic(base, USER, PASSWORD)

    # Have the server read the library, and wait until every song is in.
    api.call("startScan", fullScan="true")
    deadline = time.time() + 180
    while True:
        scan = api.call("getScanStatus")["scanStatus"]
        found = api.call("search3", query="", songCount=1000, albumCount=0, artistCount=0)
        songs = found.get("searchResult3", {}).get("song", [])
        if not scan.get("scanning") and len(songs) >= want:
            break
        if time.time() > deadline:
            raise SystemExit("the server found %d of %d songs after three minutes" % (len(songs), want))
        time.sleep(1)

    if not fresh and api.call("getPlaylists")["playlists"].get("playlist"):
        print("server was already set up; left as it is (scripts/server reset starts again)")
        return summary(api, base)

    by_key = {(s["album"], s.get("discNumber", 1), s.get("track", 0)): s for s in songs}

    def song(album, track, disc=1):
        return by_key[(album, disc, track)]["id"]

    albums = {a["name"]: a for a in api.call("getAlbumList2", type="alphabeticalByName", size=500)["albumList2"]["album"]}
    artists = {a["name"]: a for index in api.call("getArtists")["artists"]["index"] for a in index["artist"]}

    # A second, ordinary account, with a public playlist the first account can see but not change.
    status, body = http("POST", base + "/api/user", dict(userName=SECOND_USER, name="Guest", password=SECOND_PASSWORD, isAdmin=False), admin)
    if status != 200:
        raise SystemExit("could not create the second account: %s %s" % (status, body))
    libraries = http("GET", base + "/api/library", None, admin)
    if libraries[0] == 200 and isinstance(libraries[1], list) and libraries[1]:
        # Newer servers give a new account no library until told to.
        http("PUT", "%s/api/user/%s/library" % (base, body["id"]), dict(libraryIds=[lib["id"] for lib in libraries[1]]), admin)
    guest = Subsonic(base, SECOND_USER, SECOND_PASSWORD)
    shared = guest.call("createPlaylist", name="Guest's picks (shared)",
                        songId=[song("Tidewater", n) for n in (1, 3, 5)] + [song("Nordlys", 2)])["playlist"]
    guest.call("updatePlaylist", playlistId=shared["id"], public="true", comment="Owned by the second account")

    # Liked: songs across formats and albums, two albums, two artists.
    liked = [("First Light", 2), ("First Light", 6), ("Second Wind", 5), ("Tidewater", 1),
             ("Neon Arithmetic", 7), ("Slow Rivers", 1), ("Slow Rivers", 5), ("Nordlys", 2),
             ("夜の散歩", 2), ("Harbour Lights: A Compilation", 8), ("Continuous Sweep", 1),
             ("Long Player", 1)]
    api.call("star", id=[song(a, t) for a, t in liked],
             albumId=[albums["Second Wind"]["id"], albums["夜の散歩"]["id"]],
             artistId=[artists["Paper Lanterns"]["id"], artists["Zoë Älvdal"]["id"]])

    # Ratings, one of each number of stars and more.
    for stars, (album, track) in enumerate([("First Light", 1), ("First Light", 2), ("Second Wind", 1),
                                            ("Tidewater", 2), ("Neon Arithmetic", 1)], start=1):
        api.call("setRating", id=song(album, track), rating=stars)
    api.call("setRating", id=song("Slow Rivers", 1), rating=5)
    api.call("setRating", id=albums["First Light"]["id"], rating=4)

    # Playlists: ordinary, long (every song, for scrolling), gapless, odd characters, empty.
    def playlist(name, ids, comment=None, public=False):
        made = api.call("createPlaylist", name=name, songId=ids)["playlist"]
        if comment or public:
            api.call("updatePlaylist", playlistId=made["id"], comment=comment or "", public="true" if public else "false")

    playlist("Morning mix", [song("First Light", 2), song("Slow Rivers", 1), song("Tidewater", 3),
                             song("Neon Arithmetic", 1), song("Nordlys", 1), song("夜の散歩", 1),
                             song("Harbour Lights: A Compilation", 4), song("Second Wind", 2)],
             comment="Eight songs, six formats")
    playlist("Everything (long list)", [s["id"] for s in sorted(songs, key=lambda s: (s["album"], s.get("discNumber", 1), s.get("track", 0)))],
             comment="Every song on the server, for scrolling")
    playlist("Gapless sweep", [song("Continuous Sweep", n) for n in range(1, 9)], comment="Should sound like one rising note")
    playlist("Été & 夜 — odd characters", [song("Nordlys", 2), song("夜の散歩", 1), song("夜の散歩", 3)], public=True)
    playlist("Short songs", [song("First Light", 1), song("Collected Works", 7, 2), song("Long Player", 5)],
             comment="For skipping, and for how plays are counted")
    api.call("createPlaylist", name="Empty playlist")

    # A little history, newest first, so "Recently played" and play counts are not blank.
    now = int(time.time() * 1000)
    history = [("Second Wind", 1), ("Second Wind", 2), ("Slow Rivers", 1), ("First Light", 2),
               ("夜の散歩", 1), ("Neon Arithmetic", 3), ("Tidewater", 1), ("First Light", 2),
               ("Slow Rivers", 1), ("First Light", 2)]
    for n, (album, track) in enumerate(history):
        api.call("scrobble", id=song(album, track), submission="true", time=now - n * 3600_000)

    summary(api, base)


def summary(api, base):
    artists = sum(len(i["artist"]) for i in api.call("getArtists")["artists"]["index"])
    albums = len(api.call("getAlbumList2", type="alphabeticalByName", size=500)["albumList2"]["album"])
    songs = len(api.call("search3", query="", songCount=1000, albumCount=0, artistCount=0)["searchResult3"].get("song", []))
    genres = len(api.call("getGenres")["genres"].get("genre", []))
    playlists = len(api.call("getPlaylists")["playlists"].get("playlist", []))
    starred = api.call("getStarred2")["starred2"]
    print("test server ready at %s" % base)
    print("  account: %s / %s   (second account: %s / %s)" % (USER, PASSWORD, SECOND_USER, SECOND_PASSWORD))
    print("  %d artists, %d albums, %d songs, %d genres, %d playlists" % (artists, albums, songs, genres, playlists))
    print("  liked: %d songs, %d albums, %d artists" % (len(starred.get("song", [])), len(starred.get("album", [])), len(starred.get("artist", []))))
    print("  from the emulator the address is http://10.0.2.2:%s" % base.rsplit(":", 1)[-1])


if __name__ == "__main__":
    main()
