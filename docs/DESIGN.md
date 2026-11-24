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
| Background work | Coroutines; for downloads, Media3's `DownloadManager` with a downloader of our own, kept up by a job of our own (see "Downloads: what Android allows") |
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
| `downloads` | Offline downloads: the catalogue of what is downloaded (the mark that a download is whole), the whole-file fetcher, the engine on Media3's download manager, the job and service that keep it going, and the downloads laid out for browsing with no signal. |
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
- **What a server says is the person's, and stays on the screen.** The person may be shown
  what their own server said when it refused something ("The server reported: …", kept short
  and stripped of anything that names the address, account or sign-in: `safeServerMessage`).
  That text, like server addresses and account names, never goes into the log, the
  Diagnostics page or its shared report, crash text, or anything another app can read (a
  car's screen, the media session's errors, a notification); those get the kind of failure
  in our own words ("Can't reach your music server", "playback error: ERROR_CODE_IO_…").
  Song names go to the car and the notification as what is playing, and nowhere else of
  those. So a car is told one fixed sentence per kind of failure (`CarTrouble`: out of reach,
  the home network not allowed, the sign-in turned away, not allowed, not found, refused, not
  understood), chosen from the kind of error and the server's code and never from its
  message. What the server itself said is kept inside the app, in memory, for the phone's own
  screens to show (`Graph.refusals`); it goes when the account does.

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
  places move (the window slides, the queue is reordered, something is added or removed,
  another queue takes this one's place) every place the car is still showing means another
  song until its screen has caught up, and nothing in a tap says which queue its sender was
  looking at. So nothing is guessed (`QueuePlayer.placeIsSure`): for a second after the
  places move, a place sent by anyone but the app's own screens is acted on only if it
  means the same entry in every arrangement of that second and now; otherwise the tap is
  ignored and the queue is sent again, so the car redraws and the next tap is on what is
  there. (The second round read such a tap as the queue was before the change, which is
  right for a row the car had not redrawn and plays the wrong song for one it had.) What
  it costs is a tap that does nothing, in the second after a change, on a row that moved.
  **What remains:** it is still a rule about time. A car that takes longer than a second to
  redraw its queue, and is tapped after that second on an entry it has not yet redrawn,
  plays the song now at that place. It can only happen at the moments the places move:
  every hundredth song, and when the queue is edited from the phone or replaced.
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
  them all; "carry on" (a Play button) is not a start and gives way to any, and its place
  in the song is used only if the song at the cursor is still the one it was taken for. A
  page started by name names the song tapped and the songs around it: if the phone's copy
  has changed by the time it is read, the list starts at that song wherever it is now, else
  at its nearest neighbour still there (nearest by distance, after and before by turns),
  else at the page's first song, never at the old place; an empty list starts nothing and
  says so.
- **A request carries one ticket from its arrival to Play** (`PlayRequests`). Media3
  follows a hand-over with "make ready" and "play" whatever became of it: a car's in the
  same breath as its items reach the player (`MediaSessionLegacyStub.handleMediaRequest`),
  and a Media3 controller's as two more commands that arrive even when the first failed
  (the phone's voice request sends all three at once). So when a hand-over turns out stale,
  or a Media3 controller's request fails, the sender is noted, and until Media3 says that
  sender's run of commands is over (`onPlayerInteractionFinished`) its "make ready" and
  "play" do nothing: a request that has been overtaken can't start the newer choice, which
  the person may have paused.
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
  arrives as a start intent to the exported service, which any app can send and Media3
  carries out under its notification controller's name, can do what a headset's buttons
  do (play, pause, stop, next, previous, moving about in the song) and nothing else. The notification's own like, shuffle and repeat buttons carry
  a mark only this app knows (`OwnMark`: made when the service starts, held in memory,
  inside a pending intent no other app can read); Media3 hands every button intent to the
  notification provider before doing anything else with it, and `OwnButtons` carries out
  one that has the mark, drops one that hasn't, and passes none on, so a start intent never
  becomes anyone's custom command. (Like, shuffle and repeat as custom commands under that
  controller's name stay allowed: that is how Media3 reports the system's own media
  controls.) On Android 9 to 12 the platform's transport hands the app the package name its
  caller wrote, unchecked, and Media3's "trusted" can rest on that name alone: there it is
  taken only from a caller the system says really is that package (`Callers.namedTruly`).
  What was worked out about a known host is forgotten when the system says that app was
  installed, updated or removed, and a "no" that came from the lookup failing is not kept.
  `VoiceActivity`, which must be open to the Assistant, accepts the play-from-search action
  and reads only its words and fields, as text of bounded length.
- **Each sign-in is a generation** (`Era` in the service). What the session is asked runs
  in that generation's scope, and what was found for an account that has gone is never
  handed on: the question is answered "not signed in" if nobody is signed in by then, and
  asked again of whoever is (`ForAccount`; "not signed in" to a car is a standing error,
  which must not land after a new sign-in has cleared it). Search results live in the
  generation, and so does everything learnt about its server's songs by id (what a
  conversion was found to be, which songs need converting): two servers can use the same
  ids. A data source belongs to the generation it was made in and is refused under
  another; a cover's address names the account it is for (its artwork id), is "no such cover"
  under any other, and a cover is handed over, from the phone's copy or fetched, only if its
  account is still the one signed in; the kept-songs cache is released only when its last
  reader has closed. A question overtaken by a change of account every time it is asked is
  given up with no answer at all: neither "not signed in" nor a success, both of which
  Media3 copies to the car's session as how things stand.
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
  for as a byte. That is remembered, and the answer decides each time: the server's copy of
  a conversion is a cache, and when it has gone a range is answered with a fresh conversion
  from its start. So a byte further in is asked for with a Range header of the app's own
  and "position 0" as far as Media3's HTTP data source is told (told the place, it makes up
  for an ignored range by reading the answer up to the place and throwing it away, inside
  `open`); an answer whose `Content-Range` does not begin at the byte is let go unread, and
  the place is asked for by time. **A drop part-way** is put right by the reader, not the
  player: Media3 takes a stream of unknown length for a broadcast and loads a failed one
  again from its start, so the reader asks for the song again from the time it had reached
  and carries on. How long it waits first, and when it stops, goes by how often the stream
  has failed in the last three minutes (`Stalls`): 1, 2, 4 and 8 seconds, and the fifth
  failure in that time is passed to the player as final, whatever arrived between them (a
  link that delivers a little and stalls used to be tried for as long as the song had bytes
  left). Offline mode switched on while it waits stops the next try. A place at or past the
  end is the end of the song, not an error.
