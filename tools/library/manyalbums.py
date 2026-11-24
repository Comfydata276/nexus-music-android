"""Adds (or takes away) a genre with more albums than one page of a grid holds, for checking
paging: "Paging test", N albums of one second-long song each, by "Paging Test Artist".

    manyalbums.py <base url> <library folder> <count>     add them (and wait for the scan)
    manyalbums.py <base url> <library folder> remove      take them away again

The files go in their own folder of the library (music/Paging Test), so taking them away is
deleting that folder and scanning again. Nothing else in the library is touched.
"""
import os
import shutil
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from setup import PASSWORD, USER, Subsonic, forget_missing  # noqa: E402

GENRE = "Paging test"
FOLDER = "Paging Test"


def scan(api, until):
    api.call("startScan", fullScan="true")
    deadline = time.time() + 300
    while True:
        busy = api.call("getScanStatus")["scanStatus"].get("scanning")
        if not busy and until():
            return
        if time.time() > deadline:
            raise SystemExit("the scan did not finish in five minutes")
        time.sleep(1)


def albums_of_genre(api):
    found = api.call("getAlbumList2", type="byGenre", genre=GENRE, size=500)
    return len(found.get("albumList2", {}).get("album", []))


def main():
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    base, library, what = sys.argv[1].rstrip("/"), sys.argv[2], sys.argv[3]
    api = Subsonic(base, USER, PASSWORD)
    folder = os.path.join(library, "music", FOLDER)
    if what == "remove":
        shutil.rmtree(folder, ignore_errors=True)
        scan(api, lambda: albums_of_genre(api) == 0)
        # The files are gone, but Navidrome keeps them as missing, and with them the genre:
        # "Browse by genre" would still list "Paging test". Forgotten, then scanned again.
        forget_missing(base)
        scan(api, lambda: not any(g.get("value") == GENRE for g in api.call("getGenres").get("genres", {}).get("genre", [])))
        print("removed the genre %r" % GENRE)
        return
    count = int(what)
    os.makedirs(folder, exist_ok=True)
    for n in range(1, count + 1):
        out = os.path.join(folder, "Paging %03d.mp3" % n)
        if os.path.exists(out):
            continue
        subprocess.run([
            "ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=%d:duration=1" % (220 + n),
            "-c:a", "libmp3lame", "-b:a", "64k",
            "-metadata", "title=Paging song %03d" % n,
            "-metadata", "album=Paging %03d" % n,
            "-metadata", "artist=Paging Test Artist",
            "-metadata", "album_artist=Paging Test Artist",
            "-metadata", "genre=%s" % GENRE,
            "-metadata", "date=2020-01-01",
            "-metadata", "track=1",
            out,
        ], check=True)
    scan(api, lambda: albums_of_genre(api) >= count)
    print("the genre %r has %d albums" % (GENRE, albums_of_genre(api)))


if __name__ == "__main__":
    main()
