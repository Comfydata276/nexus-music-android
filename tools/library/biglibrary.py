"""Adds (or takes away) a large library: tens of thousands of distinct songs, for measuring
the phone's copy of the library, its search and the Songs page at the size of a real
collection.

    biglibrary.py <base url> <library folder> <count> [--vocabulary]
                                                          add them (and wait for the scan)
    biglibrary.py <base url> <library folder> remove      take them away again
    biglibrary.py <base url> <library folder> churn <n>   while a phone is fetching: add n
                                                          songs and delete n, then start a
                                                          scan (without waiting for it)

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

With --vocabulary the names are those of a real collection rather than of a few word lists:
more than 40,000 distinct words (made-up words, most used once or twice and a few very often,
as a real library's are), in several scripts (Latin with accents, Cyrillic, Greek, Japanese,
Korean, Chinese, Arabic), long titles ("(feat. ...)", "- 2011 Remaster", live and part
numbers), and many songs credited to two or three artists. That is what a keystroke's search
has to get through: each typed word is compared with every distinct word in the library.

The files go in their own folder of the library (music/Big Library), so taking them away is
deleting that folder and scanning again. Nothing else in the library is touched.
"""
import json
import os
import random
import re
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


def txxx_frame(description, text):
    # A user-defined text frame (TXXX), as taggers write ARTISTS: the description, then each
    # value, each ended by a null (several values, one after another).
    values = text if isinstance(text, list) else [text]
    bom = "\ufeff".encode("utf-16-le")
    data = b"\x01" + bom + description.encode("utf-16-le") + b"\x00\x00" + b"\x00\x00".join(bom + v.encode("utf-16-le") for v in values)
    return b"TXXX" + struct.pack(">I", len(data)) + b"\x00\x00" + data


def song_file(path, tags, seconds, artists=None):
    frames = b"".join(id3_frame(name, value) for name, value in tags if value)
    if artists and len(artists) > 1:
        # Each artist credited, one by one, as MusicBrainz Picard writes them.
        frames += txxx_frame("ARTISTS", list(artists))
    tag = b"ID3\x03\x00\x00" + syncsafe(len(frames)) + frames
    with open(path, "wb") as out:
        out.write(tag)
        out.write(audio(seconds))


def safe(name):
    return "".join("_" if c in '/\\:*?"<>|' else c for c in name)


# ---- A real collection's vocabulary (--vocabulary) ------------------------------------------

ONSETS = ["", "b", "br", "c", "ch", "cl", "d", "dr", "f", "fl", "g", "gr", "h", "j", "k", "l", "m", "n",
          "p", "pl", "qu", "r", "s", "sh", "sl", "st", "t", "th", "tr", "v", "w", "y", "z"]
VOWELS = ["a", "e", "i", "o", "u", "ai", "ea", "ee", "ou", "y", "é", "ö", "å", "ü"]
CODAS = ["", "", "", "n", "r", "l", "s", "t", "nd", "rk", "st", "m", "x", "ng"]
CYRILLIC = ["ка", "ро", "ми", "ла", "ве", "ти", "но", "ду", "ше", "зо", "жи", "бе", "ры", "сё", "гу", "ня", "па", "хо"]
GREEK = ["κα", "λο", "μι", "νε", "ρα", "σο", "τη", "φι", "χω", "πε", "δυ", "γα", "ζο", "θε", "ξι", "ψα", "ος", "ας"]
KANA = list("あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわん"
            "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワン")
ARABIC = list("ابتثجحخدذرزسشصضطظعغفقكلمنهوي")
TAILS = ["(feat. %s)", "(%s Remix)", "- %d Remaster", "(Live at the %s)", "Part %s", "(%s Version)",
         "(Acoustic)", "(Radio Edit)", "- Demo", "(Instrumental)", "[Bonus Track]", "(Reprise)"]
ROMAN = ["I", "II", "III", "IV", "V", "VI", "VII"]


