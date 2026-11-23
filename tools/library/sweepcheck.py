#!/usr/bin/env python3
"""Finds gaps and jumps in a recording of the "Continuous Sweep" album.

That album is one unbroken rising tone cut into songs at exact samples. Played without
gaps its pitch rises smoothly from start to end; a pause between songs shows as a moment
of silence, and lost or repeated sound shows as a sudden step in pitch. This reads a
recording (anything ffmpeg can read: a WAV captured from a phone's output, say) and lists
every such moment.

    sweepcheck.py <recording>            report; exit status 1 if anything was found
    scripts/server sweepcheck <file>     the same, inside the rig's container

It needs no reference file and does not care where in the album the recording starts.
What it tells you is only as good as the recording: one made through a microphone or a
resampling sound card will show the recorder's own glitches too.
"""
import subprocess
import sys

import numpy as np

RATE = 48000


def load(path):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(RATE), "-f", "f32le", "-"],
                         capture_output=True)
    if raw.returncode != 0 or not raw.stdout:
        sys.exit("could not read %s: %s" % (path, raw.stderr.decode()[-300:]))
    return np.frombuffer(raw.stdout, dtype="<f4").astype(np.float64)


def analytic(x):
    """The signal with its quadrature partner (a Hilbert transform), in blocks."""
    out = np.empty(len(x), dtype=np.complex128)
    block, overlap = 1 << 18, 1 << 12
    pos = 0
    while pos < len(x):
        a, b = max(0, pos - overlap), min(len(x), pos + block + overlap)
        spectrum = np.fft.fft(x[a:b])
        n = b - a
        gain = np.zeros(n)
        gain[0] = 1
        gain[1:(n + 1) // 2] = 2
        if n % 2 == 0:
            gain[n // 2] = 1
        piece = np.fft.ifft(spectrum * gain)
        end = min(len(x), pos + block)
        out[pos:end] = piece[pos - a:pos - a + (end - pos)]
        pos = end
    return out


def smooth(v, n):
    kernel = np.ones(n) / n
    return np.convolve(v, kernel, mode="same")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    x = load(sys.argv[1])
    z = analytic(x)
    level = np.abs(z)
    loud = np.median(level[level > 0.01 * level.max()]) if level.max() > 0 else 0
    if loud <= 0:
        sys.exit("the recording is silent")
    # Only the part where the sweep is sounding: from its first loud moment to its last.
    sounding = np.nonzero(smooth(level, 480) > 0.3 * loud)[0]
    first, last = sounding[0] + 2400, sounding[-1] - 2400
    if last - first < RATE:
        sys.exit("less than a second of tone was found")
    found = []

    # Silences: the level falls to under a fifth of normal for more than half a millisecond.
    quiet = smooth(level, 24)[first:last] < 0.2 * loud
    edges = np.flatnonzero(np.diff(np.concatenate([[0], quiet.astype(np.int8), [0]])))
    for start, end in zip(edges[::2], edges[1::2]):
        if end - start >= 24:
            found.append(((first + start) / RATE, "silence for %.1f ms" % ((end - start) * 1000.0 / RATE)))

    # Jumps: the pitch (how fast the phase turns) measured just before and just after each
    # moment, half a millisecond apart. In a smooth sweep they agree to a fraction of a hertz.
    phase = np.unwrap(np.angle(z[first:last]))
    pitch = np.diff(phase) * RATE / (2 * np.pi)
    slow = smooth(pitch, 240)
    step = np.abs(slow[480:] - slow[:-480])
    # Also the phase itself against where a smooth sweep would have put it 10 ms later.
    n = 480
    predicted = phase[:-2 * n] + (phase[n:-n] - phase[:-2 * n]) * 2 + 0  # straight-line guess
    drift = np.abs(phase[2 * n:] - predicted)
    limit_step = max(12.0, 12 * np.median(step))
    limit_drift = max(0.6, 12 * np.median(drift))
    marks = np.zeros(len(phase), dtype=bool)
    marks[np.flatnonzero(step > limit_step) + 240] = True
    marks[np.flatnonzero(drift > limit_drift) + n] = True
    # The very ends are where the measuring itself is unsure (there is nothing before the
    # first sample to compare with), so the first and last fifth of a second are left out.
    marks[:RATE // 5] = False
    marks[-RATE // 5:] = False
    for start, end in runs(marks, join=2400):
        t = (first + (start + end) // 2) / RATE
        if not any(abs(t - when) < 0.06 for when, _ in found):
            found.append((t, "a jump in pitch or phase"))

    found.sort()
    seconds = (last - first) / RATE
    low, high = np.median(pitch[:RATE // 10]), np.median(pitch[-RATE // 10:])
    print("%.1f s of sweep, from about %.0f Hz to about %.0f Hz" % (seconds, low, high))
    if not found:
        print("no gaps and no jumps: the joins between songs cannot be heard or measured")
        return
    print("%d places where the sweep is broken:" % len(found))
    for when, what in found:
        print("  at %7.3f s  %s" % (when, what))
    sys.exit(1)


def runs(mask, join):
    """Start and end of each stretch of True, with stretches closer than `join` merged."""
    idx = np.flatnonzero(mask)
    if len(idx) == 0:
        return []
    out, start, prev = [], idx[0], idx[0]
    for i in idx[1:]:
        if i - prev > join:
            out.append((start, prev))
            start = i
        prev = i
    out.append((start, prev))
    return out


if __name__ == "__main__":
    main()
