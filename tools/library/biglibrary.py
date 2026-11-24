"""Adds (or takes away) a large library: tens of thousands of distinct songs, for measuring
the phone's copy of the library, its search and the Songs page at the size of a real
collection.

    biglibrary.py <base url> <library folder> <count>     add them (and wait for the scan)
    biglibrary.py <base url> <library folder> remove      take them away again

Each song is its own file, but a tiny one: an ID3 tag (title, artist, album, album artist,
track, disc, year, genre) and three MPEG audio frames, the first of which is a Xing header
that says how many frames (and bytes) the song has. Nothing reads further than that to find a song's
length, so the server lists each with a length of its own (two to seven minutes) while the
file is about 600 bytes: 50,000 songs are about 215 MB on disk (each file takes a block of the
filesystem's) rather than several hundred gigabytes. Made in a second or two, read by the
server in under two minutes. They don't
play (there are three frames of silence); they are there to be listed, searched and sorted.

The names are made up from word lists, the same every time (the order of generation is the
seed), and are all different: about twelve songs to an album, six albums to an artist. A few
artists have accented names, so folding is exercised at scale too.

The files go in their own folder of the library (music/Big Library), so taking them away is
deleting that folder and scanning again. Nothing else in the library is touched.
"""
import json
import os
import random
import shutil
import struct
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(__file__))
from setup import PASSWORD, USER, Subsonic  # noqa: E402

FOLDER = "Big Library"
SONGS_PER_ALBUM = 12
ALBUMS_PER_ARTIST = 6

FIRST = """amber ash autumn azure bitter black blue bright broken burning calm cedar cinder
clear cold copper coral crimson crystal dark dawn deep distant dusk dusty early echo electric
empty endless evening faded falling fern fire first fleeting floating forest fragile frozen
gentle ghost glass golden gravel green grey hollow honey hidden holy iron ivory jade last late
lazy lemon light little lonely long lost loud lunar marble midnight misty modern morning
neon new night northern old open pale paper patient perfect pine plain quiet rapid red restless
river rose rusty salt satin scarlet secret second silent silver simple sleeping slow small
soft solar southern spare spring steady still stone strange summer sweet tender thin tidal
velvet violet wandering warm western white wild winter wooden young""".split()

SECOND = """afternoon alley anchor angel arrow avenue balloon bay bell bird bloom boat bone
bridge candle canyon castle cathedral chapel circle city cliff cloud coast compass corner
country crown current dance desert diamond door dream drum engine evening falcon feather
field flame flower fountain garden gate glacier harbour heart highway hill horizon horse
island journey kingdom lake lantern letter lighthouse machine meadow memory mirror moon
mountain needle ocean orchard palace parade path planet radio rain river road room sailor
season shadow shore signal sky smoke song sparrow station storm street sun swan telescope
temple thunder tide tower town train valley village voice wave whisper window wing wolf""".split()

# Most titles are two words; some have a few more.
THIRD = [
    "", "", "", "", "", "again", "alone", "at dawn", "by night", "forever", "in bloom",
    "in the dark", "in the rain", "on fire", "on the run", "out loud", "tonight", "undone",
]

GIVEN = """Ada Alba Ana Arlo Astrid Bea Cai Cleo Dara Eli Emil Ezra Fern Finn Freya Gus Hana
Ida Iris Ivo Jada Jonas Juno Kai Kira Lars Lena Leo Lio Luca Maeve Mara Milo Nia Nico Noor
Olla Otto Pia Remy Rhea Rosa Sami Sol Tara Theo Uma Vera Wren Yara Zane Zora""".split()

FAMILY = """Ashdown Bell Calder Crane Delacroix Ellery Farrow Gale Hart Hollis Ingram Jarvis
Keane Lark Marlow Noble Oakes Pike Quill Reyes Sable Stroud Thorne Vance Wilde Yates Zeller""".split()

BAND_WORDS = ["Velvet", "Quiet", "Electric", "Hollow", "Silver", "Wandering", "Midnight",
              "Northern", "Restless", "Golden", "Broken", "Lunar", "Cedar", "Copper", "Neon"]
BAND_NOUNS = ["Engines", "Lanterns", "Sparrows", "Harbours", "Satellites", "Machines", "Wolves",
              "Rivers", "Signals", "Orchards", "Kingdoms", "Pilots", "Tides", "Choirs", "Ghosts"]
ACCENTED = ["Zoë Brändström", "Søren Ølgaard", "Inés Muñoz", "Chloé Lefèvre", "Mikaël Dufrène",
            "Jürgen Weiß", "Ana Sofía Peña", "Łukasz Żak", "Björk Sæmundsdóttir", "Renée Façade"]
GENRES = ["Rock", "Pop", "Electronic", "Folk", "Jazz", "Ambient", "Indie", "Soul", "Classical",
          "Hip-Hop", "Country", "Metal"]


def artist_name(n, rng, used):
    if n < len(ACCENTED):
        return ACCENTED[n]
    while True:
        if rng.random() < 0.5:
            name = "%s %s" % (rng.choice(GIVEN), rng.choice(FAMILY))
        else:
            name = "The %s %s" % (rng.choice(BAND_WORDS), rng.choice(BAND_NOUNS))
        if name not in used:
            used.add(name)
            return name
        # Two artists of the same name are two names here: a number tells them apart.
        name = "%s %d" % (name, n)
        if name not in used:
            used.add(name)
            return name


