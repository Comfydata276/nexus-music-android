#!/usr/bin/env python3
"""Makes the test music library: about a hundred songs of synthesised sound, with tags.

Nothing here is real music. Every artist, album and title is invented, every sound is
computed below from sine waves, and every cover is drawn by this script, so the library
can be made again byte for byte on any machine and shared without a second thought.

    generate.py <output folder>          make the library (skips work already done)
    generate.py <output folder> --force  make it again from nothing

What is in it, and why, is described in docs/TESTING.md. A list of everything made is
written to <output folder>/manifest.json, which tools/library/setup.py and the checks read.
Runs inside the rig container (scripts/server generate); needs ffmpeg, numpy, mutagen, Pillow.
"""
import base64
import colorsys
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import wave

import numpy as np
from mutagen.flac import FLAC, Picture
from mutagen.id3 import (APIC, COMM, ID3, TALB, TCMP, TCON, TDRC, TDRL, TIT2, TPE1, TPE2, TPOS,
                         TRCK, TSO2, TSOP, TSST, TXXX, USLT)
from mutagen.mp4 import MP4, MP4Cover, MP4FreeForm
from mutagen.oggopus import OggOpus
from mutagen.oggvorbis import OggVorbis
from PIL import Image, ImageDraw, ImageFont

RATE = 44100
COMMENT = "Synthetic test audio made by nexus-music-android/tools/library. Not music."

# --- the made-up catalogue ------------------------------------------------------------
# sort: the name libraries file the artist under.  image: whether the artist gets a picture.
ARTISTS = {
    "Paper Lanterns": dict(image=True),
    "The Quiet Harbour": dict(sort="Quiet Harbour, The", image=True),
    "Sable Arcade": dict(image=True),
    "Juniper Vale": dict(image=False),
    "Zoë Älvdal": dict(sort="Älvdal, Zoë", image=True),
    "月光トリオ": dict(sort="Gekko Trio", image=False),
    "Marrow Field": dict(image=False),
    "Test Signals": dict(image=False),
}

