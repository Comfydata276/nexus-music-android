# Nexus Music for Android: design

This is the design the app is built to. If the code and this file disagree, one of them is
wrong: fix it, or raise it with the lead, in the same piece of work.

Nexus Music is a music player for a [Navidrome](https://www.navidrome.org/) server. The
Windows app came first and lives in its own repository (checked out beside this one, at
`../nexus-music`). This is the phone app: the same product, the same look, for Android.

## What matters, in order

1. **Android Auto works.** Browse, play, voice and steering-wheel controls in the car, with
   no phone screen involved. Without this the project has no point.
2. **Offline downloads work**, in the car too.
3. **It is fast and light.** Opens at once, scrolls without a hitch, small install. No
   analytics, no ads, no Google Play services, no browser engine, no framework we don't need.
4. **It looks and behaves like the Windows app.** Same colours, type, icons and words. A
   person who uses one should feel at home in the other.
5. **It does everything the Windows app does** that makes sense on a phone (see Milestones).

When two of these pull against each other, the higher one wins.

## Stack

| Part | Choice |
|---|---|
| Language | Kotlin |
| Interface | Jetpack Compose, Material 3 only as a parts bin (our own theme on top) |
| Playback | Media3: ExoPlayer inside a `MediaLibraryService` |
| Network | One OkHttp client for the API, artwork and audio |
| JSON | kotlinx.serialization |
| Images | Coil 3, fed by our own cover store |
| Background work | Coroutines; Media3's `DownloadService` for downloads |
| Build | Gradle with the version catalogue in `gradle/libs.versions.toml`, inside Docker (`scripts/dx`) |
| Tests | JUnit on the JVM; where a test is about what Media3 does (its cache, its media source, its player and session service), Media3's own classes on Robolectric with Media3's test kit. Test only: none of it is in the app |

Versions are fixed in the catalogue and raised on purpose. Before using an API from
memory, check it against the version in the catalogue: these libraries have moved on.
Adding a dependency needs a reason written in the commit message; "it's convenient" is not
one. Ask the lead before adding anything large.

Lowest Android version supported: 9 (API 28). Built against the newest platform.

## Shape of the code

One Gradle module, `app`, package `org.medianexus.music`:

| Package | What's there |
|---|---|
| `net` | Client for the Subsonic API Navidrome serves at `/rest`; models; errors. A port of `src/subsonic.rs`. |
| `store` | What's kept on the phone: the account, settings, cached pages, the library copy, covers, the downloads list, changes waiting for the server. |
| `library` | Turns server answers into what screens show; search; likes; playlists. |
| `playback` | The playback service, the queue, play reporting, Android Auto's browse tree and voice search. |
| `downloads` | Offline downloads. |
| `ui` | Compose: `theme`, `parts` (shared components), one package per screen. |

Rules that hold everywhere:

- **One process, no dependency-injection framework.** `App` (the `Application`) builds a
  `Graph` of long-lived objects once; everything gets what it needs from it by constructor.
- **The service must work with no screen.** A car starts the playback service cold.
  Anything browsing or playing needs lives in the graph, never in an activity or a
  composable.
- **Nothing slow on the main thread.** Network and disk run on `Dispatchers.IO`.
- **State flows one way.** Stores expose `StateFlow`s; screens collect them and send
  intents back. No screen talks to the network itself.
- **Show what you have, then check.** Every page is kept on disk and drawn from that copy
  at once, then refreshed from the server in the background. The Windows app's
  "What keeps it quick" section in its README is the rulebook; read it.
- **Words:** everything a person reads lives in `res/values/strings.xml`, and uses the
  Windows app's wording wherever the feature is the same. Plain English, sentence case.
  The one exception is the API client's error messages, which are ported with it in code.
- **Signed in, or not yet known.** Just after start-up the saved sign-in is still being
  read (`SessionState.Restoring`). Anything that starts cold, the playback service above
  all, waits for that to settle before deciding nobody is signed in.
- **Comments** say why, as the Windows code does. Read a few of its files for the tone.

## Playback

- **One ExoPlayer**, owned by `PlaybackService` (a `MediaLibraryService`). The interface
  talks to it through a `MediaController`, the same way the car does. There is no second
  path to the player.
- **A song is named, not addressed.** A media item carries the song's id and a
  `nexus://song/<id>` address. A resolving data source decides, at the moment the song is
  opened, where the bytes come from: a downloaded copy, else the kept copy, else the server
  at the chosen quality. Offline, quality and prefetching all live in that one place.
- **Kept songs.** Streams pass through a size-limited cache (2 GB unless changed) so
  replays and dropped connections cost nothing, and the next few songs in the queue are
  fetched ahead. Cache entries are keyed by song id and quality, never by the address,
  which carries a sign-in token that changes.
- **The queue behaves as it does on Windows**: the list being played, plus "Next in queue"
  for songs added by hand, which play straight after the current song and ahead of the rest.
  Port the behaviour from `src/playback.rs`; its tests are the specification.
- **The queue is ours; ExoPlayer plays it.** A plain Kotlin model (the songs, the order
  they play in, the place in it, which were queued by hand) is the truth and is tested on
  its own. ExoPlayer's playlist always holds the songs in exactly the order they will
  play, and its own shuffle stays off: shuffling means we reorder what is to come. That
  way "next", gapless joins, the car's queue and our queue screen can never disagree.
- **Gapless** comes with ExoPlayer. **Crossfade** (which leaves an album alone), **volume
  levelling** from ReplayGain tags, the **six-band equaliser** and **mono** are ported from
  `src/audio/` as audio processors in a later milestone. Design the player set-up so they
  slot in.
- **Play reporting** as on Windows: "now playing" when a song starts; a play counts after
  half the song or four minutes, whichever comes first; offline plays are held and sent
  later.
- **The phone's own rules are respected**: audio focus, pausing when headphones are pulled,
  a media notification, lock-screen controls, carrying on in the background.
- **Resumption.** The queue and position survive the app being killed, so "resume" from
  the car, a headset button or the notification works with nothing on screen.

How it is built (M1a):

- **`QueuePlayer`** (a `ForwardingSimpleBasePlayer` around the ExoPlayer) is the session's
  player and the one place the model and ExoPlayer are kept in step. Every queue entry has
  a number of its own (`uid`, carried in the media item's tag), so the same song twice is
  two entries. A change to the model is put to ExoPlayer by replacing what is before and
  after the song playing, never the song itself; ExoPlayer moving on by itself is put back
  to the model. It reports our shuffle and repeat, and a timeline whose shuffled order is
  its playing order, so no controller looks for a shuffle underneath.
- **The player holds a window of the queue, not the queue** (`QueueSync`): 25 songs before
  the one playing and at least 200 after, moved along in steps of 100, always at the same
  songs of a queue (its 125th, its 225th, …). Everything ExoPlayer's playlist holds is paid
  for on the main thread at every event, and copied to the system, the notification and the
  car at every change, so a 20,000-song playlist must not be in it. The model holds
  everything, and what the window has passed is let go. The session, the car and the app's
  screens see the window; the screens are told the total as well (`UpNext.total`). Next,
  Previous, repeat-all and the end of the queue are therefore the model's to decide: Next on
  the last song goes round to the top and plays on only if repeat is on, as on Windows (on
  a queue of one song that is the song started again). With repeat-all on and the end of a
  long queue in the window, the first three songs are held after the last, so the player
  runs from the last song into the first without a gap.
- **A tap on the car's queue.** Media3 numbers a car's queue items with their place in the
  player's playlist, and a tap comes back as that place and nothing else. Whenever the
  places move (the window slides, the queue is reordered, something is added or removed)
  every place the car is still showing means another song until its screen has caught up.
  The player remembers the places as they were before each change of the last second
  (`QueuePlayer.entryMeant`), and a place sent in that second by anyone but the app's own
  screens is read as it was shown: the song that was on the screen plays, wherever it is
  now; if it has left the queue, nothing does. **What remains:** nothing in a tap says which
  queue its sender was looking at, so this is a rule about time. A car that takes longer
  than a second to redraw its queue, and is tapped after that second on an entry it has not
  yet redrawn, plays the song now at that place. It can only happen at the moments the
  places move: every hundredth song, and when the queue is edited from the phone.
- **Starting a list** is building a queue (`QueuePlayer.prepare`), which for a long list
  is done off the main thread, and taking it over (`Queue.adopt`, which copies nothing).
  **The car's** start goes through the session, which asks for media items: the service
  builds the queue, hands back the window's items (`stage`), and `QueuePlayer` recognises
  them by their numbers when they arrive. **The app's screens** name a long list that is a
  page the phone keeps (an album, a playlist, Liked songs; over 200 songs) and its place,
  and the service reads the songs from the library's kept copy; a short or ad hoc list is
  sent as it is. **Starts are numbered** by the player (`QueuePlayer.beginStart`): one that
  has to look something up takes its number as it begins, and is asked whether it is still
  the newest twice, when its queue is handed to the session and again when the session's
  items reach the player, because a choice can be made in either wait. A start that has
  been overtaken hands over nothing and disturbs nothing; a change of account overtakes
  them all; "carry on" (a Play button) is not a start and gives way to any. A page started
  by name names the song tapped and the songs around it: if the phone's copy has changed by
  the time it is read, the list starts at that song wherever it is now, else at its nearest
  neighbour still there, and an empty list starts nothing and says so.
- **The app's screens** use `PlayerConnection`: a `MediaController` with `StateFlow`s and
  commands. What only the app asks for (play a list with its source, the "Next in queue"
  edits) goes as custom session commands. An edit names the entry it means by the entry's
  own number, never by its place on the screen, so a song ending between the tap and the
  command can't shift it onto another. The length of "Next in queue" and the total to
  come are in the session's extras.
- **Who may connect** (`Access`, `Callers`). The library is private: a caller gets a
  connection, by either protocol, only if it is one of three things. This app. A caller
  Android trusts with media, which is Media3's "trusted": the system itself, an app holding
  the system's permission to control media, or an app the person has given notification
  access (a car's own screens, Bluetooth, the system's media controls, a watch, and on most
  phones Android Auto and the Assistant). Or one of the **known hosts** of media apps, by
  package name and signing certificate both: Android Auto, the Google app and its
  Assistant, Wear OS, the Android Auto simulator and the Assistant on Android Automotive,
  from the list Google publishes for this in its Universal Android Music Player sample
  (`res/xml/allowed_media_browser_callers.xml`, which says where and when it was fetched).
  The third is there so that the real Android Auto is never turned away on a phone where it
  happens to hold neither of the second. The certificate is asked of the system
  (`hasSigningCertificate`) for the package the system says the calling uid is; a name
  alone is never enough, and a release build has no way to be told of any other caller (a
  debug build can be told the rig's probe, at run time). Everyone admitted browses,
  searches and plays; anyone else gets no connection. The cover provider gives covers to
  exactly the same callers. The
  app's own commands are granted only to a Media3 controller with this app's uid that is
  not the notification controller, and are checked again as each is carried out. What
  arrives under the notification controller's name (which Media3 also uses for intents sent
  to the exported service, by anyone) may only do what a notification does: transport,
  like, shuffle, repeat. `VoiceActivity`, which must be open to the Assistant, accepts the
  play-from-search action and reads only its words and fields, as text of bounded length.
- **Each sign-in is a generation** (`Era` in the service). What the session is asked runs
  in that generation's scope, and what was found for an account that has gone is never
  handed on: the question is answered "not signed in" if nobody is signed in by then, and
  asked again of whoever is (`ForAccount`; "not signed in" to a car is a standing error,
  which must not land after a new sign-in has cleared it). Search results live in the
  generation, and so does everything learnt about its server's songs by id (what a
  conversion was found to be, which songs need converting): two servers can use the same
  ids. A data source belongs to the generation it was made in and is refused under
  another; a cover fetched for the car is handed over only if its account is still the one
  signed in; the kept-songs cache is released only when its last reader has closed.
- **Each list remembers its shuffle**, as on Windows: a list with no choice made yet plays
  in order; shuffle and repeat are also kept in Settings.
- **Quality**: the original file if the phone plays it and no limit is set, else MP3 at the
  limit (320 kbps for a file the phone can't play), as Windows' `stream_form`, with one
  difference: an MP3 already at or under the limit is asked for as the file it is. Navidrome
  sends the file for it whatever is asked (it converts nothing, and takes no notice of a
  time to start from), so asking for a conversion only made seeking wrong. A file that
  turns out not to decode is converted from then on.
- **Songs the server converts** (`SongReader`). Navidrome streams a fresh conversion with
  no length and ignores byte ranges (once it has a whole conversion in its own cache it
  honours them, but there is no knowing that without asking). It converts at the lower of
  the rate asked for and the file's own, and puts a tag before the audio. So: from its
  start a conversion is read to the server's end, with no length made up or enforced;
  anywhere else (a seek, carrying on after a drop) it is asked for from the whole second
  before with `timeOffset`, and the bytes up to the place are thrown away, never by a byte
  range. The time for a byte is worked out from where the audio starts and its real bit
  rate, both read from the stream as its start goes by: the same two numbers Media3's
  constant bit rate seeker uses to choose the byte. The player doesn't know such a song's
  length, so the timeline the session sees gives it the library's. A stream that comes
  with a length and the server's word that it takes byte ranges is not a fresh conversion
  (it is the file itself, or a conversion the server holds whole): a place in it is asked
  for as a byte. **A drop part-way** is put right by the reader, not the player: Media3
  takes a stream of unknown length for a broadcast and loads a failed one again from its
  start, so the reader asks for the song again from the time it had reached (after 1, 2, 4
  and 8 seconds) and carries on, and only after the last of those is the failure passed to
  the player, as final. A place at or past the end is the end of the song, not an error.
- **Kept songs.** A file as it is goes through the cache like anything else. A conversion
  is kept only if read from its first byte to a clean end and at least as long as Windows
  requires (`least_bytes`), and that is judged once the read has closed: Media3's cache
  writes the last of a song as its writer closes, and takes a conversion's length from
  wherever the stream stopped, so it cannot be asked whether a conversion is whole. A copy
  that passes is given a mark of our own in the cache's record of it (`WholeMark`, with a
  version); a conversion without the mark is never played from the kept songs and is
  cleared away. One read at a time keeps a conversion; another meanwhile gets the server's
  bytes alone. Part of one is never kept and never pieced together with the server's bytes.
  The limit in Settings applies the moment it is changed, down to nothing, and when an
  account is next taken on if it was changed while signed out. In offline mode the player
  asks the server for nothing, from the moment the switch is turned, streams already open
  included: a song kept whole plays, any other is passed over. Fetching ahead is M2.
- **Nothing private leaves the data source**: it reports the song's own address
  (`nexus://…`), the cache is never told where a redirect led, and a failure is thrown as
  its kind alone, with no cause. A playback error reaches every app with a controller.
- **A dropped connection**: a read that gets nothing for 10 seconds is given up on; the
  stream is asked for again after 1, 2, 4 and 8 seconds; then the song stays where it is,
  the car and notification say "Reconnecting…" (the phone, "Lost the connection to the server…"), and it is tried every
  15 seconds (a try that belongs to that account and that song, dropped if either has
  changed) and carries on when the server is back. After ten minutes of that it stops
  trying and pauses, saying the server can't be reached: ten minutes is how long Media3
  keeps the service in the foreground with nothing playing, and past it the service has no
  standing with Android to bring the music back; the next Play tries again from where the
  song stopped. Passing over a song that can't be played
  leaves "playing or paused" as it was. Swiped away, playback carries on unless the person
  had paused it. The Windows check of whether the server is there at all (to pass over a
  song that is the problem) is not ported yet.
- **The saved queue** has one writer for the whole app (`QueueSaver`), which does reads and
  writes in the order asked and writes only the newest of what is waiting. Nothing is saved
  until the queue that was read is in the player (`KeptQueue.settle`, which belongs to one
  life of the service and one account's turn at it, not to the account): the player is
  empty while a long queue is read and made ready, and that emptiness must not be saved.
  While the queue only plays, only the place is written, and only beside the queue it was
  handed over with: each hand-over of the queue has a name, the writer says whether it was
  written, and one that failed is handed over whole again. A play order that isn't every
  song once is damaged, and not put back.
- **A Play button with the app not running** starts the service only if there is a saved
  sign-in and a saved queue (`PlayButtonReceiver`). The service enters the foreground at
  once, before anything is read, with a notification that is Media3's in number and channel
  (Media3's replaces it in place when playback begins; from Android 12 it is never seen).
  Resuming reads only the phone, waits a few seconds at most, and never looks for something
  else to play; if there turns out to be nothing, or nothing has begun seven seconds after
  the first button, the service leaves the foreground and stops. The receiver's first name
  (Media3's own class) is still declared, closed to other apps, for a system that remembers
  it from an earlier build.
- **In the foreground before the player asks for the sound.** Media3 enters the foreground
  a moment after playback has begun, which is after the player has asked Android for audio
  focus. So the player tells the service just before any play, whoever asked
  (`QueuePlayer.aboutToPlay`), and the service enters the foreground then if it isn't in
  it (whether it is, is asked of Android: Media3's `isPlaybackOngoing` says whether the
  service has ever been in the foreground, not whether it is now). A start Android refuses
  is handled through Media3's own hook (`MediaSessionService.Listener`): noted, the player
  paused, never an uncaught exception.
  See "Starting playback: what Android allows" below.
- **Gapless MP3.** Media3 1.11.1 allows for an MP3 decoder's 529-sample delay in the trim
  it works out from a header written by LAME or ffmpeg; Android's own decoder allows for it
  too. With both, every song lost its first 12 ms and gained 12 ms of silence. For that
  decoder, by name, the file's own numbers are used (`Mp3Trim`), and they are read from
  the file's own header beside Media3's extractor (`Mp3HeaderReader`), not worked back from
  Media3's result: the correction is made only for a header from one of the encoders Media3
  adjusts for, whose numbers are the ones Media3 started from. Another encoder, no header,
  numbers from an iTunes comment, another decoder, or anything that doesn't add up, is left
  as Media3 has it: never trimming music comes before closing a gap. FLAC, AAC and Opus join
  exactly. Ogg Vorbis
  runs a few milliseconds past its stated end (Media3's Ogg reader doesn't trim the last
  packet); not fixed. A conversion has no gapless information at all.
- **Plays** are counted against a clock as well as the position, with a last sample at
  every pause, seek and change of song, and a fresh count for a repeat or a queue started
  again from the top (`PlayReporter`).
- **Diagnostics** (`Diagnostics`; Settings, About). A bounded record, in memory and in one
  small file, of what only the phone can say after a drive: each connection to the player
  (which app, by which protocol, whether Android trusts it, whether it is a known host, let
  in or refused), who read covers, what started the player and whether and how soon
  Android let it into the foreground, audio focus refused straight after Play, which
  decoder plays MP3 and what is trimmed for it, playback errors by kind, and the times the
  server was given up on. The same thing again is counted, not written again. No server
  address, account or song name goes into it; it stays on the phone unless the person
  shares it (as text, through Android's share sheet).
- Debug builds log the player's events (tag `NexusPlayer`) and have `DebugControl`, a
  receiver the rig can drive the player with from adb.

### Starting playback: what Android allows

Playing in the background needs a foreground service of the media-playback kind, and each
version of Android since 12 has added a rule about when one may start or be heard. What the
rules are, by Google's documentation (read 24 November 2026) and Android's source:

| Android | The rule |
|---|---|
| 9 to 11 (API 28–30) | A service started with `startForegroundService` must enter the foreground within a few seconds or the app is killed. No limit on starting one from the background. |
| 12, 13 (31–33) | An app in the background may not start a foreground service, with exceptions. The ones that matter here: the person acted on the app's notification; and, by the source rather than the documentation, a few seconds after the system has delivered the app a media button, or a media-session command from an app that could itself start one. |
| 14 (34) | The service's kind must be declared, with its permission (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`). Declared. |
| 15, 16 (35–36) | An app targeting 15 is given audio focus only while it is the app in front or is running a foreground service. So the service must be in the foreground before the player asks. |
| 17 (37) | "Background audio hardening": an app in the background is heard, and given focus, only while it runs a foreground service; for an app targeting 17 (this one) that service must also have been started while the app was visible or by something the person did: Google names a tap on a notification, a widget, and a media key from an outside device. Otherwise playback is silent, with no error. |

And each way playback can start, against them:

| Playback is started by | 9–11 | 12–14 | 15–16 | 17 | Checked here |
|---|---|---|---|---|---|
| The app's own screens | allowed | allowed (the app is in front) | allowed | allowed | emulator, 17 |
| The notification or lock screen | allowed | allowed (acting on a notification) | allowed; the service enters the foreground first | allowed (a notification tap is named) | not run: needs the ten-minute wait and a tap on the system's screen |
| A headset or car button, the service running | allowed | allowed for a few seconds after the button | allowed; the service enters the foreground first | allowed (a media key is named) | emulator, 17: the car's Play after eleven minutes paused (below); a button then finds the service gone and starts it as in the next row |
| The same, the app not running | **Android 9: the button never arrives** (below). 10, 11: allowed, by the source | allowed; in the foreground 0.1 s after the service is made | allowed | allowed (a media key is named) | emulator, 9 and 17, six cases each way of ending the app |
| The car (Android Auto) choosing a song or pressing Play | allowed | allowed for a few seconds after the command, if Android Auto could itself start one | the same; the service enters the foreground first | **not known**: Google's page doesn't say whether a command from Android Auto counts as something the person did | the rig's probe, 9, 15 (car) and 17, and on 17 after eleven minutes paused with the probe still connected (`--mode long_pause`), with the hardening forced on; the probe is not Android Auto |
| The system's resume control | allowed | allowed (the system asks) | allowed; foreground first | **not known**, as the car | not run here |
| The Assistant, on the phone | allowed | allowed (it opens the app: `VoiceActivity`) | allowed | allowed (the app is visible) | the activity, by adb, in the first round; never the Assistant |
| The Assistant, in the car | allowed | as the car | as the car | **not known**, as the car | the request, by the probe; not the Assistant |

"Not known" is what the first drive is for; the Diagnostics page records which of these
happened and what Android said. If Android 17 does refuse a Play that comes from the car,
there is nothing more the app can do from inside the service: it is already in the
foreground before it asks for the sound, which is all Android lets it arrange. What to do
then would be decided from what the Diagnostics page says happened.

**Android 9 and a button with the app not running.** Android 9 sends a button, when no
session is live, as a broadcast to the component the app's session last named; Media3
1.11.1 names the playback service on Android 11 and below, and a service is not a broadcast
receiver, so the button goes nowhere. Seen on the Android 9 emulator; not fixable from the
app without replacing Media3's session. Android 10 and 11 start a named service properly by
their source, and were not run here. The car's own controls (the probe's) work on Android 9;
whether a headset button reaches a session that is live there was not checked.

### Android Auto

Google draws the car's screen. We supply lists, artwork, what's playing and a few buttons.

- **Tabs** (four at most): Home, Playlists, Library, Downloads. Until there are downloads
  (M2) there are three.
  - Home: Liked songs, then what was played and added lately.
  - Library: Albums, Artists, Genres.
  - Downloads: what is on the phone; this tab must work with no signal.
- Albums and playlists show as grids with artwork; songs as lists. Choosing a song plays
  its whole album or playlist from that song.
- **Artwork** reaches the car through a small read-only content provider backed by the
  cover store; the car cannot fetch from the server itself.
- **Voice**: "play <artist / album / song / playlist> on Nexus Music" finds the best match
  the way search does and plays it. With nothing named, it resumes, or shuffles Liked songs.
- **Buttons** on the car's player: like, shuffle, repeat. The queue is shown.
- **When something is wrong the car says so in words**: not signed in ("When it's safe,
  open Nexus Music on your phone to sign in": Google's VI-1 asks that anything sending the
  driver to the phone says to look only when it's safe), server out of reach with nothing
  downloaded, and so on.
- Follow Google's quality guidelines for car media apps; they are the test.

How it is built (M1a):

- **Ids** are paths (`album/<id>`, `track/<index>/<song>/playlist/<id>`), each part escaped;
  an album shown under two headings on Home has a prefix to keep its ids apart. A song
  carries its place in its list, as a playlist can hold one song twice.
- **Home**: Liked songs, then "Recently played" and "Recently added" (12 albums each) as
  grid groups. **Library → Artists** lists everything; past 120 they are split into
  folders by first letter (A to Z, "#" for the rest, a leading "The" ignored).
  **Library → Albums** shows "Recently added", "Recently played" and "Most played" first
  (one request each), then the whole library A to Z from the copy of the full listing kept
  on the phone. That listing is many requests on a large library, so it is fetched in the
  background (at most every ten minutes) and never while the driver waits; with no copy
  yet, A to Z joins the three folders when it lands.
- **No folder is longer than reaches the car whole** (`CarFolders`). A car asks for a
  folder in one go, and Media3 cuts the answer to what fits in 256 KiB, silently. So a
  folder holds at most 200 items and at most 190,000 bytes as reckoned here (which errs
  large), counting what heads it ("Shuffle") and counting the folders that only list
  ranges too. A longer list is shown as numbered ranges ("1–200", with the first and last
  titles), ranges of ranges if need be; a song in a range still plays the whole list from
  its place. A host that does page is given that page of the same folder. A range is named
  by places in its list, so when the list changes, the list's folder and every range of it
  the car has asked for lately are told to load again.
- **Worked out off the main thread.** A folder, a search and the list a chosen song plays
  are laid out, sorted and weighed on another thread (`OffMain`), and given up on after
  seven seconds whatever they are doing; a time limit around the work itself only takes
  effect where the work pauses, and this work doesn't.
- **"Shuffle"** heads every album, playlist and Liked songs: it plays the whole list
  shuffled, and the list remembers (its own choice, whatever shuffle was set to before).
- **Everything comes from kept pages** first and is checked with the server afterwards; a
  changed page is announced to the car. With the server out of reach the whole tree still
  opens. Anything the car waits on is given up after seven seconds. A folder never fetched,
  or a search, with the server out of reach, is one entry, "Can't reach the server", "Tap to
  try again": a folder (each with an id of its own) that, opened, is the folder fetched
  again; the car is told the folder has changed. The lost connection is "Reconnecting…" in
  the session (a car's player has one line for it); the phone says it in full.
- **Not signed in** is a standing error with a "Sign in" button that opens the app,
  published as soon as the saved sign-in has been read, whether or not anything has
  browsed, and again when Media3's own notification controller connects: Media3 copies a
  standing error to the platform's session, which is what a car reads, only through that
  controller and only if it is connected when the error is set, and with nobody signed in
  the saved sign-in is read before it has connected. Browse errors of that kind stay on the
  car's screen
  (`LIBRARY_ERROR_REPLICATION_MODE_FATAL`). A search made while signed out is answered
  ("nothing") at once. When who is signed in changes, the car is told to load again the
  folders it has asked for lately. For that to reach it, a subscription to a folder stands
  whoever is signed in (`onSubscribe`): Media3's own answer drops a subscription whose
  folder can't be fetched, which signed out is every one, and a car that isn't subscribed
  is told nothing. Signing in also clears the error Media3 copied from the failed folder
  (`clearReplicatedLibraryError`), which otherwise stands until a folder next opens.
- **Voice**: a request with a focus looks only at that kind, falling back to any kind if
  nothing matches; one without is read for "songs by", "the album", "… playlist", "X by Y".
  The best match wins (said exactly, then begins with, then contains, then words shared;
  accents, case and a leading "The" don't count); a tie goes to an artist, then album,
  playlist, song, genre. An artist plays their albums' songs shuffled; a genre 100 random
  songs of it; a song plays its album from that song. Nothing named: the queue as it is,
  else the saved queue, else Liked songs shuffled (that last only for a spoken request: a
  Play button resumes or does nothing). No match: "Couldn't find “X” in your
  library". The phone's own Assistant reaches the same code through `VoiceActivity`.
- **Buttons**: like, shuffle and repeat as Media3 command buttons (the notification shows the
  first two); shuffle and repeat are also the standard actions.

## Data kept on the phone

- **Account**: server address and username in settings; the password encrypted with a key
  held in the Android Keystore. Each account has its own downloads, cache and held changes.
- **Sign-in and transport**: a sign-in token never goes over plain HTTP beyond the home
  network unless the person typed `http://` themselves, and is warned first. An address typed
  without a scheme is tried over HTTPS, and over plain HTTP only on the home network (this
  differs from Windows, which falls back for any address); a redirect from HTTPS to HTTP is
  never followed.
- **Settings**: one JSON file, written atomically with a backup copy, never silently reset
  (as on Windows). Exposed as a `StateFlow`.
- **Pages**: JSON on disk, keyed by what was asked for (the Windows route keys where the page
  is the same, hashed for the file name), under the account's cache folder; the ones used
  lately also held in memory, by weight (about a song's worth each, 12,000 in all), not by
  number. A page fetched within 15 seconds isn't fetched again. Each page has one order of
  events: a refresh or a read from disk begun before a change made here (an unlike) is
  dropped when it finishes, writes of one page take turns, and every atomic write in the app
  goes through a temporary file of its own.
- **Answers from the server** are decoded from the stream straight into the models, never
  as a JSON tree first (a 12 MB playlist as a tree cost 80 to 100 MB), keeping what Windows
  makes of an answer: an absent section is empty, one sent as null is malformed.
- **Library copy**: every artist, album and song, kept compactly for instant search; the
  search and its ranking are a port of `src/search.rs` so both apps find the same things.
- **Covers**: one store. Each cover is fetched once at 640 px (Windows' largest) and cut
  down locally, 300 MB at most per account; Now Playing alone asks for a second, 1200 px
  copy, as its cover is nearly the phone's width, and shows the 640 px one until it
  arrives. Coil and the car's content provider
  (`content://org.medianexus.music.covers/<cover id>`) both read from it: Coil through a
  fetcher of our own (`CoverImages.kt`), with its own disk cache off, so no picture is kept
  twice; it decodes at the size shown (and keys what it holds by that width, so a large
  picture isn't kept for a small place) and holds recent ones in memory: 24 MB at most, or
  15% of the app's allowance if that is less (see TESTING.md for how that was chosen). A few
  covers are read and decoded at a time, and a fetch nobody is waiting for any more is
  dropped before its turn. Now Playing's small copy leaves once the large one has drawn. A
  cover that failed is asked for again when the connection comes back. Covers of downloaded
  music are kept where the system won't clear them.
- **Saved queue**: `queue.json` and `queue-place.json` in the account's data folder, in
  the Windows app's form (`SavedQueue`, `QueuePlace` and its `queue_shape`), ready for
  "Continue here" in M5. It stays saved through signing out.
- **Downloads**: Media3's download manager, in a cache of their own that is never evicted,
  at a quality chosen in Settings, with Wi-Fi-only as an option. Alongside, a list of what
  was downloaded with everything needed to browse it offline (names, covers, order).
- **Offline**: noticed automatically, and switchable by hand. Downloads keep playing;
  likes, ratings and plays are held until the server has accepted them (port
  `src/offline.rs`).

## Working with the Windows app

The phone is one more device on the same account. These must match the Windows app byte
for byte, so port them from its code and tests, do not reinvent them:

- the saved queue ("Continue here") and the "Nexus Music sync" playlist (`src/sync.rs`);
- the play log behind "Your stats" (`src/plays.rs`);
- jams and linked devices through the relay (`src/jam.rs`, `relay/`).

## Look and feel

The Windows interface in `../nexus-music/ui/` is the reference. Read `theme.slint`,
`widgets.slint` and the page you are rebuilding before you build it.

**Carried over exactly**

- The six colour themes from `THEMES` in `src/config.rs` (Violet is the default), with the
  same roles: `base`, `panel`, `raised`, `overlay`, `text`, `muted`, `faint`, `accent`,
  `accentBright`, `onAccent`, `warm` (marks what you've liked), `danger`; `hover`/`press`/
  `line` as the same translucent whites.
- Dark only. The app draws edge to edge; system bars take the theme's colours.
- Type: Bricolage Grotesque for display (titles, headings), Hanken Grotesk for everything
  else, both bundled. Same weights as Windows.
- Icons: the same Lucide set and the app's own filled transport icons, as vector drawables.
- Corners 10dp (covers on rows 4dp; artists round), gaps in steps of 8dp.
- The app mark: the violet tile with three white bars.

**Re-thought for a thumb**

| Windows | Phone |
|---|---|
| Sidebar | Bottom bar: Home, Search, Library. Playlists live in Library. |
| Top bar with search | A page title bar with Back; Search is its own tab. |
| Player bar | A mini player docked above the bottom bar: cover, title, artist, like, play/pause, a thin progress line. Tap or swipe up for Now Playing. |
| Now Playing page | Full screen: large cover with a soft shadow, title in the display face, artist, format line, seek bar, shuffle / previous / play / next / repeat, then devices, lyrics, queue and like. Swipe down to close. |
| Queue panel | A sheet over Now Playing, with "Next in queue" and drag handles. |
| Right-click menu | A bottom sheet, opened by the "…" on a row or a long press. |
| Hover | Nothing depends on it. |
| Ctrl/Shift selection | Long press starts selecting; a bar offers the actions. |
| Column headings to sort | A sort button and chips, as the Windows chip row. |

Sizes: a song row is 56dp high with a 40dp cover, title 15sp semibold (accent colour when
it is the one playing), artist 13sp muted. Touch targets are at least 48dp. The play
button is a filled circle with the `onAccent` icon. Where this file says "accent" for
something Windows draws in `accentBright` (the playing song's title, the play button),
Windows is right: match it.

Each tab of the bottom bar keeps its own history. Back from a tab's first page goes to
Home; from Home's first page it leaves the app. Tapping the tab you are on returns to its
first page.

Every screen must hold up on a 360dp-wide phone and at 1.3 font scale, and be usable with
TalkBack: every control has a name.

How it is built (M1b):

- **The shell**: the page, notice bars, the mini player and the bottom bar; over them Now
  Playing, the queue sheet, a song's menu (both Material bottom sheets in our colours) and
  toasts. Screens reach the app's objects through `Ui` (`LocalUi`). While the keyboard is
  up the mini player and bottom bar step aside.
- **Pages show what is kept at once** (`rememberPageFeed`, over `Library.live`): the kept
  copy, then the server's, then any change made here while the page is open (a like). Pulling
  down asks the server again; a failure with something on screen is a strip with "Try
  again" above it, never in its place; skeleton rows only when nothing is kept. A page
  opened from a card draws its header at once from what the card said.
- **The header** (Windows' `Hero`) stands the cover above the words on a phone held
  upright: a cover beside the title would leave the title too little room. On its side, or
  600 dp and wider, the cover sits beside the title as on Windows, sized by the height. Then the kind ("Album"), the
  name in the display face, and the artist or owner beside the facts ("2017, 14 songs,
  7 min"), wrapping under it when narrow. The action bar is Windows': play, the list's own
  shuffle (a dot under it when on), a heart for an album. A list with nothing in it offers
  neither play nor shuffle.
- **The playing row**: the service puts the list the queue came from and the place in it of
  the song playing in the session's extras; a row is marked only if it is that place in
  that list and that song, so the same song elsewhere, or in another list, isn't, and a
  refreshed list whose places have moved doesn't mark the neighbour.
- **What the player said** goes when the connection to it is lost (song, queue, buffering,
  error). After Android has stopped the app, "nothing playing" isn't believed until the
  service has put back the saved queue and says which song it is settled on
  (`PlayerConnection.reported`): Now Playing stays open meanwhile.
- **Likes have one owner** (`Likes`): what the server last said, from every page that says
  whether something is liked, and what has been done here and is still being sent. Every
  heart, album hearts too, and Liked songs itself are read from it. Changes to one item go to
  the server one at a time in the order made.
- **The queue follows its list**: whenever the kept copy of the list the queue came from
  changes (a fresh copy from the server, a song unliked from Liked songs), the service runs
  `followList` and keeps each entry's number, so the song playing carries on untouched.
- **Mini player**: a rounded card in `overlay` above the bottom bar, with a 2dp line in the
  text colour along its foot. Tap or swipe up opens Now Playing; a sideways swipe skips.
  Only the line reads the position, and only while drawing.
- **Now Playing**: the play button there is Windows' transport button (a circle in the text
  colour), not the accent one pages use; buffering and "Lost the connection…" replace the
  format line in words. On its side, the cover sits beside the controls. Swipe down or
  back (predictive) to close.
- **The queue sheet** is the Windows queue panel (its rows, its menu and every command name
  a song by its queue entry's own number, as the person saw it): "Now playing", then "Next in queue" with
  "Clear queue", then "Next from <list>" with "Clear". Songs move by a drag handle (Lucide's
  grip-vertical, which Windows doesn't need), across the heading into "Next in queue" or out
  of it, by the same arithmetic as Windows (`QueueRows.move`). The sheet keeps one list, the
  rows as shown (`QueueDrag`): every edit (a drag, a swipe, Move up or down, the menu's
  Remove) is worked out against it, shows at once, and names the entry moved and the one it
  is to follow. The player's reports decide what stays: one that is on the way to what is
  shown (the first of two quick drags done, the second not yet) changes nothing; any other
  replaces it, so an edit refused, or a swipe the player didn't take, is put right by the
  player's next word. No timer puts anything back. A cancelled drag sends nothing. A
  sideways swipe or "Remove from queue" takes one out; screen readers get Move up and Move down where they do something,
  and Remove. Below 400dp the songs' lengths are left out so their names have room. The
  sheet comes up to the foot of Now Playing's top bar, no further.
- **A song's menu** has what works so far: Play next, Add to queue, like, Go to album, Go to
  artist (each credited artist when there are several), and Remove from queue from the
  queue. Nothing that does nothing yet is shown.
- **Home**: the greeting, four quick tiles (two lines of two, as Windows on a narrow window),
  then Recently added, Recently played, Most played, It's been a while and Something
  different, each sideways. "Made for you" and "Artists you might like" come later.
- **Library**: chips for Playlists, Albums, Artists and Genres. Liked songs heads the
  playlists, then the pinned ones, then the rest by name. Albums have Windows' seven sorts
  as a second row of chips, sixty at a time as the grid scrolls; Home's "Show all" opens them.
- **Artist**: albums, then singles and EPs, newest first; play plays the songs of their
  first forty releases, newest first as Windows builds it, saying it is getting them and
  how many albums couldn't be fetched, if any. "Popular" and "Fans also like" need the server to ask Last.fm, which
  the test server can't; they come with the rest of browsing (M3).
- **Search**: results as the words are typed (250 ms after the last key; a newer search
  always replaces an older one), in Windows' order: the first four songs, then artists,
  albums and playlists, with the artists first when one matches the words better than any
  song does, judged by a port of Windows' scoring (`SearchRank`), and the playlists whose
  name has every typed word as the start of a word; chips narrow to one kind. Results, and
  what tapping one plays, belong to the words that found them. With nothing typed, "Browse by genre". Windows keeps
  no recent searches, so neither does the phone. A song chosen from the results plays the
  results from it (Windows starts radio from it, which comes with M3).
- **Toasts** are Windows': a pill in the text colour at the foot, three seconds, for things
  done ("Added to queue") and things that happened out of sight ("That didn't save. …" for
  a like the server refused).
- **Notifications** are not asked for: Android exempts media-session notifications from
  that permission, so the player's controls appear with it refused (checked on Android 17).
- **Android 17 and home servers**: the app targets Android 17, where reaching an address on
  the local network needs `ACCESS_LOCAL_NETWORK` (a runtime permission in the Nearby devices
  group; the system's question is about "nearby devices"). Google defines a local *network*
  (Wi-Fi or Ethernet, not VPN or mobile), so `LocalNetwork` answers in three: certainly local
  (10/8, 172.16/12, 192.168/16, link-local, multicast and broadcast, `.local` names),
  maybe (100.64/10, which Tailscale and carriers use too; fc00::/7; any name), or not (the
  phone itself, public addresses). Signing in to a certainly local address first says why
  the question is coming, then asks, and the sign-in goes ahead with the answer; a maybe is
  connected to first, and only a failure without the permission says it may be why, with
  "Allow". Where the question stands (asked, granted, refused, can't ask again) is kept by
  `LocalAccess`, and the sign-in waiting on it (address, username, password) by the session
  (`Session.hold`), in memory only and only while the question is open: not the screen, so
  the screen being rebuilt while the system asks (turning the phone, a change of font size)
  loses nothing. It is let go when used, or when the question is abandoned (the app's screen
  closed for good); after the app has been killed there is nothing to resume, and the screen
  simply asks again. One question is open at a time; "can't ask again" is what the platform reports after a
  refusal, and then "Allow" opens the app's page in the phone's settings. Signed in and not
  allowed, a bar over every page and on Now Playing says so. When it is allowed, when the
  server answers again after failing, or when the phone has a network again, pages that
  failed load again and covers are asked for again (`Graph.reconnects`); while the screens
  are in front and the server was out of reach, it is asked every 15 seconds. The car,
  started cold with no permission, browses what is kept and is told in words, with a button
  that opens the app.
- **Passwords**: the password field gives accessibility services dots, as a system password
  field does, not the password (Compose's own secure field gave them the text).

**Smoothness**: lists are lazy with stable keys and content types; nothing is computed in
composition that could be remembered; images are asked for at the size shown. Judge speed
on release builds only.

## Milestones

Each one ends with the app building, its tests passing, a run on the emulator, and a review.

- **M0 Foundation.** Theme, type, icons; the API client with tests; account and settings
  stores; sign-in; the app's shell with the bottom bar. Also the test rig: a local Navidrome
  with a made-up library, a headless emulator, and a way to check Android Auto.
- **M1 Playing, and the car.** This is the proof build that goes on a real phone and into
  a car. In two steps:
  - **M1a**: the page cache and library, the cover store, the playback service and queue,
    and the whole Android Auto surface. No new screens.
  - **M1b**: the mini player, Now Playing and the queue sheet; Home, Library, album,
    artist and playlist pages; search.
- **M2 Offline.** Downloads, offline mode, held changes, kept songs and fetching ahead;
  Downloads in the car.
- **M3 The rest of browsing.** Songs page, genres, sort and filter, playlist editing that is
  safe from two devices (port `src/playlists.rs`) with undo, the local search, Recently
  played, ratings, radio stations, song radio, autoplay, recommendations, menus,
  multi-select.
- **M4 Sound.** Quality switch, levelling, equaliser, mono, crossfade, sleep timer, lyrics.
- **M5 In step with your other devices.** Saved queue and "Continue here", the sync
  playlist, "Your stats", "Made for you" and mixes of your own.
- **M6 Jams and linked devices.**
- **M7 Requests.** Lidarr, MusicBrainz, ListenBrainz, browser sign-in through Authentik,
  "Artists you might like", bringing playlists over from Spotify.
- **M8 Shipping.** The signing key, updates from GitHub releases, checks on every push,
  start-up profiles, diagnostics, the README.

Not coming to the phone: exclusive mode and choice of output device, the mini-player
window, tray and taskbar, keyboard shortcuts.
