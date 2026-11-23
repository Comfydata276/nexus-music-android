# Testing Nexus Music for Android

Nobody working on this app can sit in a car with it, and the machine it is built on has
no screen. This is the rig that stands in for both: a music server with a made-up
library, emulators that run with nobody watching, checks that use the app the way a car
does, and a script that puts numbers on size and speed. It also says, as plainly as it
can, what none of that proves, and what one person with a phone and a car has to do.

Everything runs in Docker. The host needs Docker and `/dev/kvm`; nothing is installed on
it. Large files (emulator images, the music, reports, pictures) live outside the
repository in `~/.cache/nexus-music-android/` (set `NEXUS_ANDROID_CACHE` to move it).

## The short version

```sh
scripts/server start                 # the test music server (tester / tester-pass-1)
scripts/emu start                    # the phone, Android 17, about 20 seconds
scripts/emu install app/build/outputs/apk/release/app-release.apk
scripts/emu launch org.medianexus.music
scripts/emu ui                       # what is on screen, as text
scripts/emu tap "Connect"            # tap by the words on it
scripts/emu shot                     # a screenshot; prints where it went

scripts/carcheck org.medianexus.music --nexus    # the Android Auto check, pass or fail per item
scripts/measure app/build/outputs/apk/release/app-release.apk

scripts/emu stop && scripts/server stop          # when you are done: both hold memory
```

In the app, the server's address is **`http://127.0.0.1:4533`** (see "Two addresses" below).

| Script | What it is for |
|---|---|
| `scripts/server` | The test music server: start, stop, reset, status, call its API |
| `scripts/emu` | The emulators: start, stop, install, drive, look, logs |
| `scripts/carcheck` | The automated Android Auto check |
| `scripts/cartour` | Pictures of the app in a car's own media screens (Android Automotive) |
| `scripts/dhu` | Google's Desktop Head Unit with the real Android Auto: not usable yet, see below |
| `scripts/measure` | Size, start-up time, memory, scrolling |

Each prints its own help when run with no arguments or `--help`.

**Memory.** The machine has 12 GB and is shared. The phone emulator holds about 3 GB, the
car emulator about 3.4 GB, the Android 9 phone about 2.6 GB, the server under 50 MB. Run
one emulator at a time; `scripts/emu start` refuses when too little memory is free. The
rig's own Gradle build (the tools under `tools/`) waits for any other Gradle build to
finish before it starts.

## The test music server

Navidrome in Docker, listening on this machine only (`127.0.0.1:4533`; another port with
`NEXUS_TEST_PORT`). It looks nothing up on the internet and reports nothing to anyone.

```sh
scripts/server start     # makes the library the first time (under a minute), starts, sets up
scripts/server stop
scripts/server reset     # wipe likes, playlists, history and accounts; same library
scripts/server status
scripts/server api search3 query=lantern      # any Subsonic call, as the test account
scripts/server generate --force               # make the library again from nothing
```

**Accounts** (made up; they exist nowhere else): `tester` / `tester-pass-1`, an
administrator, and `guest` / `guest-pass-1`, an ordinary account that owns one shared
playlist.

**Two addresses.** From an emulator the server can be reached two ways:

- `http://127.0.0.1:4533` works for every app. `scripts/emu start` asks adb to pass the
  device's own port 4533 back to this machine.