- **Kept songs.** A file as it is goes through the cache like anything else. A conversion
  is kept only if read from its first byte to a clean end and at least as long as Windows
  requires (`least_bytes`), and that is judged once the read has closed: Media3's cache
  writes the last of a song as its writer closes, and takes a conversion's length from
  wherever the stream stopped, so it cannot be asked whether a conversion is whole. A copy
  that passes is given a mark of our own in the cache's record of it (`WholeMark`, with a
  version); a conversion without the mark is never played from the kept songs and is
  cleared away. One read at a time keeps a conversion; another meanwhile gets the server's
  bytes alone. Whether there is a whole copy and, if not, who keeps one, is one question
  asked in one step (`ConversionWriters.start`), and judging a finished copy and letting
  the keeping go is one step too: asked as two, a read could clear away the copy another
  had that moment finished. Part of one is never kept and never pieced together with the
  server's bytes. The limit in Settings applies the moment it is changed, down to nothing
  (which clears everything kept), and when an account is next taken on if it was changed
  while signed out; if the folder is still held by the account's turn before, it is tried
  again until it comes free. In offline mode the player
  asks the server for nothing, from the moment the switch is turned, streams already open
  included: a song kept whole plays, any other is passed over.
- **Where a song's bytes come from** (`PlaybackService.resolve`): its downloaded copy if
  there is one, whatever the streaming quality, then a copy kept whole among the kept
  songs, then the server at the streaming quality. A download is read as a plain file
  (Media3's `FileDataSource`); one that has gone since is streamed. **A download that won't
  play** (the player can't parse or decode it) is not deleted: it is set aside for this run
  of the app (`Availability.setAside`, Windows' `bad_downloads`), the person is told, and the
  song is streamed. Such a failure is final at once: the player's retry rule used to take a
  file it couldn't parse (Media3's `ParserException` is an `IOException`) for a dropped
  connection and ask for it four more times, fifteen seconds of silence. The format line of a
  song played from its download is the download's own ("FLAC · 48 kHz · 24-bit", or "MP3 ·
  160 kbps" for one saved smaller).
- **Fetching ahead** (`FetchAhead`, Windows' `keep_ahead`): the songs of the next places in
  the queue that aren't downloaded or kept whole are read whole, one at a time, through the
  player's own data source and thrown away as they go, so they are kept by exactly the rules
  the player's own reads are (a conversion only whole). How many: Settings' "Fetch ahead" on
  Wi-Fi (or any network Android doesn't count as metered); **on mobile data the next one
  only**, so a dead spot at a join costs nothing while skipping through a queue on a data
  plan doesn't fetch three songs a skip; **none with Android's Data Saver on**; none offline,
  none with nothing kept, none while paused. (Windows has no such distinction: a computer has
  no data plan.) What is kept whole is known in memory (`KeptIndex`, beside the kept songs),
  so whether a song plays offline is never a look at the disk.
- **Offline** (by hand, or the server out of reach: see "Offline mode" below), a list started
  is only those of its songs that are downloaded or kept, each at its place in the whole list
  (Windows' `play_from`); it starts at the song asked for, or the next one that plays (Windows
  starts at the first that plays); none: "That isn't downloaded, so it can't play while you're
  offline". A stream that fails for want of the server asks whether it is there at all.
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
  song stopped. The ten minutes are counted from when that song last went well
  (`Recovery.Outage`: it stopped after playing steadily, it became the song playing, or
  Play was pressed), so the time the player's own tries take is in them; they are that
  account's and that queue entry's, and another song or account starts from nothing; and a
  moment of sound between two stalls does not start them again, only half a minute of
  unbroken playing does. Passing over a song that can't be played
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
  the first button (and the player is not waiting for the server: then it stays, saying
  "Reconnecting…", as it would had it been playing), the service leaves the foreground and
  stops. The receiver's first name
  (Media3's own class) is still declared, closed to other apps, for a system that remembers
  it from an earlier build.
- **In the foreground when a request arrives, and before the player asks for the sound.**
  Anything that is to end in playing puts the service in the foreground as the first thing
  done for it, before anything is waited for: a song chosen in the car, a spoken request,
  Play with nothing loaded, a list named by the app's screens (`holdForeground`, from the
  session's callback). Android gives a command from a car a short allowance to enter the
  foreground, and what such a request looks up first (the list, a search of up to seven
  seconds, twice three for the saved sign-in and queue) could use it up. And again just
  before any play, whoever asked (`QueuePlayer.mayPlay`): Media3 enters the foreground a
  moment after playback has begun, which is after the player has asked Android for audio
  focus. Whether the service is in the foreground is asked of Android (Media3's
  `isPlaybackOngoing` says whether it has ever been, not whether it is now).
- **A call in progress is not Android saying no.** Refused the sound within two seconds of
  Play while the system says a call is ringing or under way (its audio mode), the car is told
  nothing (opening the app would be the wrong advice), and Play happens by itself when the
  call ends, unless someone has paused or chosen something else meanwhile. Not seen on an
  emulator, whose calls neither change the audio mode nor refuse the sound.
- **When Android says no, Play is not called.** If Android refuses the foreground, or
  refuses the sound straight after Play, the player is not asked to play (or has turned
  Play down itself). The queue stays as it was chosen, stopped at its place, and the
  player's error line says "When it's safe, open Nexus Music on your phone to start
  playing", with a button that opens the app and with Play still offered; it goes at the
  next start that works. It is a player's own error and not Media3's standing one
  (`setPlaybackException`), which takes every command away, Play included, and so could
  not be recovered from the car. A start Media3 itself is refused (at the start, or later:
  the player buffering again after a dropout) is handled through its own hook
  (`MediaSessionService.Listener`) in the same way: noted, the player stopped at its place,
  the same words with Play still offered, never an uncaught exception. A try at the server
  after an outage goes into the foreground first, as a Play does, and is the same recoverable
  error if Android won't have it.
- **Stopping never switches off Media3's ten minutes.** Media3 keeps the service in the
  foreground for ten minutes after playback stops; its `pauseAllPlayersAndStopSelf` switches
  that off for the rest of the service's life, and a car bound to the service keeps it alive,
  so the next song would have left the foreground at the first dropout. A start that came to
  nothing, and a swipe away while paused, pause and stop the service themselves
  (`pauseAndStop`).
- **Handing the foreground to Media3.** The foreground a request entered is held by a
  notification of the service's own until Media3's takes its place. When the time allowed
  for a start runs out (seven seconds) and it still has not: a request still being looked
  up is waited for; a player that is playing, getting ready or waiting for the server has
  Media3 asked to take the notification over, and what is held is kept meanwhile (a busy
  player used to be taken to mean Media3 had it, and the temporary notification was
  forgotten); anything else is stood down. After Media3 reports its notification up to
  date "in the foreground", Android is asked whether the service is in it: Media3 reports
  success when it has taken the notification away instead.
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
  in or refused, and whether a name was not its caller's own), who asked for covers and
  what became of each (given, not given and why, refused), what started the player and
  whether and how soon Android let it into the foreground for each kind of request, a
  start that could not be made and why, audio focus refused straight after Play, what the
  app can tell of being heard each time playing begins (below), a start intent shaped
  like a notification button without the app's mark, which decoder plays MP3 and what is
  trimmed for it, playback errors by kind, and the times the server was given up on. The
  same thing again is counted, not written again. No server address, account or song name
  goes into it, and nothing an incoming intent called itself (a fixed set of words); it
  stays on the phone unless the person shares it (as text, through Android's share sheet).
- **Whether it is heard** (`Audible`). Android 17 can let a player run on in silence and
  tells the app nothing. What the app can find out for itself, and notes: that the
  player's audio track is moving (or has not moved five seconds after playing began);
  that the service is in the foreground; that audio focus was not refused; what Android
  lists as sounding (every app is shown the players sounding now, with their owners taken
  out: so a count of media players, ours among them, and the kind of output each goes to:
  the phone's speaker, Bluetooth, USB, a remote submix such as Android Auto takes the sound
  through); and the media volume. What it cannot find out is whether Android has muted it:
  that is only in `dumpsys audio`. So "moving, in the foreground, listed, going to the car"
  from a drive where nothing was heard is Android muting it.
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
| The same, the app not running | **Android 9: the button never arrives** (below). 10, 11: allowed, by the source | allowed; in the foreground 0.1 s after the service is made | allowed | allowed (a media key is named) | emulator, 9, 16 and 17, each way of ending the app |
| The car (Android Auto) choosing a song, asking for one aloud, or pressing Play | allowed | allowed for a few seconds after the command, if Android Auto could itself start one | the same; the service enters the foreground as the request arrives, before anything is looked up | **not known**: Google's page doesn't say whether a command from Android Auto counts as something the person did | the rig's probe, 9, 15 (car), 16 and 17, and on 16 and 17 after eleven minutes paused with the probe still connected (`--mode long_pause`), on 17 with the hardening forced on; the probe is not Android Auto |
| The system's resume control | allowed | allowed (the system asks) | allowed; foreground first | **not known**, as the car | not run here |
| The Assistant, on the phone | allowed | allowed (it opens the app: `VoiceActivity`) | allowed | allowed (the app is visible) | the activity, by adb, in the first round; never the Assistant |
| The Assistant, in the car | allowed | as the car | as the car | **not known**, as the car | the request, by the probe; not the Assistant |

**A song chosen in the car, step by step** (a spoken request and a bare Play differ only
in what is looked up):

1. Android Auto, bound to the service as a media browser, sends the choice over the
   platform's media session. Android notes that the app has been sent a media-session
   command by an app that could itself start a foreground service, which gives this app a
   few seconds in which it may start one from the background.
2. Media3 hands it to the session's callback (`onSetMediaItems`) on the main thread. The
   request takes its ticket, and the service enters the foreground there and then, with a
   notification of its own that Android holds back and is never seen: `foreground: entered
   at once for a choice`. If Android refuses, that is noted and the request carries on
   (`foreground: Android refused it for a choice`).
3. In the foreground: who is signed in, the list (from the phone's kept pages; the server
   is waited on, seven seconds at most, only for a list never kept), the queue made off
   the main thread.
4. The ticket is asked whether it is still the newest, and the queue's window is handed to
   the session. Media3 delivers the items to the player, where the ticket is asked again,
   and follows them with "make ready" and "play" in the same breath. If the request has
   been overtaken, all three do nothing.
5. Just before the player is asked to play, the service is asked whether it may
   (`mayPlay`): it is in the foreground from step 2, so yes. If step 2 was refused it asks
   Android again; refused again, **Play is not called**: the queue is as chosen, stopped,
   and the car's player shows "When it's safe, open Nexus Music on your phone to start
   playing" with Play still offered.
6. The player asks Android for audio focus. If Android refuses it, the player does not
   play, and the car is told the same.
7. Playing begins. Media3 puts its own notification in place of the service's and holds
   the foreground from there (`foreground: playing in it N ms…`); Android is asked whether
   the service really is in the foreground, and if Media3's has not taken over when the
   start's time runs out it is asked to.
8. When the audio track starts to move: `sound: the audio track is moving. In the
   foreground: yes; Android lists 1 media player sounding, going to …`.

On **Android 16** (and 15) step 6 is where Android's rule bites: audio focus goes only to
the app in front or one running a foreground service, which step 2 has seen to; and step 2
itself rests on the allowance of step 1, which the arrival-time start no longer risks using
up. On **Android 17** the same steps run, and two things are not the app's to decide: whether
a command from Android Auto counts as "something the person did", so that the foreground
service started in step 2 has the standing to be heard; and, if it does not, whether Android
says so. If it refuses focus (Google's page says the request fails), step 6 catches it and
the car is told. If it grants focus and mutes the player, the app is told nothing: everything
in step 8 reads as it should and nothing is heard, which is what that line is there to show.

"Not known" is what the first drive is for; the Diagnostics page records which of these
happened and what Android said. If Android 17 does refuse a Play that comes from the car,
there is nothing more the app can do from inside the service: it is already in the
foreground before it asks for the sound, which is all Android lets it arrange. What to do
then would be decided from what the Diagnostics page says happened.

**Android 9 and a button with the app not running: a known limit.** Android 9 sends a
button, when no session is live, as a broadcast to the component the app's session last
named; Media3 1.11.1 names the playback service on Android 11 and below, and a service is
not a broadcast receiver, so the button goes nowhere. Seen on the Android 9 emulator; not
fixable from the app without a change inside Media3 (or replacing its session), and left
as it is. Android 10 and 11 start a named service properly by
their source, and were not run here. The car's own controls (the probe's) work on Android 9;
whether a headset button reaches a session that is live there was not checked.

### Android Auto

Google draws the car's screen. We supply lists, artwork, what's playing and a few buttons.

- **Tabs**: Home, Playlists, Library, Downloads, always four (a tab that comes and goes
  would move the others under the driver's thumb; Downloads is empty until something is
  downloaded).
  - Home: Liked songs, then what was played and added lately.
  - Library: Albums, Artists, Genres.
  - Downloads: what is on the phone; this tab must work with no signal. "Songs" (every
    download, newest first), then the playlists and Liked songs downloaded whole, then the
    albums with anything downloaded, each with its cover; all read from the downloads' own
    record (`DownloadedViews`), never the kept pages or the server, so it opens with no
    signal from a cold start. A song chosen there plays that list under a name of its own
    ("downloads:album:<id>"), so the queue doesn't follow a page of the server's.
  - Offline (by hand, or the server out of reach), the other tabs show only what plays:
    songs downloaded or kept whole, with each song's place in its list unchanged; albums,
    artists and genres from those songs; playlists with something that plays. Search and
    spoken requests look in the same songs (`LocalSearch`, ranked as search is). Offline
    turned on or off, the car is told to look again at everything it has open. An entry whose cover
    isn't on the phone goes without one offline (the car draws its icon), rather than with an
    address that names no picture.
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
  folder holds at most 200 items and at most 196,000 bytes as reckoned here (which errs
  large, and leaves a quarter of Media3's limit spare), counting what heads it ("Shuffle")
  and counting the folders that only list ranges too. A longer list is shown as numbered
  ranges ("1–200", with the first and last titles), ranges of ranges if need be; a song in
  a range still plays the whole list from its place. One entry too big for any folder by
  itself is shown alone, its words cut down until it fits. A host that does page is given
  that page of the same folder. A range is named by places in its list, so when the list
  changes, the list's folder and every range ever handed out for it are told to load again
  (the tree remembers them by the list's page, not by what the car asked for lately).
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
  events: every change takes the next number and becomes the page's one current value under
  the same lock (a `StateFlow` per page, which screens follow), and the streams of changes
  and of the server's answers are told under that lock too, with no limit, so nothing is
  published out of order or dropped. A refresh or a read from disk begun before a change made
  here (an unlike) is dropped when it finishes; a failed refresh is said beside the page as
  it now stands. Writes of one page take turns, and a page stays in memory until its newest
  change is on disk, so a read from disk never brings back an older value; a page on screen
  that misses a change because it was let go of from memory before it was seen reads the page
  again, rather than keeping the value before. Every atomic
  write in the app goes through a temporary file of its own, named for the run that made
  it; an earlier run's are swept away when an account's pages are opened.
- **Answers from the server** are decoded from the stream straight into the models, never
  as a JSON tree first (a 12 MB playlist as a tree cost 80 to 100 MB), keeping what Windows
  makes of an answer: an absent section is empty, one sent as null is malformed.
- **Library copy**: every artist, album and song, kept compactly for instant search; the
  search and its ranking are a port of `src/search.rs` so both apps find the same things.
- **Covers**: one store. Each cover is fetched once at 640 px (Windows' largest) and cut
  down locally, 300 MB at most per account; Now Playing alone asks for a second, 1200 px
  copy, as its cover is nearly the phone's width, and shows the 640 px one until it
  arrives. Coil and the car's content provider
  (`content://org.medianexus.music.covers/<the account's artwork id>/<cover id>`: an
  identifier made at random for each account the first time it is wanted and kept with its
  data, `artwork-id`, used in cover addresses and nowhere else; never the account's storage
  key, a hash of server and username that is the same on every phone and so could be matched
  between phones or confirm a guess) both read from it: Coil through a
  fetcher of our own (`CoverImages.kt`), with its own disk cache off, so no picture is kept
  twice; it decodes at the size shown (and keys what it holds by that width, so a large
  picture isn't kept for a small place) and holds recent ones in memory: 24 MB at most, or
  15% of the app's allowance if that is less (see TESTING.md for how that was chosen). A few
  covers are read and decoded at a time, and a fetch nobody is waiting for any more is
  dropped before its turn. Now Playing's small copy leaves once the large one has drawn. A
  cover that failed (Now Playing's large copy too) is asked for again when the connection
  comes back; a cover listens for that only while it has failed and the app is in front. Covers of downloaded
  music are kept where the system won't clear them.
- **Saved queue**: `queue.json` and `queue-place.json` in the account's data folder, in
  the Windows app's form (`SavedQueue`, `QueuePlace` and its `queue_shape`), ready for
  "Continue here" in M5. It stays saved through signing out.
- **Downloads** (`downloads/`; how they are kept going is "Downloads: what Android allows"
  below), in the account's own data folder (`files/accounts/<key>/downloads`), which needs
  no permission and which the system never clears; no choice of folder.
  - **A download is a whole file or nothing** (`SongFetch`). It arrives in a part file of its
    own; once it has come to a clean end it is checked: the file as it is must be every byte
    the server said it has, a conversion at least four fifths of its length at the rate
    asked (`leastBytes`, the kept songs' rule). Then it reaches the disk, is moved to its own
    name, and only then is it entered in the account's catalogue (`Catalogue`,
    `downloads.json`), **which is the mark**: a file without an entry is never played and is
    cleared away. The file as it is picks up where it stopped by a byte range, if the server
    answers the range exactly; a conversion starts again. A server's error page in place of
    the song is never saved.
  - **The frame is Media3's `DownloadManager`**: the queue in a table of the account's own in
    Media3's database, two at a time, the network requirement (Wi-Fi only, on by default, or
    any), what is under way stopped when that network goes and carried on when it is back,
    five tries at a download that can't reach the server, and what was waiting read back when
    the app starts. Our own downloader (`Downloads.SongDownloader`) stands in for Media3's,
    which writes into a cache a piece at a time and so cannot tell a whole song from part of
    one. Media3 takes a downloader that ends interrupted, without having been stopped by
    Media3 itself, as having **finished** (`DownloadManager.Task.run`): ours ends that way
    only when Media3 stopped it, and a test on Media3's own manager holds it to that.
  - **Offline** (by hand, or the server out of reach), what is under way stops and waits,
    rather than failing for want of a server; back online, downloads that failed for want of
    it are tried again, and lists downloaded whole are topped up.
  - **What can be downloaded**: songs, albums, playlists and Liked songs, at the quality in
    Settings (`download_quality`; Windows' rule for what is kept as it is). An album, playlist
    or Liked songs downloaded whole stays so: its name, owner, cover and order are kept, and
    whenever its page arrives from the server (or the server is back) the songs it gained are
    fetched quietly. Nothing is taken out because the server's list lost it (as Windows). A
    song removed from it by hand makes it no longer downloaded whole (Windows fetches the song
    back the next time the list is looked at).
  - **Nothing is started that won't fit**: what the server says each file is (or its length at
    the rate asked), with half a gigabyte to spare, against the space free (Windows' check and
    words). Failures are kept with their reason in plain words (`failed.json`), and "Retry
    failed" asks again; the words name no server.
  - **Progress**: per song (Waiting, arriving with how much, Done, Failed and why) and for the
    batch ("Downloading 3 of 12"), in the app's state (`DownloadsState`) and in a notification
    that names no song (song names go to the player's notification and the car as what is
    playing, and nowhere else). That notification needs the notifications permission from
    Android 13 (unlike the player's): it is asked for once, at the first download; refused,
    downloads run all the same and show their progress in the app.
  - **Covers of downloaded music** are copied into the downloads folder (`CoverStore.pin`),
    where the cache's trimming and the system's clearing don't reach, and let go when nothing
    downloaded uses them.
- **Offline mode** (`ServerWatch`, `HeldChanges`: a port of `src/offline.rs`). Offline is
  either switched on by hand (Settings, "Offline mode": nothing is asked of the server at
  all) or the server is out of reach. A request that couldn't reach the server is a reason to
  ask it at once whether it is there (a ping), not proof that it isn't: one cover timing out
  is not the server gone. If the ping fails too it is out of reach, and is asked again every
  15 seconds, quietly, until it answers; any answer meanwhile settles it, and so does the
  phone having a network again. Back: "Back online", held changes are sent, downloads carry
  on. A server that answers with an error is there; only a sign-in it refuses signs anyone
  out, as on Windows ("Your saved sign-in no longer works. Sign in again."). Android 17 with
  a server on the home network and no permission to reach it isn't asked (it can't answer).
- **Held changes**: likes, ratings and plays made while offline, or that couldn't be sent, are
  held for the account on disk (`held.json`), in order, plays with the time they counted,
  and sent when the server is back (Windows' `send_all`, its tests ported): what the server
  dealt with (took, or refused for good) goes; a proxy's error page in the server's place
  counts as "try again", and later changes to the same song wait behind it; no answer, or a
  sign-in it won't take, keeps the lot. What a round is sending stays on file until the
  server has answered, so the app killed part-way loses nothing (a play may then be counted
  twice). "Now playing" reports aren't held: they are of the moment. Before this, a play the
  server didn't take was lost.
- **What plays offline** is known in memory (`Availability`): downloaded (and not set aside
  this run), or kept whole in the form it would be streamed in now (`KeptIndex`). Rows ask it
  and the car asks it; it never looks at the disk.

### Downloads: what Android allows

A download outlives the screen that asked for it: the app is closed, killed, the phone
restarted, the network changed. What Android lets an app do to carry on, by Google's
documentation (read 24 November 2026) and Media3 1.11.1's source:

| Android | What an app may do | What the app does (`DownloadKeeper`) |
|---|---|---|
| 9 to 11 (API 28–30) | A foreground service may be started from anywhere, and runs as long as it likes. A job runs for up to ten minutes at a time | A data-sync foreground service (`DownloadsService`) holds the app up while downloads arrive, with the progress notification. A persisted job, on the network allowed, stands behind it for after a restart, and starts the service again |
| 12, 13 (31–33) | No foreground service may be started from the background (with exceptions none of which is "a job"). Jobs as before | The service, started from the app on screen. After a restart, or if it was stopped, the job does the downloading itself, a few minutes at a time, and is run again until it is done |
| 14 (34) | **User-initiated data transfer jobs**: asked for while the app is on screen, with a network required and a notification of their own, they run until done, unheld by the app's standby bucket and quotas, and Android stops and runs them again as the network comes and goes. Foreground services must say their kind | A user-initiated job when the person asks from the app; an ordinary one when the app isn't on screen (a downloaded playlist topping itself up), upgraded the next time the app is opened. No foreground service |
| 15, 16 (35–36) | A data-sync foreground service gets six hours a day, and none may be started from `BOOT_COMPLETED`. 16: an ordinary job that started while the app was in front, or beside a foreground service, now counts against its quota | As 14: user-initiated jobs aren't in either limit |
| 17 (37) | No change found to jobs or data-sync services (its list names new job debugging APIs only) | As 14 |

**Why not Media3's `DownloadService`**, which the brief expected: in 1.11.1 it asks for a
restart through a scheduler only below Android 12 (`DownloadService.onCreate`:
`canStartForegroundServiceFromBackground = SDK_INT < 31`), so from 12 on, downloads it stopped
(the app killed, the phone restarted, Wi-Fi gone while it was not running) stayed stopped
until the app was next opened; it runs a data-sync service, which 15 limits to six hours a
day; and it keeps one download manager for the whole app, where each account has its own. Its
manager is used, as is; the keeping-going is ours. **Why not jobs alone**: before 14 a job
runs ten minutes at a time and is then put off by Android's quotas, so a long download from
the app on screen is held up by a foreground service there.

What was seen on the emulators (docs/TESTING.md, "Downloads"): on 16 and 17 the app killed
mid-download by `am kill` is not killed at all (a user-initiated job ranks it as perceptible,
as the playback service does); killed outright, Android ran the job again within ten seconds
on 16, and on 17 at its next look at the device (the screen turned on), on a quiet emulator
only then; restarted, the downloads carried on with nobody opening the app; Wi-Fi off with
Wi-Fi only on, they waited, and carried on when it was back.

### What the design left open, and what was decided (M2a)

- **Four tabs always**, Downloads empty until there are downloads (not a tab that appears).
- **Fetching ahead on mobile data**: the next song only; none with Data Saver (above).
- **A list started offline** starts at the song asked for or the next that plays (Windows: the
  first that plays).
- **A song removed by hand from a list downloaded whole** makes the list no longer downloaded
  whole (Windows fetches it back).
- **"Now playing" reports** are not held offline (Windows doesn't hold them either).
- **The download notification** names no song, and the downloads go on without it.
- **The downloads folder** is the account's own data folder, no choice (the brief).
- **A server error page** in place of a song, or a file shorter than the server said, is a
  failure with its reason, never a download.
- **Download failures that can't come right by asking again** (refused, not audio, cut short,
  out of space) are final at once; only a server out of reach is tried again, five times, and
  again when it is back.
- **A playback stream that fails for want of the server** asks whether it is there (Windows'
  player does the same); out of reach, the app is offline until it answers.
- **The covers of downloaded music** are copies of the 640-pixel cover, kept with the downloads.

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
| Sidebar | Bottom bar: Home, Search, Library. Playlists live in Library. A phone on its side (wider than tall, under 480dp tall): a rail down the leading edge with the same three, the mini player along the foot of the page only. |
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
  600 dp and wider, the cover sits beside the title as on Windows, sized by the height (a
  phone on its side also puts the action bar at the end of that row, so the first view shows
  the header, the play button and the first song). Then the kind ("Album"), the
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
  (`PlayerConnection.reported`): Now Playing stays open meanwhile. A connection that fails or
  drops is tried again by itself while the screens are in front, after 1, 2, 4, 8 and 16
  seconds and then every 30 (`Reconnect`); once the player has answered with nothing to play,
  Now Playing closes.
- **Likes have one owner** (`Likes`): what the server last said, from every page that says
  whether something is liked, and what has been done here and is still being sent. Every
  heart, album hearts too, and Liked songs itself are read from it. Changes to one item go to
  the server one at a time in the order made. Each change the server takes reaches the kept
  Liked songs, in order; a refusal is said, and undoes anything, only for the last tap on
  that item, never for one a later tap has overtaken. Liked songs shows the last thing asked
  for throughout: a song unliked and liked again before the server has answered stays in it
  while the server takes the unlike (and the kept copy loses it), until the like is kept. A song found liked on another page
  (liked on another device) joins Liked songs at once, with what that page knows of it.
  Starting Liked songs by name plays the list as shown (`Library.likedShown`), changes still
  on their way included. A heart acts on the likes as they stand, not as last drawn.
- **The queue follows its list**: whenever the kept copy of the list the queue came from
  changes (a fresh copy from the server, a song unliked from Liked songs), the service runs
  `followList` and keeps each entry's number, so the song playing carries on untouched.
- **Mini player**: a rounded card in `overlay` above the bottom bar, with a 2dp line in the
  text colour along its foot. Tap or swipe up opens Now Playing; a sideways swipe skips.
  Only the line reads the position, and only while drawing.
- **Now Playing**: the play button there is Windows' transport button (a circle in the text
  colour), not the accent one pages use; buffering and "Lost the connection…" replace the
  format line in words. On its side, the cover sits beside the controls. A seek being
  dragged belongs to the queue entry it began on (`SeekDrag`), not the song, so the same song
  twice in a row doesn't carry it over; the time shown while dragging goes however the drag
  ends. Swipe down or
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
  sheet comes up to the foot of Now Playing's top bar, no further; in a short window on its
  side what it holds scrolls, its heading in sight, clear of a side navigation bar.
- **A song's menu** has what works so far: Play next, Add to queue, like, Go to album, Go to
  artist (each credited artist when there are several), and Remove from queue from the
  queue. Nothing that does nothing yet is shown.
- **Home**: the greeting, four quick tiles (two lines of two, as Windows on a narrow window),
  then Recently added, Recently played, Most played, It's been a while and Something
  different, each sideways. "Made for you" and "Artists you might like" come later.
- **Library**: chips for Playlists, Albums, Artists and Genres. Liked songs heads the
  playlists, then the pinned ones, then the rest by name. Albums have Windows' seven sorts
  as a second row of chips, sixty at a time as the grid scrolls; Home's "Show all" opens them.
  Paging (`AlbumPages`): a page that brings only albums already shown still counts as
  progress, so loading carries on; a page cancelled on its way leaves the pager able to ask
  again; a refresh leaves the grid as it is until the server's first page comes (the kept
  copy of it doesn't replace it); a later page shown from the phone's copy is put right when
  the server's arrives, and if it was the last page fetched, so are where the next starts and
  whether there is one (a short page kept when the library was smaller no longer ends the list
  for good); coming back (after Android stopped the app too) loads as far as the saved place
  before going there. Each sort keeps its own place (`SortPlaces`, one saved slot for the
  page), so choosing another sort and coming back finds the first where it was. Paging, like the pages' own feeds, runs only while the app
  is in front.
- **Artist**: albums, then singles and EPs, newest first; play plays the songs of their
  first forty releases, newest first as Windows builds it, saying it is getting them and
  how many albums couldn't be fetched, if any. "Popular" and "Fans also like" need the server to ask Last.fm, which
  the test server can't; they come with the rest of browsing (M3).
- **Search**: results as the words are typed (250 ms after the last key; a newer search
  always replaces an older one), in Windows' order: the first four songs, then artists,
  albums and playlists, with the artists first when one matches the words better than any
  song does, judged by a port of Windows' scoring (`SearchRank`), and the playlists whose
  name has every typed word as the start of a word; chips narrow to one kind. Results, and
  what tapping one plays, belong to the words that found them, and so does a failure; rows
  are shown, and tappable, only once those words are the words in the box. Every change of
  words and every "Try again" is a request of its own (going back to words that failed asks
  again). It searches only while the app is in front. With nothing typed, "Browse by genre". Windows keeps
  no recent searches, so neither does the phone. A song chosen from the results plays the
  results from it (Windows starts radio from it, which comes with M3).
- **Toasts** are Windows': a pill in the text colour at the foot, three seconds, for things
  done ("Added to queue") and things that happened out of sight ("That didn't save. …" for
  a like the server refused).
- **Notifications** are asked for once, at the first download, for the downloads' progress
  notification (Android 13 and later). The player's never needed it: Android exempts
  media-session notifications, so its controls appear with it refused (checked on Android
  17), and downloads run with it refused too.
- **What the server said** when it refused something the car asked for (`Graph.refusals`) is
  shown where the phone shows a playback error, on Now Playing and the mini player, until
  something next plays.
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
  are in front and the server was out of reach, it is asked every 15 seconds, except a server
  on the home network while the app may not reach it (asking can't succeed): that resumes
  once it is allowed. A secure connection the server turned down (its certificate) means it
  answered: that is never taken for "out of reach", nor offered the permission as the cause. The car,
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