# Each album: artist, title, year, genres, format, how loud it is "mastered" (0..1 peak),
# whether it carries ReplayGain tags, whether it has a cover, and its songs. A song is a
# title, or a dict when it needs something special.
#   secs   length in seconds (otherwise 20 to 45, chosen from the title)
#   artists  who performs it, when not just the album's artist (the first is the main one)
#   lrc    write synced lyrics beside it      lyrics  embed plain lyrics in it
#   genres  when they differ from the album's
ALBUMS = [
    dict(artist="Paper Lanterns", title="First Light", year=2019, genres=["Indie"],
         fmt="mp3", level=0.80, gain=True, songs=[
             dict(title="Intro", secs=8),
             dict(title="Lantern Season", lrc=True, secs=40),
             "Borrowed Bicycles", "Glass Orchard",
             dict(title="Don't Wait (Up)", secs=24),
             dict(title="Salt & Static", artists=["Paper Lanterns", "Juniper Vale"]),
             "Northern Avenue", "4 A.M. Ferry", "Paper Weather",
             dict(title="The Long Goodbye to a Town That Never Once Learned How to Say My Name Properly", secs=30),
         ]),
    dict(artist="Paper Lanterns", title="Second Wind", year=2021, genres=["Indie", "Rock"],
         fmt="flac24", level=0.95, gain=True, songs=[
             "Second Wind", "Kindled", "Marble Engine", "Open Signal",
             dict(title="Velvet Thunder", secs=60),
             dict(title="Tidal (feat. Sable Arcade)", artists=["Paper Lanterns", "Sable Arcade"]),
             "Under Satellite", "Rust Compass", "Yellow Morning",
         ]),
    dict(artist="The Quiet Harbour", title="Tidewater", year=2015, genres=["Ambient"],
         fmt="ogg", level=0.25, gain=False, songs=[
             "Low Tide", "Idle Shoreline", "Winter Lighthouse", "Distant Weather",
             dict(title="Hollow Meadow", secs=55), "Early Window", "Silver Rivers", "High Tide",
         ]),
    dict(artist="The Quiet Harbour", title="Collected Works", year=2017, genres=["Ambient", "Electronic"],
         fmt="flac", level=0.35, gain=True,
         discs=["The Early Years", "The Later Years"], songs=[
             # disc 1
             "Intro", "Folded Harbour", "Amber Ladder", "Cobalt Railway", "Painted Umbrella",
             "Quiet Voltage", "Glass Avenue",
             # disc 2
             "Interlude", "Lantern Arcade", "Marble Morning", "Open Rivers", "Tidal Static",
             "Velvet Compass", dict(title="Outro", secs=12),
         ]),
    dict(artist="Sable Arcade", title="Neon Arithmetic", year=2023, genres=["Electronic"],
         fmt="m4a", level=0.90, gain=True, songs=[
             "Arithmetic", "Cobalt Voltage", "Painted Satellite",
             dict(title="Idle Engine", genres=["Electronic", "Jazz"]),
             "Amber Signal", "Winter Arcade",
             dict(title="Night Shift", artists=["Sable Arcade", "Zoë Älvdal", "Juniper Vale"]),
             "Folded Thunder", "Zero Sum",
         ]),
    dict(artist="Juniper Vale", title="Slow Rivers", year=2018, genres=["Folk"],
         fmt="opus", level=0.50, gain=True, songs=[
             dict(title="Slow Rivers", lrc=True, secs=36), "Early Orchard", "Borrowed Meadow",
             "Yellow Bicycle", dict(title="The Long Way Round", secs=300),
             "Kindled Window", "Hollow Ladder", dict(title="Lullaby", lyrics=True, secs=22),
         ]),
    dict(artist="Zoë Älvdal", title="Nordlys", year=2020, genres=["Jazz"],
         fmt="mp3cbr", level=0.60, gain=False, songs=[
             "Nordlys", "Été à Ålesund", "Blå Timmen", "Señor Öresund", "Café Crème",
             "Fjällräv", "Über den Wolken",
         ]),
    dict(artist="月光トリオ", title="夜の散歩", year=2022, genres=["Jazz"],
         fmt="flac", level=0.55, gain=True, songs=[
             "夜の散歩", "月光", "雨の日", "東京の朝", "風の歌", "おやすみ",
         ]),
    dict(artist="Marrow Field", title="Long Player", year=2012, genres=["Rock"],
         fmt="mp3", level=1.0, gain=False, cover=False, songs=[
             dict(title="Side A", secs=60), dict(title="Side B", secs=60),
             dict(title="Rust Thunder", secs=45), dict(title="Under Engine", secs=50),
             dict(title="Runout Groove", secs=20),
         ]),
    # One unbroken sweep from low to high, cut into songs at exact samples with no fades.
    # Played without gaps it rises smoothly; any gap, overlap or lost sample is a click
    # and a jump in pitch, which tools/library/sweepcheck.py finds in a recording.
    dict(artist="Test Signals", title="Continuous Sweep", year=2026, genres=["Test"],
         fmt="flac", level=0.50, gain=True, sweep=(220.0, 1760.0), songs=[
             dict(title="Sweep %d of 8" % n, secs=20) for n in range(1, 9)
         ]),
    dict(artist="Test Signals", title="Continuous Sweep (MP3)", year=2026, genres=["Test"],
         fmt="mp3", level=0.50, gain=False, sweep=(220.0, 1760.0), songs=[
             dict(title="MP3 Sweep %d of 6" % n, secs=20) for n in range(1, 7)
         ]),
    # A compilation: one album, many artists, a different format on every other song,
    # and loudness tags on only some of them.
    dict(artist="Various Artists", title="Harbour Lights: A Compilation", year=2024,
         genres=["Indie", "Folk", "Electronic", "Jazz", "Ambient"], compilation=True,
         fmt="mixed", level=0.70, gain="some", songs=[
             dict(title="Harbour Lights", artists=["Paper Lanterns"], fmt="mp3", genres=["Indie"]),
             dict(title="Quiet Morning", artists=["The Quiet Harbour"], fmt="flac", genres=["Ambient"]),
             dict(title="Sable Signal", artists=["Sable Arcade"], fmt="m4a", genres=["Electronic"]),
             dict(title="Juniper Avenue", artists=["Juniper Vale"], fmt="opus", genres=["Folk"]),
             dict(title="Älvdal Weather", artists=["Zoë Älvdal"], fmt="ogg", genres=["Jazz"]),
             dict(title="月の港", artists=["月光トリオ"], fmt="flac", genres=["Jazz"]),
             dict(title="Marrow Railway", artists=["Marrow Field"], fmt="mp3", genres=["Rock"]),
             dict(title="Two Harbours", artists=["The Quiet Harbour", "Paper Lanterns"], fmt="ogg", genres=["Ambient", "Indie"]),
             dict(title="Arcade Lullaby", artists=["Sable Arcade", "Juniper Vale"], fmt="m4a", genres=["Electronic", "Folk"]),
             dict(title="Closing Time", artists=["Juniper Vale", "Zoë Älvdal", "Paper Lanterns"], fmt="opus", genres=["Folk"]),
         ]),
]