- `http://10.0.2.2:4533` is the emulator's usual name for this machine, and it is an
  address on the device's own network. **From Android 17 an app that targets it may only
  reach such addresses if it holds the local-network permission**
  (`android.permission.ACCESS_LOCAL_NETWORK`); without it the connection simply times out.
  Use this address to test exactly that: it behaves like a server on the home Wi-Fi
  addressed by its LAN address. At the time of writing the app does not ask for the
  permission, so on the Android 17 emulator it cannot sign in at this address ("Can't
  reach the server. The connection timed out.") and can at the other.

### What is in the library

Nothing in it is real music. `tools/library/generate.py` invents the names, computes the
sound from sine waves and draws the covers, the same way every time, so the library can be
remade anywhere and shared freely. It is 100 songs, 12 albums, 8 artists (9 with "Various
Artists"), 7 genres, 54 minutes, 96 MB.

| Album | Artist | Format | Songs | Loudness tags | What it is there for |
|---|---|---|---|---|---|
| First Light | Paper Lanterns | MP3 (VBR) | 10 | all | An 8-second song, synced lyrics (`.lrc`) on "Lantern Season", a song with two artists, a title with punctuation, a very long title |
| Second Wind | Paper Lanterns | FLAC 24-bit 48 kHz | 9 | all | Loud master (turned down about 6 dB by levelling), two genres, a 60-second song |
| Tidewater | The Quiet Harbour | Ogg Vorbis | 8 | none | Quiet, untagged; the artist files under "Quiet Harbour, The" |
| Collected Works | The Quiet Harbour | FLAC | 14 | all | Two discs, each with a subtitle; "Intro" also exists on another album |
| Neon Arithmetic | Sable Arcade | M4A (AAC) | 9 | all | A song with three artists, a song with two genres |
| Slow Rivers | Juniper Vale | Opus | 8 | all | The format the server transcodes for the Windows app; a five-minute song (for the "four minutes counts as a play" rule); synced lyrics on the title song; plain embedded lyrics on "Lullaby" |
| Nordlys | Zoë Älvdal | MP3 (320 kbps) | 7 | none | Accented letters in artist and titles |
| 夜の散歩 | 月光トリオ | FLAC | 6 | all | Japanese artist, album and titles |
| Long Player | Marrow Field | MP3 | 5 | none | **No cover art at all**; songs of 45 to 60 seconds; mastered at full level |
| Continuous Sweep | Test Signals | FLAC | 8 | all | One unbroken rising tone cut into 20-second songs at exact samples: gapless playback is a smooth rise, anything else clicks |
| Continuous Sweep (MP3) | Test Signals | MP3 | 6 | none | The same in a format where gapless depends on the player honouring the encoder's padding |
| Harbour Lights: A Compilation | Various Artists | all five | 10 | 5 of 10 | A compilation: seven artists, a different format every song or two, loudness tags on every other song |

Every album but one has its own embedded cover; four artists have a picture and four do
not. `scripts/server start` then adds, through the API: 12 liked songs, 2 liked albums and
2 liked artists; ratings from one star to five; ten plays spread over the last ten hours;
and seven playlists, among them "Everything (long list)" with all 100 songs for scrolling,
"Gapless sweep", one with odd characters in its name, an empty one, and one that belongs
to the second account.

The full list, with every song's length, format and loudness values, is written to
`~/.cache/nexus-music-android/library/manifest.json`.

One thing learned making it, in case a real library shows the same: Navidrome files an
album's songs as two albums when only some of them carry a release date, and it reads an
M4A's single date as a release date too. The generator writes a release date to every
file for that reason.

## The emulators

```sh
scripts/emu start                 # the phone: Android 17, 1080 x 2340, 384dp wide
scripts/emu -d phone28 start      # the phone on Android 9, the oldest version supported
scripts/emu -d car start          # a car's own system: Android Automotive OS (Android 15)
scripts/emu stop                  # (-d the same way)
scripts/emu start --wipe          # factory reset
```

The phone is Android 17 because that is what a current flagship runs and what the app
targets. Its screen is 1080 x 2340 at 450 dpi, which is a Galaxy S Ultra at its default
display setting (384dp wide); the panel's full resolution would cost the emulator's
software renderer nearly twice the work and show nothing more about layout. The "Google
APIs" image is used rather than the "Google Play" one: it is the same system, adb can
become root on it (the size measurements need that), and nothing asks for a Google account.

Driving it:

```sh
scripts/emu install <apk>         scripts/emu launch <package>      scripts/emu kill <package>
scripts/emu ui                    # one line per thing on screen: text, description, id, position
scripts/emu tap "Sign in"         # by text or description (prefers what responds to a tap)
scripts/emu tap id:play_pause     # by resource id        scripts/emu tap 540 1200   # by position
scripts/emu text "tester"         scripts/emu key back    scripts/emu scroll down 3
scripts/emu waitfor "Home" 15     # wait until those words are on screen
scripts/emu shot [file.png]       scripts/emu logs [package] [-f]      scripts/emu crashes
scripts/emu look --width 360 --font 1.3     # the design's narrow, large-type case
scripts/emu look --reset
```

Things worth knowing:

- Animations are switched off and the screen never sleeps, so scripts do not race them.
- `scripts/emu ui` cannot read a screen that never stops moving (a seek bar that is
  advancing, for one). Pause playback first.
- The car runs its screens as a second user and has a second display. The scripts take
  care of both; raw `adb shell am ...` commands on the car need `--user 10`.
- The Android 17 image insists on 4 GB of memory whatever it is asked for. The rig holds
  it to 2 GB with a kernel setting, which is why the whole emulator stays near 3 GB. It is
  enough for one app under test; expect the system to be less generous about keeping
  background apps alive than a 12 GB phone.

## Android Auto

To a media app, Android Auto is another app on the phone that finds the app's media
service, asks it for lists, loads artwork, and sends it commands. Google draws the
screen. So there are two questions, and they need different checks:

1. Does the app answer correctly? (`scripts/carcheck`: automatic, exact, repeatable.)
2. What does the driver see? (`scripts/cartour` for a close relative of Android Auto's
   screen; the real Android Auto needs something this rig cannot fetch by itself.)

### The automated check: `scripts/carcheck`

```sh
scripts/carcheck org.medianexus.music --nexus
scripts/carcheck org.medianexus.music --nexus --query "lantern" \
    --voice "artist:Paper Lanterns=>Paper Lanterns;album:Slow Rivers=>Slow Rivers;song:Lantern Season|artist=Paper Lanterns=>Lantern Season;playlist:Morning mix=>Morning mix;any:nordlys=>Nordlys"
scripts/carcheck --standin          # the stand-in fixture, to check the rig itself
scripts/carcheck --selftest         # prove the checks fail when they should
```

It installs a small app, the car probe (`tools/carprobe`), and runs it. The probe uses
the same support library Android Auto is built on (`MediaBrowserCompat`,
`MediaControllerCompat`), sends the root hints Android Auto sends, and runs in its own
process as a different app, so permissions and artwork sharing are tested for real. It
prints one line per item and ends with a count; the exit status is 0 only when nothing
failed. The full report is saved as JSON under `reports/` in the cache.

| Items | What passing proves |
|---|---|
| `discover.*` | Android Auto can find the app: an exported service answering `android.media.browse.MediaBrowserService`, and the manifest entry and `automotive_app_desc` file that declare a media app |
| `connect.cold` | With the app removed from memory, the service starts with no screen and answers (the time is reported) |
| `root.search` | The car is told the app can search, so it shows the search button |
| `browse.tabs`, `tab_icons`, `tab_names` | One to four tabs, all folders, each with an icon; with `--nexus`, exactly Home, Playlists, Library, Downloads |
| `browse.loads`, `titles`, `ids`, `playable`, `empty` | Every folder (four levels, up to 80 folders) opens within ten seconds; every item has a title and its own id; something can be played; empty folders are listed |
| `browse.styles` | The app says what is a grid and what is a list; the report shows the style of each folder |
| `art.*` | Artwork addresses are `content://` or `android.resource://` (a car cannot open web or file addresses), and another app can actually open and decode each one |
| `search.*` | A search returns results that can be shown and chosen; a search with no match returns an empty list, not an error |
| `play.item`, `play.sound`, `play.foreground` | Choosing a song starts playback; the system confirms sound is really being produced; the service is in the foreground |
| `now.*` | What is playing has title, artist, album, length and readable artwork; pause, next, previous, seek, play-an-item and play-from-search are offered; the extra buttons are listed (with `--nexus`: like, shuffle, repeat, each with an icon; `--press-buttons` presses them) |
| `transport.*` | Pause, play, seek, next, previous, shuffle and repeat each do what they say |
| `queue.*` | The queue is offered, marks the current song, a song chosen from it plays, and when a song ends the next starts |
| `voice.N` | "Play ..." requests of each kind (artist, album, song, playlist, genre, or just words), sent with the extras Google Assistant attaches, start playing something that matches |
| `voice.unnamed`, `voice.no_match` | "Play music" with nothing named plays something; a request for something that does not exist either falls back or tells the car why in words |
| `focus.*` | When another app takes the sound for a moment (a navigation prompt, a call) playback gives way, and comes back afterwards |
| `resume.*` | After the app is removed from memory, the car can connect again and "play" with nothing chosen picks up |
| `--mode signed_out` | With nobody signed in, the session carries a standing error in words for the car to show, and nothing plays |

**Trusted and untrusted.** Media3 gives full control only to callers it trusts: the app
itself, the system, and anything allowed to read notifications. Android Auto is one. An
ordinary app gets to look (browse, search, see what is playing) but not to touch. The
probe is given notification access so that it stands where Android Auto stands;
`--untrusted` takes that away, to see what any app on the phone is allowed. With the
stand-in, untrusted, browsing and search pass and every playback item fails, which is
Media3's default and a sensible one.

**Is a green report worth anything?** `scripts/carcheck --selftest` answers that. It runs
the stand-in healthy (every item must pass), then switches on one fault at a time, each
of which a specific item must catch: artwork given as web addresses, artwork other apps
may not read, no search, a folder that takes twelve seconds, five things at the root,
spoken requests refused, nothing to resume; and nobody signed in, where the error message
must be found. It takes two minutes.

**What `carcheck` does not cover.**

- How anything looks. It never sees a pixel of the car's screen.
- Speech. It sends what the Assistant would send after understanding a sentence; whether
  the Assistant understands "play Paper Lanterns on Nexus Music", knows the app by that
  name, or picks the kind of request the check assumed, is Google's side and is untested.
- Android Auto's own rules: lists cut short while the car is moving, how many levels it
  will show, which artwork sizes it asks for, how long it waits before giving up (the
  check allows ten seconds, from Google's quality guidelines), what it does with an
  empty folder or an error.
- Anything the app decides from the caller's name. Media3 can tell Android Auto from
  other callers by its package name, and an app can use that; the probe has a different
  name, so code written only for Android Auto does not run under it.
- The cable, Bluetooth, the car's buttons and its sound system.
- Sound quality. It knows that sound is being produced, not what it sounds like.

### The visual check that works here: `scripts/cartour`

```sh
scripts/emu -d car start
scripts/emu -d car install app/build/outputs/apk/release/app-release.apk
scripts/cartour org.medianexus.music --query lantern
scripts/cartour --standin
```

This opens the app in the media screens of Android Automotive OS, the system built into
cars that run Android themselves, and saves a picture of each: every tab, a folder, the
player, the queue, search results, and the car's home screen. It starts with both the
car's media app and the app under test closed, as when a car is switched on.

Why it is worth looking at: these screens are Google's, they read a media service through
the same car media library Android Auto's are built with, and they honour the same
things: tabs and their icons, grid or list, group headings, artwork through content
addresses, the queue, the extra buttons, search, error messages. If a grid comes out as a
list here, or a cover is missing, or a button has no icon, it will very likely be the
same in Android Auto.

Why it is not Android Auto: the layout, colours and proportions differ; Android Auto's
limits while driving are not applied; there is no Assistant; and here the app is
installed on the car itself rather than projected from a phone, so nothing about the
connection is exercised. The app also has to install and run on Android 15, which this
image is.

`scripts/carcheck` runs on the car emulator too (`NEXUS_DEVICE=car scripts/carcheck ...`).

### The real Android Auto: `scripts/dhu`, and why it stops short

Google's Desktop Head Unit is the program car screens are tested against: it connects to
the real Android Auto app on a phone and draws exactly what a car would. It is
installed, and `scripts/dhu check` shows it running in the rig's container on a virtual
display and reaching for the phone. That is as far as it goes on this machine today:

**the emulator image has only a stub of Android Auto.** The full app comes from the Play
Store, which needs a signed-in Google account; the rig has none and should not borrow a
person's. The well-known mirrors of Google's packages refuse automated downloads, and the
rig does not try to get round that.

Two ways to finish it, both needing a person to decide:

1. **Hand the rig the package.** Download Android Auto in a browser from a mirror such as
   APKMirror (pick an `arm64-v8a` or `x86_64` build for Android 12 or later) and run
   `scripts/dhu setup <file>`. Android itself guarantees the file is genuine: only a
   package signed by Google can replace the stub. Then open Android Auto once on the
   emulator, accept its terms, switch on developer mode and "Unknown sources" (below),
   and run `scripts/dhu start`, `scripts/dhu shot`.
2. **Sign a throwaway Google account in** on a Play Store image
   (`NEXUS_PHONE_IMAGE=system-images/android-37.0/google_apis_playstore/x86_64 scripts/emu create`),
   update Android Auto from the Play Store, then `scripts/dhu start`. On that image adb
   cannot become root, so `scripts/measure` loses its installed-size numbers.

Be clear about the state of this script: `status` and `check` are proved; `setup`,
`start` beyond launching the program, `shot`, `tap` and `console` are written from
Google's documentation and have never met a real Android Auto here. The recent builds on
the mirrors are ARM only; the emulator image can run ARM code, but Android Auto doing so
is untried. Expect to adjust it the first time.

Even with it working, the Desktop Head Unit is not a car: no cable or wireless link, no
steering-wheel buttons, no microphone unless a recording is fed in, and spoken requests
need the Assistant, which needs an account.

A simpler route to the same thing, if a person is willing: the Desktop Head Unit on any
computer with a **real phone** plugged in. That is the real Android Auto, the real
Assistant and the real app, without a car. Google's instructions are at
<https://developer.android.com/training/cars/testing/dhu>.

### What each check covers

| | `carcheck` | `cartour` | Head unit + real Android Auto (not working here yet) | A real car |
|---|---|---|---|---|
| The service answers correctly (browse, search, play, queue, resume) | yes, item by item | by eye | by eye | by eye |
| Artwork readable by another app | yes | yes | yes | yes |
| Spoken requests reach the app and play the right thing | yes, as the Assistant would send them | no | only with an account | yes |
| The Assistant understands the sentence and the app's name | no | no | only with an account | yes |
| How Google lays out tabs, grids, lists, the player | no | close (same library, different skin) | yes | yes |
| Limits while driving, list lengths, timeouts | no | no | mostly | yes |
| Code that runs only for Android Auto's own package | no | no | yes | yes |
| Gives way to navigation prompts and calls | yes (audio focus) | no | partly | yes |
| Cable, wireless, reconnecting, the car's buttons and speakers | no | no | no | yes |

## The stand-in

`tools/standin` is a **fixture**, not part of the app and never shipped: a small Media3
library service, on the same Media3 version as the app, that offers a car what
`docs/DESIGN.md` says the app will (four tabs, grids and lists, artwork through a content
provider, search, voice, a queue, resumption, like/shuffle/repeat buttons, a sign-in
error). It plays tones it generates. It exists so every check above could be proved end
to end before the app's playback service is written, and so `--selftest` has something
to break. `tools/` has its own Gradle build and is not included in the app's.

It is not a model to copy. It does, though, record three things about Media3 1.11 that
the app's service will meet:

- Callers are trusted or not, and `ConnectionResult.AcceptedResultBuilder(session,
  controller)` gives the untrusted ones read-only commands. Do not narrow what Android
  Auto gets by accident.
- An error returned from a browse request (not signed in) is copied to the session only
  for a moment unless the session is built with
  `setLibraryErrorReplicationMode(LIBRARY_ERROR_REPLICATION_MODE_FATAL)`. A car needs it
  to stay. `carcheck --mode signed_out` tells the two apart.
- `sendError` on the session is likewise shown for a moment and withdrawn. The check for
  a spoken request with no match accepts that and says so.

## Measurements: `scripts/measure`

```sh
scripts/dx ./gradlew --no-daemon :app:assembleRelease
scripts/measure app/build/outputs/apk/release/app-release.apk --tap "Library" --tap "Songs"
scripts/measure org.medianexus.music        # what is already installed, keeping its data
scripts/measure --standin                   # to check the script itself
```

| Number | How it is taken | How far to trust it |
|---|---|---|
| APK size | The file | Exact. It is what a person downloads |
| Installed size | Code and data folders on the device, after opening once | Exact for this image. Needs root, which the "Google APIs" image allows |
| Cold start | Five starts with the process not running, after one that is thrown away; time to the first frame as the system reports it, and to "fully drawn" if the app calls `reportFullyDrawn()` | Compare builds with each other. A phone is faster |
| Memory, idle | The app's share of memory (PSS) ten seconds after opening | Carries over to a phone reasonably well |
| Memory, playing | The same, ten seconds into playback, and again with the app in the background. Playback is started by asking the media service to play, as a car would | Needs the playback service; reported as n/a until then |
| Scrolling | Frame times over eight flings down and four up, from the system's frame statistics | Compare builds only. The emulator draws with the processor |

Give `--tap "<words>"` once for each tap needed to reach a long list from the app's first
screen (sign in first; without `--keep` the app's data is wiped for a clean size, so for
scrolling measure the installed app by package name instead). The numbers are saved as
JSON under `reports/` in the cache.

First numbers, for the record: release build 0.1.0 (M0, sign-in and the empty shell)
on the Android 17 emulator: APK 2.9 MB, installed 3.1 MB, cold start 590 ms (483 to 747),
idle 28.6 MB.

## Gapless playback

The "Continuous Sweep" album is one rising tone cut into songs. By ear, on a real phone:
play the album; any click or stumble at 20, 40, 60 seconds is a gap. By measurement:

```sh
scripts/server sweepcheck recording.wav
```

reads a recording of it and lists every silence and every jump in pitch. It has been
proved on the generated files: joined exactly it finds nothing; with 30 ms of silence put
in at one join and 4 ms removed at another, it finds both, to the millisecond.

What is missing is the recording. The emulator would not open a sound server inside the
container, so the rig cannot yet capture what the app plays; for now a recording has to
come from a real phone's output. See "What is not covered".

## What is not covered

- **The real Android Auto screen**, until someone supplies the package or an account
  (above).
- **Speech**, the car's buttons, the cable, wireless, reconnection: only a car.
- **Sound itself.** No capture from the emulator, so gapless joins, crossfade, levelling
  and the equaliser cannot be measured here yet. The better route is probably inside
  Android (the probe recording the app's output digitally) rather than outside it.
- **Samsung's system.** The emulator is Google's Android. One UI's battery rules and its
  habit of stopping background apps are not reproduced, and they matter to a music player.
- **Real networks.** The server is one hop away with no delay. `scripts/emu adb emu
  network speed edge` and `... network delay gprs` can slow it; dropping the connection
  mid-song is `scripts/emu shell svc wifi disable; svc data disable`.
- **Internet radio, Lidarr, the jam relay, sign-in through Authentik.** The test server
  has none of them.
- **Performance on real hardware.** Start-up and frame times here compare builds, nothing
  more.
- **TalkBack.** `scripts/emu ui` shows every control's name, which is most of what
  TalkBack reads, but nobody has listened to it.

## The one test only a person can do: a real phone in a real car

Do this for the M1 proof build and after any change to the playback service.

**Put the build on the phone**

1. Build it: `scripts/dx ./gradlew --no-daemon :app:assembleRelease`. The file is
   `app/build/outputs/apk/release/app-release.apk`.
2. Copy it to the phone and open it (the phone will ask to allow installing from that
   source), or with the phone on a cable and USB debugging on: `adb install -r app-release.apk`.
3. Open the app, sign in, play something, and download an album if the build has downloads.

**Let Android Auto show an app that did not come from the Play Store**

4. On the phone open Android Auto's settings (Settings, search for "Android Auto").
5. Scroll to the bottom and tap **Version** about ten times, until it offers developer
   settings; accept.
6. Open the three-dot menu at the top right, **Developer settings**, and switch on
   **Unknown sources**. Without this a sideloaded media app never appears in the car.
7. If the app still does not appear in the car, look under **Customise launcher** in
   Android Auto's settings and tick it.

**In the car** (parked; engine or accessory power on; cable first, wireless afterwards if
the car has it)

| Try | Expect |
|---|---|
| Find Nexus Music among the car's apps | Its icon and name are there |
| Open it | Four tabs: Home, Playlists, Library, Downloads. Covers load. No blank screens |
| Open an album, play the third song | It plays; the queue holds the whole album from that song |
| The car's player | Title, artist, cover, progress; like, shuffle and repeat buttons work |
| Steering-wheel next, previous, pause | Each works, with the phone's screen off |
| "Hey Google, play Paper Lanterns on Nexus Music" (use an artist, album, song and playlist of your own) | The right thing plays |
| "Hey Google, play music on Nexus Music" | It resumes, or plays Liked songs |
| Search from the car's screen, if it offers it | Results appear; choosing one plays |
| Start navigation and let it speak | The music dips or pauses and comes back |
| Take a phone call | The music pauses and comes back |
| Switch the car off and on (or pull the cable and plug it back) | The app is still there; play carries on from where it was |
| Force-stop the app on the phone, then open it from the car | It starts and browses with no phone screen involved |
| Drive (a passenger looks) | Lists still usable; note anything cut short or locked |
| Flight mode on, then the Downloads tab | Downloaded music browses and plays |
| Sign out on the phone, look at the car | A message in words, not a blank or a spinner |
| The "Continuous Sweep" album, loud | One smooth rising note, no clicks at the joins |

**Bring back** a photo of anything wrong, what you did just before, and the phone's log:
with the phone on a cable afterwards, `adb logcat -d > car.log`.

## Where things are

| Path | What |
|---|---|
| `scripts/lib.sh` | Shared by the scripts: images, devices, building the tools |
| `scripts/uitree.py`, `scripts/measure.py` | Helpers that run inside the container |
| `docker/rig.Dockerfile` | The rig's image: the build image plus Python, ffmpeg and a virtual display |
| `tools/library/` | The library generator, the server set-up, the sweep analyser |
| `tools/carprobe/` | The car probe |
| `tools/standin/` | The stand-in fixture |
| `~/.cache/nexus-music-android/` | `sdk/` (emulator and images, about 16 GB), `library/`, `navidrome/`, `home/.android/avd/` (the emulators' disks), `reports/`, `shots/`, `logs/` |

To take the rig off a machine: `scripts/emu stop; scripts/server stop`, remove the cache
folder, and `docker rmi nexus-music-android-rig`.