class Vocabulary:
    """Made-up words, handed out as a real library uses its words: a new word now and then
    (most of a real library's distinct words appear once or twice), otherwise one already
    used, the commoner the likelier (Zipf). The same every time for the same seed."""

    def __init__(self, rng):
        self.rng = rng
        self.words = []
        self.seen = set()

    def fresh(self):
        rng = self.rng
        while True:
            script = rng.random()
            if script < 0.80:
                word = "".join(rng.choice(ONSETS) + rng.choice(VOWELS) + rng.choice(CODAS) for _ in range(rng.choice([1, 2, 2, 2, 3, 3, 4])))
                if rng.random() < 0.5:
                    word = word.capitalize()
            elif script < 0.86:
                word = "".join(rng.choice(CYRILLIC) for _ in range(rng.choice([2, 3, 4]))).capitalize()
            elif script < 0.90:
                word = "".join(rng.choice(GREEK) for _ in range(rng.choice([2, 3, 4]))).capitalize()
            elif script < 0.94:
                word = "".join(rng.choice(KANA) for _ in range(rng.choice([2, 3, 4, 5])))
            elif script < 0.97:
                # Hangul syllables, composed: a lead, a vowel and sometimes a tail.
                word = "".join(chr(0xAC00 + (rng.randrange(19) * 21 + rng.randrange(21)) * 28 + rng.choice([0, 0, rng.randrange(28)])) for _ in range(rng.choice([2, 3])))
            elif script < 0.985:
                word = "".join(chr(0x4E00 + rng.randrange(0x5000)) for _ in range(rng.choice([2, 3, 4])))
            else:
                word = "".join(rng.choice(ARABIC) for _ in range(rng.choice([3, 4, 5])))
            if len(word) >= 2 and word.lower() not in self.seen:
                self.seen.add(word.lower())
                self.words.append(word)
                return word

    def word(self, new=0.33):
        if not self.words or self.rng.random() < new:
            return self.fresh()
        # Zipf over what has been used so far: rank r with weight 1/r.
        n = len(self.words)
        rank = int(n ** self.rng.random())
        return self.words[min(n - 1, max(0, rank - 1))]

    def phrase(self, few, most, new=0.33):
        return " ".join(self.word(new) for _ in range(self.rng.randint(few, most)))


def credit_line(artists):
    """How a tagger writes several artists in the one artist field: "A feat. B & C"."""
    if len(artists) == 1:
        return artists[0]
    return "%s feat. %s" % (artists[0], " & ".join(artists[1:]))


def realistic_title(vocab, rng, artists):
    title = vocab.phrase(1, 5)
    roll = rng.random()
    if roll < 0.08:
        # A long one: a dozen words or so.
        title = vocab.phrase(8, 14)
    if rng.random() < 0.22:
        tail = rng.choice(TAILS)
        if "%s" in tail:
            filler = rng.choice(artists) if tail.startswith("(feat.") else (rng.choice(ROMAN) if tail.startswith("Part") else vocab.phrase(1, 2, new=0.1))
            tail = tail % filler
        elif "%d" in tail:
            tail = tail % (1990 + rng.randrange(35))
        title += " " + tail
    return title[:1].upper() + title[1:]