def phrase(rng, extra=True):
    words = "%s %s" % (rng.choice(FIRST), rng.choice(SECOND))
    tail = rng.choice(THIRD) if extra else ""
    text = (words + (" " + tail if tail else "")).strip()
    return text[:1].upper() + text[1:]


def id3_frame(name, text):
    # ID3v2.3: text in UTF-16 with a byte-order mark, so any name survives.
    data = b"\x01" + "﻿".encode("utf-16-le") + text.encode("utf-16-le")
    return name.encode("ascii") + struct.pack(">I", len(data)) + b"\x00\x00" + data


def syncsafe(n):
    return bytes([(n >> 21) & 0x7F, (n >> 14) & 0x7F, (n >> 7) & 0x7F, n & 0x7F])


# MPEG-1 Layer III, 32 kbps, 44.1 kHz, mono, no padding: 104 bytes a frame, 1,152 samples.
HEADER = b"\xff\xfb\x10\xc0"
FRAME = 104
SIDE_INFO = 17


def audio(seconds):
    frames = max(1, round(seconds * 44100 / 1152))
    # The first frame carries the Xing header (after the side information): the frame count
    # and the bytes they would come to (TagLib believes a Xing header only with both).
    xing = b"Xing" + struct.pack(">I", 0x3) + struct.pack(">I", frames) + struct.pack(">I", frames * FRAME)
    first = HEADER + b"\x00" * SIDE_INFO + xing
    first += b"\x00" * (FRAME - len(first))
    silent = HEADER + b"\x00" * (FRAME - len(HEADER))
    return first + silent * 2


def song_file(path, tags, seconds):
    frames = b"".join(id3_frame(name, value) for name, value in tags if value)
    tag = b"ID3\x03\x00\x00" + syncsafe(len(frames)) + frames
    with open(path, "wb") as out:
        out.write(tag)
        out.write(audio(seconds))


def safe(name):
    return "".join("_" if c in '/\\:*?"<>|' else c for c in name)


def generate(folder, count):
    rng = random.Random(20261124)
    used_artists, used_titles = set(), set()
    made = 0
    artist_n = 0
    while made < count:
        artist = artist_name(artist_n, rng, used_artists)
        artist_n += 1
        genre = rng.choice(GENRES)
        for a in range(ALBUMS_PER_ARTIST):
            if made >= count:
                break
            album = phrase(rng, extra=False)
            year = 1965 + rng.randrange(60)
            album_dir = os.path.join(folder, safe(artist), "%d - %s" % (year, safe(album)))
            os.makedirs(album_dir, exist_ok=True)
            for track in range(1, SONGS_PER_ALBUM + 1):
                if made >= count:
                    break
                # Every title different in the whole library: a number goes on one that isn't.
                title = phrase(rng)
                if title in used_titles:
                    title = "%s %d" % (title, made)
                used_titles.add(title)
                path = os.path.join(album_dir, "%02d %s.mp3" % (track, safe(title)))
                made += 1
                if os.path.exists(path):
                    continue
                song_file(path, [
                    ("TIT2", title), ("TPE1", artist), ("TPE2", artist), ("TALB", album),
                    ("TRCK", "%d/%d" % (track, SONGS_PER_ALBUM)), ("TPOS", "1/1"),
                    ("TYER", str(year)), ("TDRC", str(year)), ("TCON", genre),
                ], 120 + rng.randrange(300))
    return made


def scan(api, until, limit):
    api.call("startScan", fullScan="true")
    deadline = time.time() + limit
    while True:
        status = api.call("getScanStatus")["scanStatus"]
        if not status.get("scanning") and until(status):
            return status
        if time.time() > deadline:
            raise SystemExit("the scan did not finish in %d minutes (it had %s songs)" % (limit // 60, status.get("count")))
        time.sleep(3)


def forget_missing(base):
    """Deletes the files Navidrome lists as missing, through its own interface's API."""
    login = urllib.request.Request(base + "/auth/login", data=json.dumps({"username": USER, "password": PASSWORD}).encode(),
                                   headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(login, timeout=30) as answer:
        token = json.load(answer)["token"]
    gone = urllib.request.Request(base + "/api/missing", method="DELETE", headers={"x-nd-authorization": "Bearer " + token})
    with urllib.request.urlopen(gone, timeout=300):
        pass


def main():
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    base, library, what = sys.argv[1].rstrip("/"), sys.argv[2], sys.argv[3]
    api = Subsonic(base, USER, PASSWORD)
    folder = os.path.join(library, "music", FOLDER)
    before = api.call("getScanStatus")["scanStatus"].get("count", 0)
    if what == "remove":
        had = sum(len(files) for _, _, files in os.walk(folder))
        shutil.rmtree(folder, ignore_errors=True)
        scan(api, lambda s: s.get("count", 0) <= before - had or had == 0, 1800)
        # Navidrome keeps files that have gone as "missing" (in case they come back), with
        # their albums, artists and genre counts: "Browse by genre" would still list the big
        # library's genres with thousands of songs. Removing them is its own Missing files
        # page's job, as an administrator; a scan after that tidies up the rest.
        forget_missing(base)
        status = scan(api, lambda s: True, 600)
        print("removed the big library: the server has %s songs" % status.get("count"))
        return
    count = int(what)
    started = time.time()
    made = generate(folder, count)
    print("%d songs in %s (%.0f s)" % (made, folder, time.time() - started))
    started = time.time()
    status = scan(api, lambda s: s.get("count", 0) >= made, 3600)
    print("scanned: the server has %s songs (%.0f s)" % (status.get("count"), time.time() - started))


if __name__ == "__main__":
    main()
