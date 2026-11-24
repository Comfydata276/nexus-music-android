"""Adds (or takes away) the songs the sound milestone is checked with, in a folder of their
own (music/Sound Tests), for a second server instance: the ordinary server's hundred songs,
and every check that counts them, are left alone.

    sounds.py <base url> <library folder>          add them (and wait for the scan)
    sounds.py <base url> <library folder> remove   take them away again

Two albums by "Test Signals", FLAC, 44.1 kHz:

  "Level Tests": songs whose ReplayGain values are measured (EBU R 128, as generate.py does),
  so that levelled they should come out at the same loudness:
    1 Loud Song           loud, every value; synced lyrics (.lrc)
    2 Quiet Song          quiet, every value; plain lyrics in the file
    3 Peaks At Loud       sharp peaks: Normal is held back by them, Loud pushes them past
                          full scale (the limiter's)
    4 Song Value Only     the song's own value and peak, no album value
    5 Nothing To Go By    no values at all; no lyrics
  "Tone Tests":
    1 Band Tones          a second of each of fourteen steady tones, 30 Hz to 15 kHz, at
                          -12 dBFS, after half a second of silence: the equaliser measured
    2 Long Lyrics         two and a half minutes with a synced line every second and a half

What was made is written to <library folder>/sounds.json (the values, and what each song
should measure once levelled).
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
import generate as g  # noqa: E402
from manyalbums import scan  # noqa: E402
from setup import PASSWORD, USER, Subsonic  # noqa: E402

FOLDER = "Sound Tests"
ARTIST = "Test Signals"
TONES = [30, 60, 100, 150, 250, 400, 650, 1000, 1600, 2400, 4000, 6500, 10000, 15000]


def tones():
    rate = g.RATE
    parts = [np.zeros((rate // 2, 2))]
    for f in TONES:
        t = np.arange(rate) / rate
        mono = 0.25 * np.sin(2 * np.pi * f * t)
        # 5 ms in and out, so a tone's edges don't splash into its neighbours.
        edge = int(0.005 * rate)
        mono[:edge] *= np.linspace(0, 1, edge)
        mono[-edge:] *= np.linspace(1, 0, edge)
        parts.append(np.stack([mono, mono], axis=1))
    parts.append(np.zeros((rate // 2, 2)))
    return np.concatenate(parts)


def peaky():
    """A song at an ordinary loudness with a few sharp peaks near full scale: levelling at
    Normal is held back by them; Loud pushes them past it, where the limiter rounds them."""
    samples = g.tune(g.seed_of("sound", "peaks"), 20, 0.4)
    rate = g.RATE
    for at in (5, 10, 15):
        i = at * rate
        burst = 0.9 * np.sin(2 * np.pi * 2000 * np.arange(int(0.004 * rate)) / rate)
        samples[i:i + len(burst), 0] = burst
        samples[i:i + len(burst), 1] = burst
    return samples


def lrc(title, secs, every):
    lines = ["[ti:%s]" % title]
    at, n = 1.0, 1
    while at < secs - 1:
        lines.append("[%02d:%05.2f]Line %d of %s" % (int(at // 60), at % 60, n, title))
        at += every
        n += 1
    return "\n".join(lines) + "\n"


def main():
    if len(sys.argv) not in (3, 4):
        raise SystemExit(__doc__)
    base, library = sys.argv[1].rstrip("/"), sys.argv[2]
    api = Subsonic(base, USER, PASSWORD)
    folder = os.path.join(library, "music", FOLDER)

    def count():
        found = api.call("search3", query="", artistCount=0, albumCount=0, songCount=500)
        return sum(1 for s in found.get("searchResult3", {}).get("song", []) if s.get("album") in ("Level Tests", "Tone Tests"))

    if len(sys.argv) == 4:
        if sys.argv[3] != "remove":
            raise SystemExit(__doc__)
        shutil.rmtree(folder, ignore_errors=True)
        scan(api, lambda: count() == 0)
        print("removed %s" % FOLDER)
        return

    os.makedirs(folder, exist_ok=True)
    made = []
    with tempfile.TemporaryDirectory() as tmp:
        level = [
            dict(title="Loud Song", samples=g.tune(g.seed_of("sound", "loud"), 20, 0.98), lrc=True),
            dict(title="Quiet Song", samples=g.tune(g.seed_of("sound", "quiet"), 20, 0.12), plain=True),
            dict(title="Peaks At Loud", samples=peaky()),
            dict(title="Song Value Only", samples=g.tune(g.seed_of("sound", "only"), 20, 0.5), song_only=True),
            dict(title="Nothing To Go By", samples=g.tune(g.seed_of("sound", "none"), 20, 0.6), untagged=True),
        ]
        tagged = [s for s in level if not s.get("untagged")]
        album_lufs = g.loudness([s["samples"] for s in tagged], tmp)
        album_gain = -18.0 - album_lufs
        album_peak = max(float(np.max(np.abs(s["samples"]))) for s in tagged)
        tone = [
            dict(title="Band Tones", samples=tones(), untagged=True),
            dict(title="Long Lyrics", samples=g.tune(g.seed_of("sound", "long"), 150, 0.7), lrc_every=1.5),
        ]
        cover = g.jpeg_bytes(g.picture("Sound Tests", ARTIST, g.seed_of("sound", "cover")))
        for album, songs in (("Level Tests", level), ("Tone Tests", tone)):
            for track, s in enumerate(songs, 1):
                secs = len(s["samples"]) / g.RATE
                lufs = g.loudness([s["samples"]], tmp)
                peak = float(np.max(np.abs(s["samples"])))
                gains = None
                values = {}
                if not s.get("untagged"):
                    values = dict(track_gain=round(-18.0 - lufs, 2), track_peak=round(peak, 6))
                    gains = {"REPLAYGAIN_TRACK_GAIN": "%.2f dB" % values["track_gain"], "REPLAYGAIN_TRACK_PEAK": "%.6f" % peak}
                    if album == "Level Tests" and not s.get("song_only"):
                        values.update(album_gain=round(album_gain, 2), album_peak=round(album_peak, 6))
                        gains.update({"REPLAYGAIN_ALBUM_GAIN": "%.2f dB" % album_gain, "REPLAYGAIN_ALBUM_PEAK": "%.6f" % album_peak})
                name = "%02d %s.flac" % (track, s["title"])
                path = os.path.join(folder, album + " - " + name)
                wav = os.path.join(tmp, "song.wav")
                g.write_wav(wav, s["samples"])
                subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", wav, "-c:a", "flac", path], check=True)
                plain = "\n".join("Plain line %d of %s" % (n, s["title"]) for n in range(1, 9)) if s.get("plain") else None
                g.tag(path, "flac", dict(
                    title=s["title"], artist_display=ARTIST, artists=[ARTIST], album_artist=ARTIST, album=album,
                    track=track, track_total=len(songs), disc=1, disc_total=1, year=2026, genres=["Test"],
                    compilation=False, gain=gains, lyrics_plain=plain,
                ), cover)
                if s.get("lrc") or s.get("lrc_every"):
                    with open(os.path.splitext(path)[0] + ".lrc", "w", encoding="utf-8") as f:
                        f.write(lrc(s["title"], secs, s.get("lrc_every", 4.0)))
                made.append(dict(album=album, track=track, title=s["title"], file=os.path.relpath(path, os.path.join(library, "music")),
                                 secs=secs, lufs=round(lufs, 2), peak=round(peak, 6), **values,
                                 synced_lyrics=bool(s.get("lrc") or s.get("lrc_every")), plain_lyrics=bool(plain)))
    with open(os.path.join(library, "sounds.json"), "w") as f:
        json.dump(dict(album_lufs=round(album_lufs, 2), songs=made, tones=TONES), f, indent=2)
    scan(api, lambda: count() >= len(made))
    print("%d songs in %s" % (count(), FOLDER))
    for s in made:
        print("  %-12s %d %-18s %5.1f LUFS  peak %.3f  %s" % (s["album"], s["track"], s["title"], s["lufs"], s["peak"],
              "track %+.2f dB%s" % (s["track_gain"], ", album %+.2f dB" % s["album_gain"] if "album_gain" in s else "") if "track_gain" in s else "no values"))


if __name__ == "__main__":
    time.sleep(0)
    main()