def generate_realistic(folder, count):
    rng = random.Random(20261125)
    vocab = Vocabulary(rng)
    used_artists, used_titles = set(), set()
    names = []
    made = 0
    artist_n = 0
    while made < count:
        if artist_n < len(ACCENTED):
            artist = ACCENTED[artist_n]
        else:
            artist = vocab.phrase(1, 3, new=0.5).title()
            if artist in used_artists:
                artist = "%s %d" % (artist, artist_n)
        used_artists.add(artist)
        names.append(artist)
        artist_n += 1
        genre = rng.choice(GENRES)
        for a in range(ALBUMS_PER_ARTIST):
            if made >= count:
                break
            album = vocab.phrase(1, 4)
            album = album[:1].upper() + album[1:]
            year = 1965 + rng.randrange(60)
            album_dir = os.path.join(folder, safe(artist)[:80], "%d - %s" % (year, safe(album)[:80]))
            os.makedirs(album_dir, exist_ok=True)
            for track in range(1, SONGS_PER_ALBUM + 1):
                if made >= count:
                    break
                title = realistic_title(vocab, rng, names)
                if title in used_titles:
                    title = "%s %d" % (title, made)
                used_titles.add(title)
                # Two or three artists on many songs, the album's own first.
                credited = [artist]
                if len(names) > 3 and rng.random() < 0.3:
                    for _ in range(rng.choice([1, 1, 2])):
                        other = rng.choice(names)
                        if other not in credited:
                            credited.append(other)
                path = os.path.join(album_dir, "%02d %s.mp3" % (track, safe(title)[:100]))
                made += 1
                if os.path.exists(path):
                    continue
                tags = [
                    ("TIT2", title), ("TPE1", credit_line(credited)), ("TPE2", artist), ("TALB", album),
                    ("TRCK", "%d/%d" % (track, SONGS_PER_ALBUM)), ("TPOS", "1/1"),
                    ("TYER", str(year)), ("TDRC", str(year)), ("TCON", genre),
                ]
                song_file(path, tags, 120 + rng.randrange(300), artists=credited)
    return made, len(vocab.words)


def distinct_words(folder):
    """The distinct words of every title, album and artist under `folder` (from the file and
    folder names: near enough to the tags, which they are made from), folded roughly as the
    app folds them: lower case, split at anything that isn't a letter or a digit."""
    words = set()
    split = re.compile(r"[^\w]+|_", re.UNICODE)
    for root, dirs, files in os.walk(folder):
        for name in dirs + [f[3:-4] for f in files if f.endswith(".mp3")]:
            for word in split.split(name.lower()):
                if word and not word.isdigit():
                    words.add(word)
    return len(words)


def churn(api, folder, n):
    """Adds n songs (a folder of their own) and deletes n others, then starts a scan and
    returns at once: for changing the library while a phone is fetching its copy."""
    rng = random.Random(time.time())
    stamp = time.strftime("%H%M%S")
    added = os.path.join(folder, "Churn", stamp)
    os.makedirs(added, exist_ok=True)
    for i in range(n):
        title = "Churn %s %03d" % (stamp, i)
        song_file(os.path.join(added, "%03d %s.mp3" % (i, title)), [
            ("TIT2", title), ("TPE1", "Churn Artist"), ("TPE2", "Churn Artist"), ("TALB", "Churn " + stamp),
            ("TRCK", "%d" % (i + 1)), ("TYER", "2026"), ("TDRC", "2026"), ("TCON", "Churn"),
        ], 60)
    files = [os.path.join(root, f) for root, _, names in os.walk(folder) if "Churn" not in root for f in names if f.endswith(".mp3")]
    gone = rng.sample(files, min(n, len(files)))
    for path in gone:
        os.remove(path)
    api.call("startScan", fullScan="false")
    print("added %d songs in %s, deleted %d others; a scan has started" % (n, added, len(gone)))


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
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    base, library, what = sys.argv[1].rstrip("/"), sys.argv[2], sys.argv[3]
    options = sys.argv[4:]
    api = Subsonic(base, USER, PASSWORD)
    folder = os.path.join(library, "music", FOLDER)
    if what == "churn":
        churn(api, folder, int(options[0]) if options else 50)
        return
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
    if "--vocabulary" in options:
        made, coined = generate_realistic(folder, count)
        print("%d songs in %s (%.0f s): %d words coined, about %d distinct words in the names" % (made, folder, time.time() - started, coined, distinct_words(folder)))
    else:
        made = generate(folder, count)
        print("%d songs in %s (%.0f s)" % (made, folder, time.time() - started))
    started = time.time()
    status = scan(api, lambda s: s.get("count", 0) >= made, 3600)
    print("scanned: the server has %s songs (%.0f s)" % (status.get("count"), time.time() - started))


if __name__ == "__main__":
    main()
