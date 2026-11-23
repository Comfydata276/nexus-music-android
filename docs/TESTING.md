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
car emulator about 3.4 GB, the Android 16 phone about 2.9 GB, the Android 9 phone about
2.6 GB, the server under 50 MB. Run
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
  addressed by its LAN address. The app asks for the permission when signing in to such
  an address. `scripts/emu install` grants every permission the app declares (`-g`), so to
  see the question, take it away first:
  `scripts/emu shell pm revoke org.medianexus.music android.permission.ACCESS_LOCAL_NETWORK`.
  Refused, signing in at this address times out ("Can't reach the server. The connection
  timed out.") and the app says why; allowed, it signs in.
  Android remembers a refusal: refused twice, it won't ask again (`USER_FIXED`), and the
  app sends "Allow" to its settings page. To see the question again from the start, also
  `scripts/emu shell pm clear-permission-flags org.medianexus.music android.permission.ACCESS_LOCAL_NETWORK user-set user-fixed`.

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

**A long playlist.** `scripts/server longlist` adds "Long list (5,000 songs)", the 100 songs
over and over, for measuring opening, scrolling and starting a long list (`longlist 20000`
for a longer one; `longlist --remove`, or `scripts/server reset`, takes it away). Each time
round starts one song later than the last, so the song a hundred places on is never the
same song (the player's window of a queue moves a hundred at a time, and a queue that is a
window out would otherwise look right). It is added a few thousand songs at a time: the
server refuses 20,000 in one request.

**A genre longer than a page.** `scripts/server manyalbums` adds a genre, "Paging test", of 80
one-second, one-song albums (`manyalbums 200` for more), in a folder of the library of its
own, and waits for the server to read them; `manyalbums --remove` deletes the folder and
scans again. For checking paging: a grid of sixty at a time, the genre page loading them
all, coming back deep into Albums after Android has stopped the app.

One thing learned making it, in case a real library shows the same: Navidrome files an
album's songs as two albums when only some of them carry a release date, and it reads an
M4A's single date as a release date too. The generator writes a release date to every
file for that reason.

## The emulators

```sh
scripts/emu start                 # the phone: Android 17, 1080 x 2340, 384dp wide
scripts/emu -d phone36 start      # the phone on Android 16, what the first real phone runs
scripts/emu -d phone28 start      # the phone on Android 9, the oldest version supported
scripts/emu -d car start          # a car's own system: Android Automotive OS (Android 15)
scripts/emu stop                  # (-d the same way)
scripts/emu start --wipe          # factory reset
```

The phone is Android 17 because that is what a current flagship runs and what the app
targets. Its screen is 1080 x 2340 at 450 dpi, which is a Galaxy S Ultra at its default
display setting (384dp wide); the panel's full resolution would cost the emulator's
software renderer nearly twice the work and show nothing more about layout. `phone36` is
the same phone on Android 16 (its second quarterly release, API 36.1, which is what One UI
8.5 is built on): the first phone the app goes onto, a Galaxy S26 Ultra, shipped with it.
Android's rules for starting playback differ between 16 and 17 (see `docs/DESIGN.md`,
"Starting playback"), so the car check and the cold starts are run on both. The "Google
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
scripts/emu capture start|stop|pull <name>  # debug builds: what the player plays, as a WAV
```

Things worth knowing:

- Animations are switched off and the screen never sleeps, so scripts do not race them.
- An emulator stopped uncleanly leaves lock files that stop the next start; `start`
  clears them (it only does so when that emulator's container isn't running).
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
scripts/carcheck org.medianexus.music --nexus --known-host         # let in by certificate, not by trust
scripts/carcheck org.medianexus.music --nexus --mode queue_slide   # a tap as the queue moves (needs a long playlist)
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
| `browse.tabs`, `tab_icons`, `tab_names` | One to four tabs, all folders, each with an icon; with `--nexus`, exactly Home, Playlists, Library (Downloads joins them when there are downloads) |
| `browse.loads`, `titles`, `ids`, `playable`, `empty` | Every folder (four levels, up to 80 folders) opens within ten seconds; every item has a title and its own id; something can be played; empty folders are listed |
| `browse.complete` | No folder lost its tail. A car asks for a folder in one go and Media3 silently cuts the answer to what fits in 256 KiB, so the probe asks each long folder twice, in one go and page by page, and the two must agree; the longest folder is reported |
| `browse.shuffle`, `play.shuffle` | With `--nexus`: every list of songs is headed by "Shuffle", and choosing it plays that list, shuffled |
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
| `--mode signed_out` | With nobody signed in, the session carries a standing error in words for the car to show, and nothing plays. `signed_out.unasked`: the error is there on connecting, before anything has browsed (a car that only shows the player never browses). `signed_out.search`: a search is answered at once, not left spinning. To be signed out without signing in again afterwards, on a debug build: `run-as org.medianexus.music mv no_backup/password.sealed no_backup/password.keep` with the app stopped, and back again after. (That is also the quick case: with no saved password the sign-in is read at once, before Media3's notification controller has connected, which is when `signed_out.unasked` used to fail) |
| `--mode outsider` | What an app that is not the car may do. With `--untrusted`, nothing: no connection by either protocol, and (with `--nexus`) no covers from the cover provider either. Without it (trusted, as Android Auto is): it connects, but none of the app's own commands are granted or carried out; intents forged to the exported service as Media3's built-in player commands can't change the queue (`outsider.forged`); and intents forged as the notification's like, shuffle and repeat buttons change nothing at all (`outsider.forged_buttons`: the buttons' names and the shuffle and repeat modes are the same afterwards) |
| `--known-host` | The probe with **no** notification access, so Android does not trust it, let in only because the app knows it by package name and signing certificate, as it knows Android Auto from Google's list. On a debug build the rig tells the app the probe's certificate first (`DebugControl --es host`, kept in a file of the app's; nothing is added to the list in the repository) and the whole check must then pass: `known_host.admitted`, and every browse, cover and playback item after it. On a release build there is no way to tell it, so the probe must be refused (the `outsider.*` items): that the release list is Google's and nothing else is part of what passing proves |
| `--mode queue_slide` | A tap on the car's queue made just as the player's part of a long queue moves along. The probe starts a long playlist (`scripts/server longlist 600`; open the app's Library once afterwards so the phone has it), keeps the queue as it was first shown, goes down it until what it is shown starts somewhere else, and at once taps an entry of the queue it kept. Nothing in the tap says which queue it was made on, so `queue.slide` passes if no song plays that the driver did not tap (above all not the one now at that place), and `queue.resent` if the queue is then sent again for the car to redraw. `queue.settled`: a moment later an entry of the queue as it now is plays that entry |
| `--mode long_pause` | What a car does after a long stop: play, pause, stay connected for eleven minutes (`--pause-s N` for another wait), and press Play. Past ten minutes paused the app's service has left the foreground (it is alive only because the probe is bound to it), and `long_pause.resume` and `long_pause.foreground` pass if Play brings the music, and the foreground, back |
| `--mode play` | Only press play and say what plays; with `--media-id "<id>"`, choose that item instead. For scripted checks of one thing. `--warm` (any mode) leaves the app in memory rather than starting it cold |

**Trusted, known and untrusted.** The app lets in three kinds of caller (`docs/DESIGN.md`,
"Who may connect"): itself; callers Android trusts with media; and the known hosts of
media apps by package name and signing certificate (Google's list). The probe can stand as
the second (given notification access: the default), as the third (`--known-host`, debug
builds), or as none (`--untrusted`). What it can never be is Android Auto itself: whether
the real one is let in on a real phone, and by which of the two routes, is the first thing
the Diagnostics page says after a drive.

Media3 gives full control only to callers it trusts: the app
itself, the system, and anything allowed to read notifications. Android Auto is one. By
Media3's default an ordinary app gets to look (browse, search, see what is playing) but
not to touch. The probe is given notification access so that it stands where Android
Auto stands; `--untrusted` takes that away, to see what any app on the phone is allowed.
With the stand-in, untrusted, browsing and search pass and every playback item fails,
which is that default. The app is stricter: a person's library is private, so an
untrusted caller gets no connection at all, and `--mode outsider --untrusted` checks it.

**Is a green report worth anything?** `scripts/carcheck --selftest` answers that. It runs
the stand-in healthy (every item must pass), then switches on one fault at a time, each
of which a specific item must catch: artwork given as web addresses, artwork other apps
may not read, no search, a folder that takes twelve seconds, five things at the root,
spoken requests refused, nothing to resume; and nobody signed in, where the error message
must be found. It takes two minutes. Run it on a phone emulator: on the car emulator the
healthy stand-in fails `art.readable` (seen in the third round, and not looked into; the
stand-in is a fixture, and the app's own check passes there).

**What `carcheck` does not cover.**

- How anything looks. It never sees a pixel of the car's screen.
- Speech. It sends what the Assistant would send after understanding a sentence; whether
  the Assistant understands "play Paper Lanterns on Nexus Music", knows the app by that
  name, or picks the kind of request the check assumed, is Google's side and is untested.
- Android Auto's own rules: lists cut short while the car is moving, how many levels it
  will show, which artwork sizes it asks for, how long it waits before giving up (the
  check allows ten seconds, from Google's quality guidelines), what it does with an
  empty folder or an error.
- Anything the app decides from the caller's name. The app now does decide one thing from
  it: a caller on Google's list of known hosts, signed as the list says, is let in whatever
  Android has granted it. The probe has another name and another signature, so that entry
  of the list is never the one exercised here; `--known-host` exercises the same code with
  the probe's own name and certificate.
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

It is also how to see what a driver sees when something is wrong. Signed out: clear the
app's data on the car (`scripts/emu -d car shell pm clear --user 10 org.medianexus.music`)
and run the tour. Server out of reach: sign in, run the tour once so the screens are
loaded, `scripts/server stop`, and run it again.

**Signing in while the car shows "Sign in"** has no automatic check (the probe can't sign
in), and it broke once without any check noticing. By hand, on the car emulator with a
debug build: clear the app's data; open the car's media screen on the app
(`am start --user 10 -a android.car.intent.action.MEDIA_TEMPLATE -e
android.car.intent.extra.MEDIA_COMPONENT org.medianexus.music/.playback.PlaybackService`)
and see "Sign in"; open the app (`scripts/emu -d car launch org.medianexus.music`) and
sign in; send the same `am start` again, without stopping anything. The car must show
the tabs. The log (tag `NexusPlayback`) says "who is signed in changed: the car is asked
to look again at N folders", and `dumpsys media_session` no longer says `state=ERROR`.

Why it is not Android Auto: the layout, colours and proportions differ; Android Auto's
limits while driving are not applied; there is no Assistant; and here the app is
installed on the car itself rather than projected from a phone, so nothing about the
connection is exercised. The app also has to install and run on Android 15, which this
image is.

`scripts/carcheck` runs on the car emulator too (`NEXUS_DEVICE=car scripts/carcheck ...`),
and on the other phones the same way (`NEXUS_DEVICE=phone36`, `phone28`).

**After the third round of hardening**, on the release build: the whole check on Android
16, Android 17 and the car emulator, 52 passed and none failed on each (one standing
warning, the playlist that is empty on purpose), and 48 and none on Android 9; `--mode
outsider` trusted and untrusted, `--known-host` (refused, as a release build must),
`--mode queue_slide` and `--mode long_pause` on Android 16 and 17; `--known-host` on a
debug build on Android 16 (let in, 49 passed). The reports are in
`~/.cache/nexus-music-android/reports/round3/`.

One thing that run taught about the rig: an emulator keeps the app's kept pages between
rounds, and they outlive changes to the test server. The first run on Android 17 failed
two checks because the phone's kept album listing still named albums another piece of
work had added to the server and taken away again (`scripts/server manyalbums`): opening
them is rightly refused. And after `scripts/server reset`, which gives some songs' covers
new ids, one cover in forty could not be read on Android 16 until the kept pages had
gone. Both show in Diagnostics as `covers asked for by org.medianexus.carprobe: not given
(no such cover)`. With the app stopped, `rm -rf
/data/data/org.medianexus.music/cache/accounts/*/pages` (as root; `/data/user/10/…` on the
car) gives the check the library as the server now has it.

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

Four more, met by the app's own service since (all Media3 1.11.1):

- A standing error set with `MediaSession.setPlaybackException` reaches the platform's
  session, which is what a car reads, only through Media3's own notification controller,
  and only if that controller is connected at the moment the error is set. Set earlier, it
  is on the session and the car is never told. The app sets it again in `onPostConnect`.
- `MediaSessionService.isPlaybackOngoing()` says whether the service has ever been started
  in the foreground. It stays true after Media3 has left the foreground. Whether the
  service is in the foreground now is `Service.getForegroundServiceType()`.
- A queue item's id, to a car, is its place in the player's playlist, and a tap comes back
  as that place (`MediaSessionLegacyStub.onSkipToQueueItem`).
- A new stream is tried against each kind of file before it is read (`sniff`), and the MP3
  extractor's sniff leaves the input past the ID3 tag. Anything that reads beside an
  extractor must not expect the first read to begin at byte 0.

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
M1b (the screens; signed in, kept with `--keep`, idle on Home with its covers): APK 5.3 MB,
installed code 5.5 MB, cold start 471 ms (433 to 511), idle 76.6 MB, playing 82.2 MB (82.5
in the background); scrolling the 100-song playlist, 5.6% slow frames, 95% under 34 ms.
M1b fixes, measured beside M1b's build on the same day (the emulator was slower that day:
M1b's own build then gave 588 ms, 74.1 MB, 83.9 MB, 10.6%): APK 5.4 MB, cold start 510 to
559 ms, idle 76.2 to 80.6 MB, playing 85.1 to 90.7 MB, 100-song playlist 8.7 to 9.5% slow
frames; 5,000-song playlist 8.7% slow frames (M1b's build, 8.7%); opening it with nothing
kept, the app's peak 88.6 MB with the Java heap at 14 MB (M1b's build, 117 to 131 MB with
the heap grown to 45 to 59 MB, the server's answer read into a JSON tree first).
Screens round 2, measured alternately with the build before it (765624f) on the same day,
signed in, kept, on the 100-song playlist: APK 5,782,726 bytes (765624f: 5,766,342); cold
start 566 to 629 ms (765624f: 595 to 612); idle 73.5 to 80.1 MB (75.2 to 75.4); playing 81.2
to 89.9 MB (81.1); 100-song playlist 8.4 to 9.4% slow frames (8.5 to 8.9%); opening the
5,000-song playlist with nothing kept, the app's peak 85.1 to 89.8 MB (86.7 to 90.7), sampled
from `dumpsys meminfo` four times a second while it opens.

Memory on this emulator is mostly not the app's to cut: `dumpsys gfxinfo` shows about 36
MB of the window's own buffers imported into the process (on a phone they are graphics
memory), and decoded covers are 8 MB on a cold Home. The covers' memory cache was set by
counting, in a debug build (log tag `NexusCovers`), what the same browse (ten pages, two
albums again, Now Playing) drew: at 24 MB, 150 covers, 77 from memory and 4 decoded again
after being let go; at 12 MB, 19 decoded again. This emulator's allowance is 192 MB, so
the old 15% share was 29 MB here and the cap changes little; on a phone that allows 512 MB
it was 75 MB.

**Coming back after Android stopped the app.** Background the app, then
`scripts/emu shell am kill org.medianexus.music` (what Android does when it needs memory:
the screens' saved state is kept) and open it again. `scripts/emu kill` is a force stop,
which also throws the saved state away, so the app opens afresh on Home.

## Gapless playback

The "Continuous Sweep" album is one rising tone cut into songs. By ear, on a real phone:
play the album; any click or stumble at 20, 40, 60 seconds is a gap. By measurement:

```sh
scripts/server sweepcheck recording.wav
```

reads a recording of it and lists every silence and every jump in pitch. It has been
proved on the generated files: joined exactly it finds nothing; with 30 ms of silence put
in at one join and 4 ms removed at another, it finds both, to the millisecond.

The recording comes from the app itself. The emulator has no sound to record, so debug
builds carry a tap at the very end of the player's audio chain (after the trimming that
makes joins gapless) that can write every sample it sends to the audio output into a WAV
file:

```sh
scripts/emu capture start sweep        # then play the album from its first song
scripts/emu capture stop               # after the last song ends
scripts/emu capture pull sweep         # to ~/.cache/nexus-music-android/captures/sweep.wav
scripts/server sweepcheck ~/.cache/nexus-music-android/captures/sweep.wav
```

This is exactly what the player hands to Android, so it proves the player's joins.
(A capture also catches a moment of the first song after the last one ends, where the
player parks back at the start: cut the file at the album's length. A capture that
crosses a change of sample rate is written as one file per rate.)

`sweepcheck` knows only the sweep. For any songs at all:

```sh
scripts/server joincheck recording.wav song-1.mp3 song-2.mp3 song-3.mp3
```

decodes each song file with ffmpeg, finds each in the recording, and says to the sample
where it starts and ends against where it should, and how far the recording is from
ffmpeg's decoding. (Several recordings, in order, separated by commas, for a capture that
was split.)

Results on the Android 17 emulator:

- **FLAC** joins exactly, no gap and no jump at any of the sweep's seven joins.
- **MP3** did not at first (M1b): 529 samples (12 ms) of silence at every join, and what
  `sweepcheck` could not see, the first 529 samples of every song missing. The cause and
  the cure are in `docs/DESIGN.md` ("Gapless MP3"). Now each song starts on exactly its
  sample (0, 882,000, 1,764,000 …) and the recording is within 0.0001 of full scale of
  ffmpeg's decoding. `sweepcheck` still reports two small jumps in pitch on the MP3 album:
  they are in the files (ffmpeg's own decoding of them has the same two), from encoding
  each song separately.
- **AAC (M4A) and Opus** join exactly (the compilation album, with `joincheck`).
- **Ogg Vorbis** runs 156 to 224 samples (3.5 to 5 ms) past its end. Not fixed.
- **A song the server converts** has no gapless information; expect a join of about 50 ms.

The MP3 cure is tied to the decoder by name (Android's own software decoder), and to the
file's own header (written by LAME or ffmpeg; see `docs/DESIGN.md`). Every build says which
decoder it met and what it trimmed, in the log (`adb logcat -s NexusPlayback | grep "MP3 is
decoded by"`) and on the Diagnostics page: "trimming 576 and 1008 samples (the file's own
header: …)" is the cure applied; "(as Media3 has them)" is a file or a decoder it was not
applied to. Recorded again after the second round of hardening, on the Android 17
emulator: the MP3 sweep album joins as before (no silence at any join; the two small jumps
that are in the files).
On a phone whose maker supplies another, the numbers are left as Media3 gives them and
only a recording of that phone can say whether they are right.

None of this can show what Android or the phone's hardware does after the player (a mixer
that glitches, Bluetooth); that still needs ears, or a recording of a real phone's output.

## Tests that run Media3 itself

Most of the app's tests are plain JVM tests of plain Kotlin. Where a fault is about what
Media3 does (when its cache writes, how its media source retries, how its session numbers a
queue, when its service enters the foreground), a stand-in for Media3 can agree with the
code and both be wrong, which is how the first round of hardening passed with faults
standing. Those tests run Media3's real classes on Robolectric (Android on the JVM), with
Media3's own test kit for a clock the player can be run against. They are part of
`scripts/check` like the rest.

| Test | What is real in it |
|---|---|
| `KeptConversionTest` | `SimpleCache`, `CacheDataSource`, `CacheDataSink` under the player's data source: which conversions are kept |
| `ConvertedPlaybackTest` | ExoPlayer, the progressive media source and the MP3 extractor, against a stand-in server shaped like Navidrome; a renderer reads back from each frame the time in the song it belongs to |
| `QueueTapTest`, `StartsTest` | ExoPlayer under the queue's player, driven the way Media3's session drives it |
| `ColdStartTest` | The playback service itself on Media3's session service, started by a Play button: in the foreground when the start command returns; a start that comes to nothing ended by its seven-second limit and not before; Play with nothing to resume ended at once |
| `StartingTest` | The service whole, with a stand-in for Android's foreground (the one thing here that can say no): in the foreground before anything is looked up, no Play when Android refuses, the car told why, Media3 not taken to hold the foreground on its own say-so; a real `MediaController` whose request fails; a caller with a borrowed name; the server's own words kept off the session |
| `ForgedStartTest` | The service signed in to a stand-in server (`StandInServer`), sent the start intents any app can send: a forged like, shuffle or repeat changes nothing; the like button of the notification Media3 really posted still likes |
| `CoverAddressTest` | The app's real cover provider and cover stores, signed in as one account and then another |
| `RangedAnswerTest` | Media3's `OkHttpDataSource` over a real connection, counting the bytes of each answer actually read: an answer that ignored a range is not read up to the place |
| `Mp3TrimTest` | Media3's MP3 extractor reading made-up files with and without a LAME header, sniffed first and then read, as Media3 does a new stream |
| `KnownHostsTest` | The app's own copy of Google's list, read from its resources |

What Robolectric cannot be: Android's rules about foreground services and audio, another
app's process, a real `MediaControllerCompat` across a binder, Android's own answer about a
package's signing certificate, the platform's own media session (a media button that
arrives before Media3's notification controller has connected goes through it, and on
Robolectric goes nowhere). Those are the emulator's (below) or the car's.

**Nothing in them waits in real time for something to happen.** Robolectric's main thread
runs only when a test says so, and its clock moves only when a test moves it: that is the
gate that holds a request at its arrival, before anything has been looked up, and what
lets a test tell "ended at once" from "ended by a time limit". Where another thread's work
has to finish first (the saved sign-in being read, a request to the stand-in server), it
is waited for by letting the main thread run until that thing is so, with the clock
standing still, and never as a way of ordering the events under test. Two tests used to
get this wrong and are the examples to avoid: one judged a limit by the wall clock on a
shared machine, and one waited for the service to stop "because there was nothing to
resume" while moving the clock on, so that what stopped it was a time limit.

And a test that runs Media3 can still run it differently from the way the player does. The
MP3 header reader passed its tests and did nothing on the emulator: the test handed the
extractor a file to read, where Media3 first tries each kind of file on a stream and only
then reads, and that first step moves past the tag. It was the app's own Diagnostics line
on the emulator ("trimming … (as Media3 has them)") that showed it. After a change to how
songs are read, record the MP3 sweep album again (below) and read that line.

## The playback service's hard cases

Debug builds answer to adb through `DebugControl`, a `MediaController` like the app's own
screens have (`scripts/emu shell am broadcast -n org.medianexus.music/.playback.DebugControl …`):

| Extras | What it does |
|---|---|
| `--el seek 250000` | Seeks in the song, to that many milliseconds |
| `--ei item 4` | Jumps to that item of the player's playlist |
| `--es say state` | Answers with the state, the song, the position, the place in the queue and its length |
| `--es stalls start` (`stop`) | Logs, under the tag `NexusStall`, every time the main thread is held for more than two frames (33 ms), and by what |
| `--es capture start --es name x` (`stop`) | The recording above; `scripts/emu capture` does these |
| `--es host "<package> <sha256>"` (`--es host ""`) | Adds a caller to be let in as a known host, by its package name and the SHA-256 of its signing certificate (or forgets them all); `scripts/carcheck --known-host` does this for the probe |
| `--es press play` | Presses Play, as the app's own screens do, from wherever the app is: sent while the app is in the background, it is a Play that Android may refuse (below) |

**A Play button with the app not running.** The system gives a service started this way
a few seconds to show itself as playing, and kills the app if it doesn't. End the process,
clear the log, send the button (`scripts/emu shell input keyevent 126`), wait, and read the
log for `ForegroundServiceDidNotStartInTimeException` and for the service's own account
of what it did (tag `NexusPlayback` in a debug build; the Diagnostics file in any:
`run-as org.medianexus.music cat files/diagnostics.txt` on a debug build, Settings, About
on any). The cases: signed out (move `no_backup/password.sealed` aside with `run-as`);
signed in with no saved queue; a saved queue; a saved queue with the server stopped and
the song not kept (remove `cache/accounts/*/songs`: kept, it plays without the server);
a saved queue file with rubbish in it. None may end in that exception, and only the
saved-queue cases may start the service at all.

Two ways of ending the process, which are not the same thing:

- `am kill` is what Android does when it wants the memory. It only ends an app that is in
  the background with nothing running: open the app, press Home, then `am kill` (it does
  nothing to an app whose service is started, which this one's is after playing).
- `am force-stop` (`scripts/emu kill`) also puts the app in Android's "stopped" state and
  throws away its saved state and pending intents. On the Android 17 emulator a media
  button still starts a force-stopped app. For the rubbish-file case use this one: opened
  first, the app finds the file damaged and clears it away.

What to expect in a saved-queue case, from the Diagnostics file: "a media button with the
player not running: starting it", "player started by a media button (MEDIA_PLAY), with the
app not running", "foreground: entered at once for a media button, N ms after the player
was made" (about 0.1 s on the emulator), then "foreground: playing in it N ms after…"
(about 0.5 s), then "sound: the audio track is moving. In the foreground: yes; Android
lists 1 media player sounding; media volume N of M" (an emulator run with no sound device
names no output; a phone does).

Run after the third round of hardening on the release build, on Android 16 and on Android
17 with the hardening below forced on (`throw`): a saved queue after a force-stop and
after `am kill` (in the foreground 21 to 51 ms after the player was made, playing in it
78 to 166 ms); nothing saved, and nobody signed in ("nothing to resume, so not started");
a damaged queue file ("asked to play with nothing to play: left the foreground and
stopped cleanly"). No `ForegroundServiceDidNotStartInTimeException`, no crash, in any.
With the server out of reach and the song not kept, on Android 16: "Reconnecting…", still
in the foreground two and a half minutes later, and playing again by itself within ten
seconds of the server being back. (Before this round a start with the server out of reach
was stood down at its seven-second limit.) The evidence is in
`~/.cache/nexus-music-android/reports/round3/`.

**The release build's files.** A release build has no `run-as`. On these emulators (the
"Google APIs" images) `scripts/emu root` makes adb root, and the Diagnostics file is then
`/data/data/org.medianexus.music/files/diagnostics.txt`. Restarting adb as root drops the
port it passes back to the test server: put it back with `scripts/emu adb reverse tcp:4533
tcp:4533`, or the app can't reach the server and says "Reconnecting…" (which is how the
third round found that out).

**A Play that Android refuses.** The one place Android can be made to say no on an
emulator: a debug build, something in the queue, paused, the app in the background for
eleven minutes (so the service has left the foreground: no `isForeground=true` in `dumpsys
activity services org.medianexus.music`), and then Play sent from inside the app's own
process with nothing of the person's behind it (`DebugControl --es press play`). Expect, in
Diagnostics: `foreground: Android refused it to play (ForegroundServiceStartNotAllowedException)`
and `playing could not be started: Android would not let the player into the foreground.
Left paused, and the session says to open the app`; in `dumpsys media_session`:
`state=ERROR`, the error "When it's safe, open Nexus Music on your phone to start playing",
and Play still among its actions; and nothing playing. Open the app and press Play: it
plays, and the error has gone. Seen exactly so on the Android 16 emulator after the third
round (`reports/round3/android16-debug-refused-play.txt` in the cache). It is the only
refusal the rig can make: the probe, which stands for the car, is always allowed (an app
under instrumentation counts as in front), so whether Android ever refuses the real
Android Auto is still the phone's to say.

**Android 17's rules for sound from the background** can be forced on the emulator, whatever
the app targets: `scripts/emu shell cmd audio set-hardening throw` (or `enable`;
`clear-hardening` to put it back). `throw` makes a refused request for the sound an
exception rather than silence, so a start that Android would have muted shows as a crash.
The cold-start cases were run both ways.

**Paused for more than ten minutes, then Play.** Media3 keeps the service in the foreground
for ten minutes after playback stops; after that the service is an ordinary background one,
and a Play button reaches it through the session, not through a start command. Play, pause,
press Home, wait eleven minutes, see `isForeground=false` in `dumpsys activity services
org.medianexus.music`, send the button, and see PLAYING and `isForeground=true` again.
`scripts/carcheck … --mode long_pause` does the car's version of this (the probe stays
connected, as a parked car does) and was run on Android 16 and 17 after the third round:
`foreground: entered at once to play` in Diagnostics, `long_pause.resume` and
`long_pause.foreground` passing.

**Android 9.** A Play button with the app not running never reaches the app there (see
`docs/DESIGN.md`, "Starting playback"): `MediaSessionService: Sending KeyEvent … to the
restored intent ComponentInfo{…PlaybackService}` in the log, and nothing after it. What can
be checked on Android 9 is the service's own start: `am start-foreground-service -n
org.medianexus.music/.playback.PlaybackService -a android.intent.action.MEDIA_BUTTON`
(adb cannot attach the key, so there is nothing to do): in the foreground within a second,
out of it and stopped seven seconds later, nothing in the log about a foreground service
that did not start.

**Swiped away.** Play something, swipe the app out of the recent-apps list, and look ten
seconds later: `dumpsys media_session` says PLAYING and `dumpsys activity services
org.medianexus.music` says `isForeground=true`. Paused first, the service is gone.

**A very long queue.** The library has nothing long, so make a playlist of every song many
times over through the API (`createPlaylist` with thousands of `songId`s; 20,000 was
used). Then with `stalls start`: open it in the car check, start it, press Next for a
minute, jump far down it, press Next on its last song. Measure in a build that is not
debuggable, since a debuggable one runs several times slower: add
`isDebuggable = false` to the debug build type for the measurement and take it out again.
With the player holding a window of the queue (see `docs/DESIGN.md`), 20,000 songs
measured: starting it from the car, one stall of 35 ms; Next, none over 33 ms; a jump far
away 54 ms; the app's Java heap about 55 MB. Before, the same playlist ran the app out of
memory.

`scripts/carcheck … --warm` leaves the app in memory (the stall log is switched on in the
process, and the check's cold start would otherwise end it). In a debug build as it is
(several times slower), `--mode queue_slide --warm` on the 20,000-song playlist, the phone's
copy already kept: before the car's folders were worked out off the main thread, stalls of
145 ms and 97 ms laying the list out; after, none from the list, and up to about 80 ms from
the player moving its own window when the probe jumps a hundred songs down the queue.

**A song the server converts.** Set a quality in Settings, turn on debug logging, play a
long song and seek: the log line "a conversion opened part-way, at byte N: asked of the
server from second S" shows what was asked. To see where a seek really landed, record the
sweep album converted and read the pitch at the landing (`sweepcheck` prints it): at
128 kbps, seeks to 15.0, 4.0 and 18.5 seconds landed at 15.05, 4.07 and 18.56. To see that
only whole conversions are kept: play one to its end and another only part-way, stop the
server, and play both again.

There is no setting for the quality yet: on a debug build, with the app stopped,
`run-as org.medianexus.music sed -i 's/"bitrate": 0,/"bitrate": 128,/' files/settings.json`
(and back to 0 afterwards). Checked that way against the test server after the second round
of hardening: a five-minute Opus song converted at 128, seeks to 200 s and to 20 s land
(203.6 s and 23.7 s four seconds later; "asked of the server from second 199", "from second
19"); an MP3 of 73 kbps, which the server sends as it is whatever is asked, is not asked for
as a conversion at all, and a seek in it lands (before, it began again from its start with
the new time on the clock). What this rig cannot do is drop a conversion part-way: stopping
the server does not break a stream the emulator's own tunnel has already taken whole. That
case is `ConvertedPlaybackTest`'s, and a real network's.

## What is not covered

- **The real Android Auto screen**, until someone supplies the package or an account
  (above).
- **Whether the real Android Auto is let in, and whether Android lets it start the
  music.** The probe is not Android Auto: it is trusted because the rig says so, or known
  because the rig says so. On the phone, Android Auto is whatever Samsung and Google made
  it: it may or may not hold notification access or the system's media permission, its
  signing certificate may or may not be one of those on Google's 2022 list, and Android 17
  may or may not count its Play as something the person did. See "What only the phone and
  the car can say" below.
- **Whether Android ever refuses the real Android Auto, and what the car then shows.** The
  rig's probe is always allowed into the foreground (an app under instrumentation counts
  as in front), on Android 16 and 17 both. A refusal can be made on an emulator only from
  inside the app's own process ("A Play that Android refuses", above), which shows what the
  app does about one, not when a car meets one. And what Android Auto draws for the
  player's error line with Play still offered is Google's to decide.
- **Whether Android has muted the player.** No app is told. Diagnostics records everything
  short of it (see `docs/DESIGN.md`, "Whether it is heard").
- **Speech**, the car's buttons, the cable, wireless, reconnection: only a car.
- **Sound after the player.** `scripts/emu capture` records what the player sends to
  Android (gapless joins can be measured; crossfade, levelling and the equaliser can be
  when they arrive), but not what Android's mixer or the phone's hardware do with it.
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
| Open it | Three tabs: Home, Playlists, Library (and Downloads once the build has downloads). Covers load. No blank screens |
| Open a long playlist (over 200 songs) | Numbered ranges ("1–200 …"); a song chosen in one plays the whole playlist from there; nothing missing at the end |
| "Shuffle" at the top of an album or playlist | The whole list plays, shuffled |
| Open an album, play the third song | It plays; the queue holds the whole album from that song |
| The car's player | Title, artist, cover, progress; like, shuffle and repeat buttons work |
| Steering-wheel next, previous, pause | Each works, with the phone's screen off |
| "Hey Google, play Paper Lanterns on Nexus Music" (use an artist, album, song and playlist of your own) | The right thing plays |
| "Hey Google, play music on Nexus Music" | It resumes, or plays Liked songs |
| Search from the car's screen, if it offers it | Results appear; choosing one plays |
| Start navigation and let it speak | The music dips or pauses and comes back |
| Take a phone call | The music pauses and comes back |
| Switch the car off and on (or pull the cable and plug it back) | The app is still there; play carries on from where it was |
| Force-stop the app, then press play on the steering wheel or a Bluetooth headset | The saved queue carries on; with nothing saved, nothing happens (and nothing crashes) |
| Pause in the car, leave it paused for a quarter of an hour (phone screen off), then press Play on the car's screen, and another time on the steering wheel | It plays. (After ten minutes paused the player is no longer a foreground service; this is the start Android 15 and 17 have rules about) |
| With a long playlist playing (over 300 songs), open the car's queue and tap entries well down it, several times | Each tap plays the song that was tapped |
| Turn on a streaming limit if the build has the setting; seek about in a long song; drive through a spot with no signal for under a minute | Seeks land where asked; the song carries on from where it was, not from its start |
| Drive out of reach of the server for more than ten minutes | "Reconnecting…", then paused with "Can't reach your music server"; Play, once back in reach, carries on from where the song stopped |
| Afterwards, on the phone: Settings, About, Show diagnostics | A page of text; Share sends it. Bring it back whatever happened |
| Play, swipe the app away on the phone | The music carries on |
| Drive out of reach of the server (or switch Wi-Fi and data off) for a minute, then back | "Reconnecting…" on the car's screen, the song stays where it was, and it carries on by itself |
| Sign in on the phone while the car shows "Sign in" | The car's lists appear without leaving and re-entering the app |
| Force-stop the app on the phone, then open it from the car | It starts and browses with no phone screen involved |
| Drive (a passenger looks) | Lists still usable; note anything cut short or locked |
| Flight mode on, then the Downloads tab | Downloaded music browses and plays |
| Sign out on the phone, look at the car | A message in words, not a blank or a spinner |
| The "Continuous Sweep" album, loud | One smooth rising note, no clicks at the joins |

**Seven more, each for something only a phone in a car can show.** Do these as well, one
at a time, and note the time you did each: Diagnostics lines carry a time, and each of
these has lines of its own to read afterwards (Settings, About, "Show diagnostics").

| Try | Expect | Diagnostics lines to read |
|---|---|---|
| **First Play, phone locked, a playlist never opened on the phone.** Make a playlist on another device. Force-stop Nexus Music, lock the phone, plug it in, and from the car open that playlist and choose a song | It plays within a few seconds, with the phone still locked | `player started by a media browser connecting (a car, or the system), with the app not running`; `foreground: entered at once for a choice, N ms after the player was made`; `foreground: playing in it N ms…`; `sound: the audio track is moving. In the foreground: yes; Android lists 1 media player sounding, going to …`. If instead: `foreground: Android refused it for a choice (…)` and `playing could not be started: Android would not let the player into the foreground…`, the car should have shown "When it's safe, open Nexus Music on your phone to start playing": say whether it did, and whether pressing Play again then played |
| **The resume card after the app was ended.** Play something, pause, swipe the app away, and wait until its notification has gone. Then use the media card on the lock screen, and another time the one in Quick Settings, to resume. Then the same after a force-stop (Settings, Apps, Force stop). Then after restarting the phone: once before unlocking it, once after | After being swiped away or force-stopped: the card is there and resumes where it stopped. After a restart, before the first unlock: nothing can play (the app's files are locked until then, and nothing can be recorded either); after unlocking, the card resumes | `library opened by com.android.systemui (recent: yes; …)` (the system asking what it would resume); `a media button with the player not running: starting it`, or a `player started by …` line; `foreground: entered at once for Play with nothing loaded` or `… for a media button`; `foreground: playing in it N ms…`. `…nothing to resume, so not started` means there was no saved queue; `a media button with the player not running: Android would not let it be started from the background` is Android saying no |
| **Artwork on a large grid never seen before, over mobile data.** Wi-Fi off. In the car open Library, Albums, and a letter (or an artist) with many albums the phone has never shown | Covers fill in within a few seconds each; none is another album's; the list scrolls meanwhile | `covers asked for by com.google.android.projection.gearhead: given` with a count; `…: not given (too many being fetched at once)` or `…(no such cover)` with counts says how many came back empty the first time (they are fetched all the same, and are there when asked for again); `…: not given (it is another account's)` should never appear unless you changed account; `…: refused` means Android Auto was not let read covers at all |
| **Another player was the last one used, then the wheel's Play key.** Play something in another music app, pause it. Without opening Nexus Music, press Play on the steering wheel. Then open Nexus Music in the car, play, pause, and press the wheel's key again | First press: the other app plays (Android sends the key to whichever player was used last; that is not ours to change). After Nexus Music has been used: it plays | For the first press, **no line at all** at that time is the right answer. For the second: `foreground: entered at once to play` (or `… for a media button`), `foreground: playing in it…`, `sound: the audio track is moving…` |
| **Where the sound goes with the cable and Bluetooth both connected.** With the phone on the car's cable for Android Auto and also paired to the car (or a headset) over Bluetooth, play, and note where you hear it; then pull the cable and note again | Through the car while on the cable; whatever the phone then does (it usually pauses) when the cable goes | `sound: the audio track is moving. … Android lists 1 media player sounding, going to <a remote submix (an app carrying the sound on, as Android Auto does) / Bluetooth / USB / the phone's speaker>; media volume N of M`: the kind of output Android said the music was going to, each time playing began. Say whether that matches what you heard |
| **A tap on a row just redrawn.** With a long playlist playing (over 300 songs), open the car's queue. From the phone, drag a song in the queue to another place; within a second of the car's list changing, tap a row that moved. Then tap it again | The first tap may do nothing (the car's list redraws); it must never play a different song from the one on the row. The second tap plays the row | Nothing is recorded for this: it is for eyes. Say what played, if anything other than what you tapped ever did, and roughly how long the car took to redraw its queue (the rule in `docs/DESIGN.md` assumes under a second) |
| **Leaving home Wi-Fi for mobile data in the middle of a song.** Start a song at home on Wi-Fi and drive off (or switch Wi-Fi off) | If your server can be reached from outside: the song carries on, perhaps after a pause of a few seconds, from where it was. If it can only be reached at home: "Reconnecting…", and after ten minutes paused with "Can't reach your music server" | `playback error: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` (or `…_TIMEOUT`) with a count; `sound: the audio track ran dry for a moment`; then another `sound: the audio track is moving…` when it carries on. `the server was out of reach for ten minutes: paused` if it never did |

**Bring back** a photo of anything wrong, what you did just before, the **Diagnostics**
(Settings, About, "Show diagnostics", then "Share": it is text, and names no server, account
or song), and the phone's log: with the phone on a cable afterwards, `adb logcat -d > car.log`.

### What only the phone and the car can say, and where the answer will be

Each of these was decided as well as it can be from Google's documentation, Android's
source and the emulators, and none of them can be settled there. The Diagnostics page was
built to settle them on the first drive.

| The question | What to look for in Diagnostics |
|---|---|
| Does the real Android Auto get a connection, and by which route? | `connection from com.google.android.projection.gearhead by Android's own media session and browser: let in. Android trusts it: yes/no; known host: yes/no`. "Refused" with both "no" means its certificate is not on Google's list (the list is from December 2022): the car lists the app and cannot open it, and the list needs that certificate added |
| Which other apps connect on a Samsung phone (its own media controls, its watch app, Bixby), and are any refused that should not be? | One `connection from <package>` line each, with the outcome |
| Does Android Auto get the covers? | `covers asked for by com.google.android.projection.gearhead: given` (or "refused"); and the same for `com.android.systemui` and anything Samsung's |
| What starts the player in the car, and with the app not running? | `player started by a media browser connecting (a car, or the system), with the app not running` (or "a media button (…)", "a Media3 controller connecting") |
| Does Android (16 on the first phone, 17 later) let it into the foreground when the car chooses a song, asks for one aloud, or presses Play, and how soon? | One line for each kind of request, written as it arrives: `foreground: entered at once for a choice`, `…for a spoken request`, `…for Play with nothing loaded`, `…to play`, `…for a media button`, the first with `N ms after the player was made`; then `foreground: playing in it N ms…`. `foreground: Android refused it <for what> (…)` is Android saying no at that moment; `playing could not be started: Android would not let the player into the foreground. Left paused, and the session says to open the app` is what the app then did instead of playing. `foreground: still held by the player's own notification 7 s after it was entered…` and `foreground: Media3 did not take it over when playback began` are the hand-over to Media3's notification going wrong |
| What does the car show when Android does refuse? | The player's error line: "When it's safe, open Nexus Music on your phone to start playing", with an "Open Nexus Music" button if the car draws one, and Play still there. Say what the car actually showed (a photo), and whether Play pressed again then played |
| Is it heard (Android 17's "while-in-use" question for a Play that came from the car; on 15 and 16, audio focus)? | Each time playing begins: `sound: the audio track is moving. In the foreground: yes; Android lists 1 media player sounding, going to <kind of output>; media volume N of M`. All of that, and silence in the car, is Android muting it (which the app is not told): `adb shell dumpsys audio` afterwards has lines beginning `AudioHardening` with the app's name. `sound: playing for 5 s and the audio track has not moved` is the app's own player not producing sound. `audio focus: refused or taken away at once after Play` followed by `playing could not be started: Android would not give it the sound…` is Android refusing focus, and the car is told as above |
| Did anything on the phone send the player a start intent shaped like a notification button without being this app? | `a start request shaped like a notification button (like, shuffle or repeat), without this app's mark: not carried out`. On the proof build's own phone this should never appear; if it does, something else is pressing the player's buttons |
| Which decoder plays MP3 on this phone, and is the trim right for it? | `MP3 is decoded by <name>; trimming N and M samples (the file's own header…)` or `(as Media3 has them)`. A name other than `c2.android.mp3.decoder` means Samsung's own, which is left with Media3's numbers: then the "Continuous Sweep (MP3)" album, by ear, says whether there is a gap |
| What goes wrong on a real network? | `playback error: <kind>` lines with counts, `the server was out of reach for ten minutes: paused` |

And these nothing on the phone records, so they are for eyes and ears:

- how Android Auto lays out the tabs, grids, ranges and "Shuffle"; what it does with a
  folder of 200; whether it shows the queue, and how long it takes to redraw it when the
  queue moves (the one-second rule in `docs/DESIGN.md` assumes under a second); what it
  does with a tap that the app ignored (a tap on a row that moved in the last second does
  nothing, and the queue is sent to the car again);
- what the car does with the player's error line when playing can't be started ("When
  it's safe, open Nexus Music on your phone to start playing"): where it shows it, whether
  it draws the "Open Nexus Music" button, whether Play is still offered beside it;
- whether the Assistant knows the app by name and what it sends;
- steering-wheel buttons, the cable, wireless, reconnecting after the car is switched off;
- a Play button after the phone has been restarted and the app not opened since;
- One UI's own battery rules: whether it stops the app in the background mid-drive (put
  the app on "Unrestricted" under Battery if it does, and say so);
- the sound itself: gaps at joins, the level, a click after a dropped connection picks up.

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