EXT = {"mp3": "mp3", "mp3cbr": "mp3", "flac": "flac", "flac24": "flac", "ogg": "ogg",
       "m4a": "m4a", "opus": "opus"}
ENCODE = {
    "mp3": ["-c:a", "libmp3lame", "-q:a", "4"],
    "mp3cbr": ["-c:a", "libmp3lame", "-b:a", "320k"],
    "flac": ["-c:a", "flac", "-compression_level", "8"],
    "flac24": ["-ar", "48000", "-c:a", "flac", "-sample_fmt", "s32", "-bits_per_raw_sample", "24"],
    "ogg": ["-c:a", "libvorbis", "-q:a", "5"],
    "m4a": ["-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart"],
    "opus": ["-c:a", "libopus", "-b:a", "128k"],
}


def seed_of(*parts):
    return int.from_bytes(hashlib.sha256("|".join(map(str, parts)).encode()).digest()[:8], "big")


def safe(name):
    return re.sub(r'[/\\:*?"<>|]', "-", name).strip(". ")


# --- sound ------------------------------------------------------------------------------
PENTATONIC = [0, 2, 4, 7, 9]


def tune(seed, secs, level):
    """A small tune: plucked notes over a held bass note. Different for every seed."""
    rng = np.random.default_rng(seed)
    n = int(secs * RATE)
    t = np.arange(n) / RATE
    root = 110.0 * 2 ** (int(rng.integers(0, 12)) / 12)
    beat = 60.0 / float(rng.integers(72, 132))
    left = np.zeros(n)
    right = np.zeros(n)
    bass = 0.22 * np.sin(2 * np.pi * root / 2 * t) * (0.75 + 0.25 * np.sin(2 * np.pi * t / (beat * 8)))
    left += bass
    right += bass
    start = 0.0
    while start < secs - 0.05:
        step = PENTATONIC[int(rng.integers(0, 5))] + 12 * int(rng.integers(0, 3))
        freq = root * 2 ** (step / 12)
        length = beat * float(rng.choice([0.5, 0.5, 1, 1, 2]))
        i0 = int(start * RATE)
        i1 = min(n, i0 + int(length * 2.5 * RATE))
        tt = np.arange(i1 - i0) / RATE
        note = np.sin(2 * np.pi * freq * tt) + 0.35 * np.sin(4 * np.pi * freq * tt) + 0.12 * np.sin(6 * np.pi * freq * tt)
        note *= np.exp(-tt * 3.2) * np.minimum(1.0, tt / 0.004)
        pan = float(rng.uniform(0.25, 0.75))
        left[i0:i1] += 0.30 * note * (1 - pan)
        right[i0:i1] += 0.30 * note * pan
        start += length
    fade = int(0.03 * RATE)
    ramp = np.linspace(0, 1, fade)
    for ch in (left, right):
        ch[:fade] *= ramp
        ch[-fade:] *= ramp[::-1]
    stereo = np.stack([left, right], axis=1)
    return stereo * (level / np.max(np.abs(stereo)))


