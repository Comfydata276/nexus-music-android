"""Measures a recording of the app's player (scripts/emu capture) against what the sound
settings should have done to the songs of sounds.py.

    soundcheck.py eq <recording.wav> <sounds.json> <six gains in dB> [preamp dB]
        The "Band Tones" song played through the equaliser: each tone's level against the
        level it went in at, beside the response designed for those gains (the cookbook's
        biquads, worked out here in double precision, as the app's tests do).

    soundcheck.py level <recording.wav> <sounds.json> <title,title,...> <quiet|normal|loud> <auto|song|album>
        Songs of "Level Tests" played one after another, gapless, levelled: each one's
        integrated loudness (EBU R 128, by ffmpeg), beside what levelling should make of it.

The recording begins wherever the capture was started; each starts at the first sound in
it, which is the first song's first (the songs begin after silence, or with sound at once).
"""
import json
import math
import re
import subprocess
import sys
import tempfile
import wave

import numpy as np

BANDS = [60.0, 150.0, 400.0, 1000.0, 2400.0, 15000.0]
LEVELS = {"quiet": -1.0, "normal": 4.0, "loud": 7.0}


def read(path):
    with wave.open(path, "rb") as w:
        rate, channels, width = w.getframerate(), w.getnchannels(), w.getsampwidth()
        raw = w.readframes(w.getnframes())
    if width == 2:
        data = np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768.0
    elif width == 4:
        data = np.frombuffer(raw, dtype="<f4").astype(np.float64)
    else:
        raise SystemExit("a 16-bit or float recording, please")
    return rate, data.reshape(-1, channels)


def onset(samples, threshold=0.004):
    loud = np.nonzero(np.max(np.abs(samples), axis=1) > threshold)[0]
    if len(loud) == 0:
        raise SystemExit("the recording is silent")
    return int(loud[0])


def designed(gains, preamp, f, rate):
    total = 20 * math.log10(10 ** (min(0.0, max(-12.0, preamp)) / 20))
    w = 2 * math.pi * f / rate
    for band in range(6):
        a = 10 ** (gains[band] / 40)
        w0 = 2 * math.pi * min(BANDS[band], rate * 0.45) / rate
        sn, cs = math.sin(w0), math.cos(w0)
        if band in (0, 5):
            beta = 2 * math.sqrt(a) * (sn / 2 * math.sqrt(2))
            if band == 0:
                c = [a * ((a + 1) - (a - 1) * cs + beta), 2 * a * ((a - 1) - (a + 1) * cs), a * ((a + 1) - (a - 1) * cs - beta),
                     (a + 1) + (a - 1) * cs + beta, -2 * ((a - 1) + (a + 1) * cs), (a + 1) + (a - 1) * cs - beta]
            else:
                c = [a * ((a + 1) + (a - 1) * cs + beta), -2 * a * ((a - 1) + (a + 1) * cs), a * ((a + 1) + (a - 1) * cs - beta),
                     (a + 1) - (a - 1) * cs + beta, 2 * ((a - 1) - (a + 1) * cs), (a + 1) - (a - 1) * cs - beta]
        else:
            alpha = sn / 2
            c = [1 + alpha * a, -2 * cs, 1 - alpha * a, 1 + alpha / a, -2 * cs, 1 - alpha / a]

        def mag(x0, x1, x2):
            re_ = x0 + x1 * math.cos(w) + x2 * math.cos(2 * w)
            im = -x1 * math.sin(w) - x2 * math.sin(2 * w)
            return math.hypot(re_, im)
        total += 20 * math.log10(mag(c[0], c[1], c[2]) / mag(c[3], c[4], c[5]))
    return total


def eq(args):
    recording, manifest = args[0], json.load(open(args[1]))
    gains = [float(x) for x in args[2:8]]
    preamp = float(args[8]) if len(args) > 8 else 0.0
    rate, samples = read(recording)
    start = onset(samples)
    left = samples[:, 0]
    reference = 0.25 / math.sqrt(2)
    worst = 0.0
    print("%8s  %9s  %9s  %6s" % ("Hz", "measured", "designed", "diff"))
    for i, f in enumerate(manifest["tones"]):
        a = start + i * rate + int(0.2 * rate)
        b = a + int(0.6 * rate)
        rms = float(np.sqrt(np.mean(left[a:b] ** 2)))
        measured = 20 * math.log10(max(rms, 1e-9) / reference)
        want = designed(gains, preamp, f, rate)
        worst = max(worst, abs(measured - want))
        print("%8d  %+8.2f   %+8.2f   %+5.2f" % (f, measured, want, measured - want))
    print("largest difference: %.2f dB" % worst)


def lufs(rate, samples):
    with tempfile.NamedTemporaryFile(suffix=".wav") as tmp:
        with wave.open(tmp.name, "wb") as w:
            w.setnchannels(samples.shape[1])
            w.setsampwidth(2)
            w.setframerate(rate)
            w.writeframes(np.clip(np.round(samples * 32767), -32768, 32767).astype("<i2").tobytes())
        out = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", tmp.name, "-af", "ebur128", "-f", "null", "-"],
                             capture_output=True, text=True).stderr
    return float(re.findall(r"I:\s+(-?[\d.]+) LUFS", out)[-1])


def gain_for(song, level, mode, album_run):
    offset = LEVELS[level]
    untagged = min(10 ** ((-6.0 + offset) / 20), 1.0)
    if "track_gain" not in song:
        return untagged
    by_album = {"song": False, "album": True}.get(mode, album_run)
    if by_album and "album_gain" in song:
        gain_db, peak = song["album_gain"], song.get("album_peak", song.get("track_peak"))
    else:
        gain_db, peak = song["track_gain"], song.get("track_peak")
    gain = 10 ** ((gain_db + offset) / 20)
    headroom = 1.41 if level == "loud" else 1.0
    return min(gain, headroom / (peak if peak and peak > 0 else 1.0))


def level(args):
    recording, manifest = args[0], json.load(open(args[1]))
    titles = args[2].split(",")
    target, mode = args[3], args[4]
    songs = [next(s for s in manifest["songs"] if s["title"] == t) for t in titles]
    rate, samples = read(recording)
    at = onset(samples)
    print("%-18s %9s %9s %9s %8s" % ("song", "file", "measured", "expected", "diff"))
    for n, song in enumerate(songs):
        frames = int(round(song["secs"] * rate))
        part = samples[at:at + frames]
        at += frames
        # Auto: an album played in order (the neighbours are the album's next and previous).
        neighbours = [songs[k] for k in (n - 1, n + 1) if 0 <= k < len(songs)]
        album_run = any(o["album"] == song["album"] and abs(o["track"] - song["track"]) == 1 for o in neighbours)
        g = gain_for(song, target, mode, album_run)
        want = song["lufs"] + 20 * math.log10(g)
        got = lufs(rate, part)
        print("%-18s %+8.2f  %+8.2f  %+8.2f  %+6.2f   (gain %+.2f dB%s)" % (song["title"], song["lufs"], got, want, got - want,
              20 * math.log10(g), ", peak %.3f out" % float(np.max(np.abs(part))) if True else ""))


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in ("eq", "level"):
        raise SystemExit(__doc__)
    {"eq": eq, "level": level}[sys.argv[1]](sys.argv[2:])


if __name__ == "__main__":
    main()
