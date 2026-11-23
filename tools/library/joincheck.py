#!/usr/bin/env python3
"""Checks, sample for sample, how songs played one after another join up.

Give it a recording of the player's output (scripts/emu capture) and the song files that
were played, in order. It decodes each file with ffmpeg, which trims exactly the padding
the file's own header declares, finds that audio in the recording, and says where each song
starts and ends there: so many samples early or late, so many missing from its start or
its end. A player that is gapless puts every song's first sample straight after the last
sample of the one before.

    joincheck.py <recording.wav>[,<recording-2.wav>...] <song file>...
    scripts/server joincheck <recording>[,...] <song file>...      the same, in the rig

A recording in several files (the player starts a new one when the sample rate changes) is
given as a comma-separated list, in order. Unlike sweepcheck it needs the files, and works
for any music; it compares against ffmpeg's decoder, so for formats where decoders differ
in their arithmetic (AAC, Vorbis, Opus) it reports how alike the two are rather than
expecting them identical.
"""
import subprocess
import sys

import numpy as np


def probe_rate(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=sample_rate",
                          "-of", "csv=p=0", path], capture_output=True, text=True).stdout.strip()
    return int(out.split(",")[0])


def load(path, rate=None):
    cmd = ["ffmpeg", "-v", "error", "-i", path, "-ac", "1"]
    if rate:
        cmd += ["-ar", str(rate)]
    raw = subprocess.run(cmd + ["-f", "f32le", "-"], capture_output=True).stdout
    return np.frombuffer(raw, dtype="<f4").astype(np.float64)


def find(rec, piece, around, spread):
    """Where in `rec` this piece lies, near `around`; and how alike they are there."""
    lo = max(0, around - spread)
    hi = min(len(rec) - len(piece), around + spread)
    if hi <= lo:
        return None, 0.0
    p = piece - piece.mean()
    window = rec[lo:hi + len(piece)]
    # Correlation at every offset, in one go.
    corr = np.correlate(window - window.mean(), p, mode="valid")
    norm = np.sqrt(np.convolve((window - window.mean()) ** 2, np.ones(len(piece)), mode="valid")) * np.sqrt((p * p).sum()) + 1e-12
    score = corr / norm
    at = int(np.argmax(score))
    return lo + at, float(score[at])


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    parts = sys.argv[1].split(",")
    recs = [(load(p), probe_rate(p)) for p in parts]
    which, at = 0, 0
    worst = 0
    print("%-34s %-6s %10s %10s %9s %9s  %s" % ("song", "rate", "starts", "ends", "head", "tail", "alike"))
    for path in sys.argv[2:]:
        rate = probe_rate(path)
        # Opus always decodes at 48 kHz, whatever its header says it was made from.
        ref = load(path)
        ref_rate = 48000 if path.endswith(".opus") else rate
        if ref_rate != recs[which][1] or at >= len(recs[which][0]) - 100:
            # The player started a new recording file for a new sample rate.
            if which + 1 < len(recs) and recs[which + 1][1] == ref_rate:
                which, at = which + 1, 0
        rec, rec_rate = recs[which]
        if rec_rate != ref_rate:
            print("%-34s %-6d   the recording is at %d Hz here: not compared" % (path.split("/")[-1][:34], ref_rate, rec_rate))
            continue
        n = len(ref)
        piece = min(4096, n // 4)
        a, ca = find(rec, ref[piece:2 * piece], at + piece, 6000)
        b, cb = find(rec, ref[n - 2 * piece:n - piece], at + n - 2 * piece, 6000)
        if a is None or b is None:
            print("%-34s %-6d   not found in the recording" % (path.split("/")[-1][:34], ref_rate))
            continue
        start, end = a - piece, b + 2 * piece
        tol = 0.02 if ca > 0.9999 else 0.12

        def same(i):
            j = start + i
            return 0 <= j < len(rec) and abs(rec[j] - ref[i]) < tol

        head = next((i for i in range(0, min(4000, n - 64)) if all(same(k) for k in range(i, i + 48))), -1)
        tail = next((n - 1 - i for i in range(n - 1, max(n - 4000, 64), -1) if all(same(k) for k in range(i - 48, i))), -1)
        worst = max(worst, abs(start - at), abs(end - (at + n)), max(head, 0), max(tail, 0))
        print("%-34s %-6d %+10d %+10d %9s %9s  %.4f / %.4f" % (
            path.split("/")[-1][:34], ref_rate, start - at, end - (at + n),
            "?" if head < 0 else str(head), "?" if tail < 0 else str(tail), ca, cb))
        at = end
    print()
    print("starts, ends: samples away from where a gapless join puts them (+ late, - early)")
    print("head, tail:   samples of the song's own start and end that are not in the recording")
    sys.exit(0 if worst == 0 else 1)


if __name__ == "__main__":
    main()