def sweep(f0, f1, total_secs, level):
    """One logarithmic sweep from f0 to f1, the same in both ears."""
    n = int(total_secs * RATE)
    t = np.arange(n) / RATE
    k = math.log(f1 / f0)
    phase = 2 * np.pi * f0 * total_secs / k * (np.exp(t / total_secs * k) - 1)
    mono = level * np.sin(phase)
    return np.stack([mono, mono], axis=1)


def write_wav(path, samples):
    pcm = np.clip(np.round(samples * 32767), -32768, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())


def loudness(samples_list, tmp):
    """Integrated loudness in LUFS (EBU R 128), measured by ffmpeg."""
    path = os.path.join(tmp, "measure.wav")
    write_wav(path, np.concatenate(samples_list))
    out = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", path, "-af", "ebur128", "-f", "null", "-"],
                         capture_output=True, text=True).stderr
    found = re.findall(r"I:\s+(-?[\d.]+) LUFS", out)
    if not found:
        raise SystemExit("ffmpeg did not report loudness:\n" + out[-800:])
    return float(found[-1])


# --- pictures ---------------------------------------------------------------------------
def font(size, text=""):
    cjk = any(ord(c) > 0x2E80 for c in text)
    paths = (["/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"] if cjk else []) + \
        ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"]
    for p in paths:
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def picture(title, subtitle, seed, size=800, round_shapes=False):
    """A cover: two colours chosen from the seed, a pattern, and the words."""
    rng = np.random.default_rng(seed)
    hue = float(rng.uniform(0, 1))
    c1 = tuple(int(255 * v) for v in colorsys.hsv_to_rgb(hue, 0.65, 0.85))
    c2 = tuple(int(255 * v) for v in colorsys.hsv_to_rgb((hue + float(rng.uniform(0.25, 0.5))) % 1, 0.75, 0.35))
    ramp = np.linspace(0, 1, size)[:, None, None]
    grid = (np.array(c1)[None, None, :] * (1 - ramp) + np.array(c2)[None, None, :] * ramp)
    img = Image.fromarray(np.broadcast_to(grid, (size, size, 3)).astype("uint8").copy())
    draw = ImageDraw.Draw(img, "RGBA")
    for _ in range(int(rng.integers(5, 12))):
        x, y = (int(v) for v in rng.integers(0, size, 2))
        r = int(rng.integers(size // 12, size // 3))
        shade = (255, 255, 255, int(rng.integers(18, 60)))
        if round_shapes or rng.random() < 0.5:
            draw.ellipse([x - r, y - r, x + r, y + r], fill=shade)
        else:
            draw.polygon([(x, y - r), (x + r, y + r), (x - r, y + r)], fill=shade)
    big = font(size // 11, title)
    words, lines, line = title.split(" "), [], ""
    for word in words:
        trial = (line + " " + word).strip()
        if draw.textlength(trial, font=big) > size * 0.86 and line:
            lines.append(line)
            line = word
        else:
            line = trial
    lines.append(line)
    y = size * 0.60
    for text in lines[:3]:
        draw.text((size * 0.07, y), text, font=big, fill=(255, 255, 255, 255))
        y += size // 10
    draw.text((size * 0.07, size * 0.90), subtitle, font=font(size // 22, subtitle), fill=(255, 255, 255, 215))
    return img


def jpeg_bytes(img):
    import io
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=84)
    return buf.getvalue()


# --- tags -------------------------------------------------------------------------------
def tag(path, fmt, t, cover):
    """Writes one song's tags the way a careful tagger (Picard, say) would."""
    kind = EXT[fmt]
    display = t["artist_display"]
    gains = t.get("gain")
    if kind == "mp3":
        id3 = ID3()
        id3.add(TIT2(encoding=3, text=t["title"]))
        id3.add(TPE1(encoding=3, text=display))
        id3.add(TXXX(encoding=3, desc="ARTISTS", text=t["artists"]))
        id3.add(TPE2(encoding=3, text=t["album_artist"]))
        id3.add(TALB(encoding=3, text=t["album"]))
        id3.add(TRCK(encoding=3, text="%d/%d" % (t["track"], t["track_total"])))
        id3.add(TPOS(encoding=3, text="%d/%d" % (t["disc"], t["disc_total"])))
        id3.add(TDRC(encoding=3, text=str(t["year"])))
        # A release date as well as a date: the server takes an M4A's one date for both,
        # and files an album's songs apart when only some of them have a release date.
        id3.add(TDRL(encoding=3, text=str(t["year"])))
        id3.add(TCON(encoding=3, text=t["genres"]))
        id3.add(COMM(encoding=3, lang="eng", desc="", text=COMMENT))
        if t.get("artist_sort"):
            id3.add(TSOP(encoding=3, text=t["artist_sort"]))
        if t.get("album_artist_sort"):
            id3.add(TSO2(encoding=3, text=t["album_artist_sort"]))
        if t.get("disc_subtitle"):
            id3.add(TSST(encoding=3, text=t["disc_subtitle"]))
        if t["compilation"]:
            id3.add(TCMP(encoding=3, text="1"))
        if t.get("lyrics_plain"):
            id3.add(USLT(encoding=3, lang="eng", desc="", text=t["lyrics_plain"]))
        if gains:
            for key, value in gains.items():
                id3.add(TXXX(encoding=3, desc=key, text=value))
        if cover:
            id3.add(APIC(encoding=3, mime="image/jpeg", type=3, desc="Cover", data=cover))
        id3.save(path, v2_version=4)
        return
    if kind == "m4a":
        mp4 = MP4(path)
        mp4["\xa9nam"] = t["title"]
        mp4["\xa9ART"] = display
        mp4["aART"] = t["album_artist"]
        mp4["\xa9alb"] = t["album"]
        mp4["trkn"] = [(t["track"], t["track_total"])]
        mp4["disk"] = [(t["disc"], t["disc_total"])]
        mp4["\xa9day"] = str(t["year"])
        mp4["\xa9gen"] = t["genres"]
        mp4["\xa9cmt"] = COMMENT
        mp4["cpil"] = bool(t["compilation"])
        free = "----:com.apple.iTunes:"
        mp4[free + "ARTISTS"] = [MP4FreeForm(a.encode()) for a in t["artists"]]
        if t.get("artist_sort"):
            mp4["soar"] = t["artist_sort"]
        if t.get("album_artist_sort"):
            mp4["soaa"] = t["album_artist_sort"]
        if t.get("lyrics_plain"):
            mp4["\xa9lyr"] = t["lyrics_plain"]
        if gains:
            for key, value in gains.items():
                mp4[free + key] = [MP4FreeForm(value.encode())]
        if cover:
            mp4["covr"] = [MP4Cover(cover, imageformat=MP4Cover.FORMAT_JPEG)]
        mp4.save()
        return
    audio = {"flac": FLAC, "ogg": OggVorbis, "opus": OggOpus}[kind](path)
    audio["TITLE"] = t["title"]
    audio["ARTIST"] = display
    audio["ARTISTS"] = t["artists"]
    audio["ALBUMARTIST"] = t["album_artist"]
    audio["ALBUM"] = t["album"]
    audio["TRACKNUMBER"] = str(t["track"])
    audio["TRACKTOTAL"] = str(t["track_total"])
    audio["DISCNUMBER"] = str(t["disc"])
    audio["DISCTOTAL"] = str(t["disc_total"])
    audio["DATE"] = str(t["year"])
    audio["RELEASEDATE"] = str(t["year"])
    audio["GENRE"] = t["genres"]
    audio["COMMENT"] = COMMENT
    if t.get("artist_sort"):
        audio["ARTISTSORT"] = t["artist_sort"]
    if t.get("album_artist_sort"):
        audio["ALBUMARTISTSORT"] = t["album_artist_sort"]
    if t.get("disc_subtitle"):
        audio["DISCSUBTITLE"] = t["disc_subtitle"]
    if t["compilation"]:
        audio["COMPILATION"] = "1"
    if t.get("lyrics_plain"):
        audio["LYRICS"] = t["lyrics_plain"]
    if gains:
        for key, value in gains.items():
            audio[key] = value
    if cover:
        pic = Picture()
        pic.type, pic.mime, pic.desc, pic.data = 3, "image/jpeg", "Cover", cover
        pic.width = pic.height = 800
        pic.depth = 24
        if kind == "flac":
            audio.add_picture(pic)
        else:
            audio["METADATA_BLOCK_PICTURE"] = [base64.b64encode(pic.write()).decode("ascii")]
    audio.save()


def display_artist(artists):
    if len(artists) == 1:
        return artists[0]
    return artists[0] + " feat. " + " & ".join(artists[1:])


def lrc_for(title, secs):
    """Synced lyrics: a made-up line every four seconds, so timing is easy to judge."""
    lines = ["[ti:%s]" % title, "[re:nexus-music-android test library]"]
    n = 1
    for start in range(2, int(secs) - 2, 4):
        lines.append("[%02d:%02d.00]Line %d of %s, at %d seconds" % (start // 60, start % 60, n, title, start))
        n += 1
    return "\n".join(lines) + "\n"


# --- the work ---------------------------------------------------------------------------
def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    out = os.path.abspath(sys.argv[1])
    force = "--force" in sys.argv[2:]
    music = os.path.join(out, "music")
    manifest_path = os.path.join(out, "manifest.json")
    recipe = hashlib.sha256(open(__file__, "rb").read()).hexdigest()
    if not force and os.path.exists(manifest_path):
        if json.load(open(manifest_path)).get("recipe") == recipe:
            print("library is up to date: %s" % music)
            return
        print("the recipe has changed since this library was made; making it again")
    shutil.rmtree(music, ignore_errors=True)
    os.makedirs(music)
    manifest = dict(recipe=recipe, artists=[], albums=[], songs=[])
    tmp = tempfile.mkdtemp()

    for name, info in ARTISTS.items():
        folder = os.path.join(music, safe(name))
        os.makedirs(folder, exist_ok=True)
        if info.get("image"):
            picture(name, "artist", seed_of("artist", name), size=600, round_shapes=True) \
                .save(os.path.join(folder, "artist.jpg"), "JPEG", quality=84)
        manifest["artists"].append(dict(name=name, sort=info.get("sort"), image=bool(info.get("image"))))

    total = sum(len(a["songs"]) for a in ALBUMS)
    done = 0
    for album in ALBUMS:
        songs = [dict(title=s) if isinstance(s, str) else dict(s) for s in album["songs"]]
        discs = album.get("discs") or [None]
        per_disc = len(songs) // len(discs)
        folder = os.path.join(music, safe(album["artist"]), "%d - %s" % (album["year"], safe(album["title"])))
        os.makedirs(folder, exist_ok=True)
        cover = None
        if album.get("cover", True):
            cover = jpeg_bytes(picture(album["title"], album["artist"], seed_of("cover", album["title"])))

        # the sound of every song first, so album loudness can be measured over all of it
        if album.get("sweep"):
            whole = sweep(album["sweep"][0], album["sweep"][1], sum(s["secs"] for s in songs), album["level"])
            pos = 0
            for s in songs:
                n = int(s["secs"] * RATE)
                s["samples"] = whole[pos:pos + n]
                pos += n
        else:
            for s in songs:
                secs = s.get("secs") or 20 + seed_of("secs", album["title"], s["title"]) % 26
                s["secs"] = secs
                s["samples"] = tune(seed_of("tune", album["title"], s["title"]), secs, album["level"])
        album_gain = album_peak = None
        if album["gain"]:
            album_gain = -18.0 - loudness([s["samples"] for s in songs], tmp)
            album_peak = max(float(np.max(np.abs(s["samples"]))) for s in songs)

        entry = dict(artist=album["artist"], title=album["title"], year=album["year"],
                     genres=album["genres"], compilation=bool(album.get("compilation")),
                     discs=len(discs), cover=cover is not None, gapless=bool(album.get("sweep")),
                     folder=os.path.relpath(folder, music), songs=[])
        for index, s in enumerate(songs):
            disc = index // per_disc + 1 if len(discs) > 1 else 1
            track = index % per_disc + 1 if len(discs) > 1 else index + 1
            fmt = s.get("fmt") or album["fmt"]
            artists = s.get("artists") or [album["artist"]]
            tagged = album["gain"] is True or (album["gain"] == "some" and index % 2 == 0)
            gains = None
            if tagged:
                track_gain = -18.0 - loudness([s["samples"]], tmp)
                gains = {
                    "REPLAYGAIN_TRACK_GAIN": "%.2f dB" % track_gain,
                    "REPLAYGAIN_TRACK_PEAK": "%.6f" % float(np.max(np.abs(s["samples"]))),
                    "REPLAYGAIN_ALBUM_GAIN": "%.2f dB" % album_gain,
                    "REPLAYGAIN_ALBUM_PEAK": "%.6f" % album_peak,
                }
            lyrics_plain = None
            if s.get("lyrics"):
                lyrics_plain = "\n".join("Plain line %d of %s" % (n, s["title"]) for n in range(1, 9))
            name = "%d-%02d - %s.%s" % (disc, track, safe(s["title"])[:80], EXT[fmt])
            path = os.path.join(folder, name)
            wav = os.path.join(tmp, "song.wav")
            write_wav(wav, s["samples"])
            subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", wav,
                            "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact",
                            *ENCODE[fmt], path], check=True)
            main_info = ARTISTS.get(artists[0], {})
            tags = dict(title=s["title"], artists=artists, artist_display=display_artist(artists),
                        album_artist=album["artist"], album=album["title"], track=track,
                        track_total=per_disc if len(discs) > 1 else len(songs), disc=disc,
                        disc_total=len(discs), year=album["year"],
                        genres=s.get("genres") or album["genres"][:1] if album.get("compilation") else s.get("genres") or album["genres"],
                        compilation=bool(album.get("compilation")),
                        artist_sort=main_info.get("sort"),
                        album_artist_sort=ARTISTS.get(album["artist"], {}).get("sort"),
                        disc_subtitle=discs[disc - 1], gain=gains, lyrics_plain=lyrics_plain)
            tag(path, fmt, tags, cover)
            if s.get("lrc"):
                with open(os.path.splitext(path)[0] + ".lrc", "w", encoding="utf-8") as f:
                    f.write(lrc_for(s["title"], s["secs"]))
            song = dict(title=s["title"], artists=artists, artist=tags["artist_display"],
                        album=album["title"], album_artist=album["artist"], disc=disc, track=track,
                        seconds=s["secs"], format=EXT[fmt], genres=tags["genres"],
                        replay_gain=gains, synced_lyrics=bool(s.get("lrc")),
                        plain_lyrics=bool(s.get("lyrics")), file=os.path.relpath(path, music))
            entry["songs"].append(song)
            manifest["songs"].append(song)
            done += 1
            if done % 20 == 0:
                print("%d of %d songs" % (done, total), flush=True)
        manifest["albums"].append(entry)

    shutil.rmtree(tmp, ignore_errors=True)
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    size = sum(os.path.getsize(os.path.join(d, n)) for d, _, names in os.walk(music) for n in names)
    print("made %d artists, %d albums, %d songs, %.0f MB in %s"
          % (len(manifest["artists"]), len(manifest["albums"]), len(manifest["songs"]), size / 1e6, music))


if __name__ == "__main__":
    main()
