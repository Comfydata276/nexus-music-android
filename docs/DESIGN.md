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

Lowest Android version supported: 12 (API 31): the phone the app is for runs the newest
Android, and the last few versions are supported. Built against the newest platform.

## Shape of the code

One Gradle module, `app`, package `org.medianexus.music`:

| Package | What's there |
|---|---|
| `net` | Client for the Subsonic API Navidrome serves at `/rest`; models; errors. A port of `src/subsonic.rs`. |
| `store` | What's kept on the phone: the account, settings, cached pages, the library copy, covers, the downloads list, changes waiting for the server. |
| `library` | Turns server answers into what screens show; search; likes; playlists. |
| `playback` | The playback service, the queue, play reporting, Android Auto's browse tree and voice search. |
| `downloads` | Offline downloads: the catalogue of what is downloaded (the mark that a download is whole), the whole-file fetcher and the check that reads a file through, the engine on Media3's download manager, the job and service that keep it going, and the downloads laid out for browsing with no signal. |
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
- **Gapless** comes with ExoPlayer. **Volume levelling** from ReplayGain tags, the
  **six-band equaliser** and **mono** are ported from `src/audio/` as one audio processor in
  the player's sink (M4a: see "How it is built (M4a, sound and lyrics)"). **Crossfade** (which
  leaves an album alone) needs two songs sounding at once and comes after it.
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
  controls.) On Android 12 the platform's transport hands the app the package name its
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
  there is one (and its file is still the length that was verified), whatever the streaming
  quality, then a copy kept whole among the kept songs, then the server at the streaming
  quality. A download is read as a plain file (Media3's `FileDataSource`). A download that
  needs a fresh copy (below) is passed over for the server's while the server can be had,
  and played all the same when it can't. **A file that is no longer the length that was
  verified is not opened, and the catalogue is told at once** (`Downloads.notWhole`): it is
  unmarked and fetched again there and then, as it would be at the next start. (The player
  used to stream it while every list went on showing it as downloaded.)
- **A song keeps playing from the copy it started on** (`SongDataSource.open`, `SongCopy`).
  Media3 opens a song's data source again at every seek and every retry, asking for a *byte*
  it worked out from the copy it has been reading; that byte means something only in that
  copy. So a data source (Media3 makes one for each media period: one queue entry's turn)
  opens, every time after the first, the copy it opened first: the same downloaded file
  (same name, modification time and length), or the stream in the same form (the kept copy
  of a stream is the same bytes). If that copy has gone (the download removed or replaced),
  or its stream fails while a download has arrived since, nothing is opened at that byte: the
  read fails as "moved" (`StreamFailure.moved`), which is not retried, and the service makes
  the entry ready again where it is, which is a seek by *time* on a fresh read of whatever is
  best now. (Resolved afresh at each open, a streamed song whose download finished mid-song
  was handed the downloaded file at the stream's byte: after a seek to 1:40 it played the
  download's 0:40.)
- **The format line is of the copy being played.** The data source tells the service which
  copy each song was opened from; the service puts its bit rate in the session's extras, and
  the line under the song ("FLAC · 48 kHz · 24-bit", or "MP3 · 160 kbps" for a conversion or
  a download saved smaller) says that, not what the downloads and settings would give now.
- **A download that fails when it is played needs a fresh copy, and nothing is deleted
  until that copy has arrived** (`Downloads.demote`, `Downloaded.stale`). **Only for a
  failure that can be laid at that file**, met while it was the download that was playing
  (`Recovery.laidAtTheFile`): it couldn't be read, it couldn't be parsed, or it holds what
  this phone can't decode. And **only that file**: its name and the modification time it had
  when it was marked. It is marked, on the disk, as needing a fresh copy, shown as on its
  way again, and fetched when the server can be reached; the new copy takes its place only
  once it has been verified, **under a name of its own** (`<id>.r.<kind>`, the next one
  back at the plain name), and the old file goes only once the catalogue's mark for the new
  one is on the disk. If any step fails (the folder can't be synced, the mark can't be
  written) the new file goes and the old copy and its entry are as they were. (It was
  renamed over the old file first, so a mark that couldn't be written then cost both.) **Meanwhile it is still a download**: on the
  phone, listed offline, and played with no server as far as it plays; with the server
  there, the server's copy is played instead. (It used to be unmarked and deleted on the
  spot, and set aside for the rest of the run: a song that stumbled once in a dead spot was
  gone until there was a signal again.) A fresh copy that fails too, in the same run, is
  listed as failed with the reason, not fetched over and over, and is still on the phone.
  **If what failed was the phone's decoding of the file as it is** (the player says the
  format is unsupported, or exceeds what the phone can do), that is remembered on the disk
  (`Catalogue.mustConvert`, `convert.json`, by the song's id, that file's size and the
  version of Android that said so) and the fresh copy is the server's conversion: the same
  original is never fetched again to fail again on that version of Android, and the song is
  streamed converted too. After a system update the question is asked again. A decoder that
  fell over or couldn't be had, or the sound output failing, says nothing about the file:
  the entry is made ready again, once, and nothing else is done. Such failures are final at
  once: the player's retry rule used to take a file it couldn't parse (Media3's
  `ParserException` is an `IOException`) for a dropped connection and ask for it four more
  times, fifteen seconds of silence.
- **Nothing downloaded is read again after it is marked.** The second round read a file
  through each time it had played to its end, and read every download of an earlier build
  through once; both are gone. What they were for was real (on the Android 16 emulator a
  FLAC with bytes changed inside a frame played its whole length with no error, and an MP3
  with a stretch of junk in its middle "played" its stated length, the last twenty-three
  seconds in silence), but a file that was the server's when it was marked is still the
  server's, and the cost was every file read twice and a good copy deleted on a finding
  nobody could act on in a dead spot. What is left is the one case where the player itself
  says the file is at fault (above).
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
  (Windows' `play_from`). A song chosen (a row tapped, on the phone or in the car) that
  doesn't play starts nothing: whatever is playing carries on, and the person is told "That
  isn't downloaded, so it can't play while you're offline"; it is never swapped for another.
  A list asked for as a list (its play button, Shuffle) starts at its first song that plays,
  or the next one after where it was asked to start (Windows starts at the first that plays);
  none: the same words. A song found by the car's search whose album doesn't hold it (an old
  kept page, a song kept from it separately) plays by itself, as a spoken request does. A
  stream that fails for want of the server asks whether it is there at all.
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
  what became of each (given, not given and why, refused), for a caller refused either way
  the SHA-256 of each certificate its package is signed with now and whether it has a
  signing history (`Callers.signedBy`, a line of its own beside the package name, in the
  colon-separated form Google's list of known hosts is written in, and only for a name the
  system says is the caller's: what letting an app in by package and certificate, as the
  known hosts are, needs; it lets nothing in. A signing certificate's fingerprint is public,
  the same in every copy of the app, and identifies no person, only the app's publisher),
  what started the player and
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
  that is only in `dumpsys audio`. "Moving" says the player is progressing, not that sound
  reached a speaker. So "moving, in the foreground, listed, going to the car" from a drive
  where nothing was heard says the sound was lost after the player: Android muting it, or
  the car's own volume or source.
- Debug builds log the player's events (tag `NexusPlayer`) and have `DebugControl`, a
  receiver the rig can drive the player with from adb.

### Starting playback: what Android allows

Playing in the background needs a foreground service of the media-playback kind, and each
version of Android since 12 has added a rule about when one may start or be heard. What the
rules are, by Google's documentation (read 24 November 2026) and Android's source:

| Android | The rule |
|---|---|
| 12, 13 (31–33) | An app in the background may not start a foreground service, with exceptions. The ones that matter here: the person acted on the app's notification; and, by the source rather than the documentation, a few seconds after the system has delivered the app a media button, or a media-session command from an app that could itself start one. |
| 14 (34) | The service's kind must be declared, with its permission (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`). Declared. |
| 15, 16 (35–36) | An app targeting 15 is given audio focus only while it is the app in front or is running a foreground service. So the service must be in the foreground before the player asks. |
| 17 (37) | "Background audio hardening": an app in the background is heard, and given focus, only while it runs a foreground service; for an app targeting 17 (this one) that service must also have been started while the app was visible or by something the person did: Google names a tap on a notification, a widget, and a media key from an outside device. Otherwise playback is silent, with no error. |

And each way playback can start, against them:

| Playback is started by | 12–14 | 15–16 | 17 | Checked here |
|---|---|---|---|---|
| The app's own screens | allowed (the app is in front) | allowed | allowed | emulator, 17 |
| The notification or lock screen | allowed (acting on a notification) | allowed; the service enters the foreground first | allowed (a notification tap is named) | not run: needs the ten-minute wait and a tap on the system's screen |
| A headset or car button, the service running | allowed for a few seconds after the button | allowed; the service enters the foreground first | allowed (a media key is named) | emulator, 17: the car's Play after eleven minutes paused (below); a button then finds the service gone and starts it as in the next row |
| The same, the app not running | allowed; in the foreground 0.1 s after the service is made | allowed | allowed (a media key is named) | emulator, 16 and 17, each way of ending the app |
| The car (Android Auto) choosing a song, asking for one aloud, or pressing Play | allowed for a few seconds after the command, if Android Auto could itself start one | the same; the service enters the foreground as the request arrives, before anything is looked up | **not known**: Google's page doesn't say whether a command from Android Auto counts as something the person did | the rig's probe, 15 (car), 16 and 17, and on 16 and 17 after eleven minutes paused with the probe still connected (`--mode long_pause`), on 17 with the hardening forced on; the probe is not Android Auto |
| The system's resume control | allowed (the system asks) | allowed; foreground first | **not known**, as the car | not run here |
| The Assistant, on the phone | allowed (it opens the app: `VoiceActivity`) | allowed | allowed (the app is visible) | the activity, by adb, in the first round; never the Assistant |
| The Assistant, in the car | as the car | as the car | **not known**, as the car | the request, by the probe; not the Assistant |

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

### Android Auto

Google draws the car's screen. We supply lists, artwork, what's playing and a few buttons.

- **Tabs**: Home, Playlists, Library, Downloads, always four (a tab that comes and goes
  would move the others under the driver's thumb; Downloads is empty until something is
  downloaded).
  - Home: Liked songs, then "Made for you" (each mix a list of songs), then what was played
    and added lately.
  - Library: Albums, Artists, Genres, Radio (the internet radio stations; there offline too,
    and empty, so nothing moves under the driver's thumb).
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
  - **Offline, no car path asks the server** (`CarTree.first`): each reads the kept page if
    it has anything that plays (it has the whole list, so each song keeps its place in it),
    else the downloads' own record, else is empty at once. Android may clear the kept pages
    while the downloads stay (they are in different places for that reason), and a kept page
    can be an old one (from before the server gave its songs other ids), so a downloaded
    playlist or album is always listed and always opens with what is on the phone: Home,
    Playlists, Liked songs, a playlist, an album, each place asked for by id, a song chosen,
    search and a spoken request. The list of playlists puts the downloaded ones in whether
    or not the server's list is kept. **The Albums folders by order** (Recently added,
    Recently played, Most played), whose order only the server knows, show the albums on the
    phone when their own list is not kept (they were empty), **or is kept with nothing on it
    that plays** (that, filtered, was an empty folder taken for the answer). **One playlist asked for by id**
    is answered from what the phone has of that playlist itself (its downloads, its own kept
    page), and only then from the list of playlists.
  - **An album, or a song of one, asked to be played with no page of the album kept** is
    played from the downloads' own record when that has what was asked for (the song
    chosen; for the whole album, the album downloaded whole and all arrived), **whether or
    not the server is known to be there** (`CarTree.downloadedInstead`): chosen in a
    folder, shuffled, added by its id or asked for by voice. It used to go to the server
    first, and on a link that had just gone silent waited out the car's limit with the
    songs on the phone. A downloaded song asked for by itself is had from the record too.
  - **While it isn't known whether the server is there** (the first seconds of a cold
    start: four at most, none at all with no network; and the moment after offline mode is
    switched off), **every path answers at once from what the phone has**, and the server is
    asked behind the answer, the car being told to look again when it lands (`CarTree.first`,
    `PageCache.later`): the kept page (shown as it is shown online, without covers that
    aren't on the phone), else what the downloads and kept songs make of it. Home takes each
    of its three pages by itself (it used to wait for all three if any one was not kept);
    the artist and genre branches, each place asked for by id, a song chosen, search (never
    held up for the list of playlists) and a spoken request (a downloaded playlist asked for
    by name counts as on the phone; but only a name said exactly: a near match on the phone,
    a slip forgiven, waits to know, and then the server's answers decide, where its exact
    match beats the phone's near one) all go the same way. Only where the phone has nothing at
    all is anything waited for, and then only to *know* (those few seconds): after which it
    is the server's answer, or the phone's nothing. No cover is fetched while the server is
    known to be out of reach. **A kept list of songs with nothing in it that plays, of
    something the phone has songs of** (an old copy, from before the server gave its songs
    other ids) gives way to the songs on the phone in those seconds too, as it does offline:
    shown as it was kept, it was a folder of nothing that would play. **And an answer worked
    out on the phone is finished on the phone**: a search or a spoken request that has found
    what it wants there takes its playlists, and the list it then plays, from the phone as
    well, whatever has become of the server meanwhile (it used to look again at whether the
    server was there, and if it had come back in that moment, wait on a fresh list of
    playlists with the answer already in hand).
  - **A song that isn't on the phone, at the cursor when the server is known to be out of
    reach and not yet heard, is passed over at once** for the next in the queue that can be
    played, with a line saying so ("Can't reach your server: skipped to a song on this
    phone"); with none, the player stops where it is and says that
    (`PlaybackService.passOverOutOfReach`, asked when the check fails, when the player comes
    to such a song, and when its stream fails). **"The next" is the next in the order the
    queue will play** (`Recovery.nextThatPlays`, asked of the queue itself): on through the
    playing order, which is the shuffled order when shuffle is on, through all of the queue
    and not only the part of it the player holds, and round from the end to the top when
    repeat is on. (It was looked for along the player's own playlist, by position: a song on
    the phone three hundred places on in a long queue was "nothing further on", and repeat
    counted for nothing.) A song that fails for any other reason is passed over the same
    way: to the queue's next, not the playlist's. **Offline, passing over ends**
    (`Recovery.PassingOver`): in one run of passing over (each failure of the entry it last
    passed to), a song that has already failed is not tried again, and when every song on
    the phone has, it stops there and says nothing can be played offline. (Two downloads
    that both failed to open, with repeat on, sent the player from one to the other for
    ever: a stale download still counts as on the phone, and this path is outside the
    limit of three failures in a row.) Something playing, or Play pressed, ends the run;
    a song the person chooses begins a new one, so a stale copy can still be played on
    purpose. It used to wait as "Reconnecting…" for as
    long as the stream's own tries took: the stream's loader looks only at the switch set by
    hand. A song that was *playing* when the server went still stays where it is and carries
    on when the server is back.
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
  Play button resumes or does nothing). No match: "Couldn't find that in your library", a
  fixed sentence: the words said are the person's, and the session's error is read by every
  app with a controller. So is a playback error: "Couldn't play this song", never its name. The phone's own Assistant reaches the same code through `VoiceActivity`.
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
- **Library copy** (`LibraryCopy`, `SongTable`): every artist, album and song, kept compactly
  for instant search and the Songs page; the search and its ranking are a port of
  `src/search.rs` so both apps find the same things. See "The library on the phone" below.
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
  the Windows app's form (`SavedQueue`, `QueuePlace` and its `queue_shape`). It stays saved
  through signing out. (How it stands beside the queue saved on the server for the person's
  other devices: "How it is built (M5a…)".)
- **Downloads** (`downloads/`; how they are kept going is "Downloads: what Android allows"
  below), in the account's own data folder (`files/accounts/<key>/downloads`), which needs
  no permission and which the system never clears; no choice of folder.
  - **"Done" means the right song, whole, on disk** (`SongFetch`, `Media3AudioCheck`). A
    download arrives in a part file of its own; once it has come to a clean end it is
    **verified**, and only then moved to its own name (the folder synced) and entered in the
    account's catalogue, **which is the mark**: a file without an entry is never played and
    is cleared away. What verified means depends on whether anything says how long the file
    should be:
    - **The file as it is, as long as the server said that answer was, and the size the
      library gives the song: it is the server's file, complete, and that is enough.** (If
      the library's size disagrees, the server is asked for the song again, once, and its
      newer word gone by.) It is not read through, timed or checksummed. Two things a length
      cannot say are asked of its beginning: **that the player's own readers recognise it
      and find sound in it** (tried in the order the player tries them, and read only as far
      as the format of the sound: a FLAC's first block, an MP3's first frame, an MP4's
      header wherever in the file that is), which is what catches a server's error page
      saved as a song; and **that the phone can decode that sound** (below).
    - **A conversion (which comes with no length), or a file the library gives no size for:
      nothing else says whether it is whole, so it is read through to its end**, every
      sample (not decoded: read and let go), with no error and no end part-way through
      anything; **its last frame must be whole and its running time more than nothing,
      whatever sizes agree**; it must not contradict the length its own container states
      (half a second short of it, or past it); and its samples must run as long as the song
      is known to (no more than a second and a two-hundredth short, two seconds and a
      hundredth over; the server's newer word if the first disagrees; with no length known
      for the song, a clean end is all there is, and is taken). Two things Media3's readers
      pass in silence are caught there: a FLAC frame that doesn't match its own checksum,
      **its last frame included** (a tag written after the sound is read as part of the
      last frame, so one frame's worth at the end used to be let pass; now it is told apart
      by looking: the checksum comes right part-way and a tag's own signature, or padding,
      follows), and an MP3 with a stretch that is not MP3, where the reader looks 128 KiB
      for the next frame and then ends the song there with no error (taken for damage
      unless what it had read by then is the song's length, when the rest is only a tag).
    - **The phone can decode it, by the player's own answer** (`Media3AudioCheck
      .playerDecodes`): the decoders for that kind of sound, as the player's renderer lists
      them, and whether one of them takes **this very format**, its profile, channels and
      sample rate (`MediaCodecInfo.isFormatSupported`, what `MediaCodecAudioRenderer` asks
      at 1.11.1); not merely whether a decoder for the kind exists. **A question that can't
      be put is a no, for that download only**: it is downloaded converted, and nothing is
      remembered. A definite no (an ALAC in an `.m4a`; more channels than the phone
      decodes) is not a failure: the server's conversion is downloaded in its place, that is
      remembered on the disk with the version of Android that said it, and the file as it is
      is not asked for again on that version. (A question that failed was written down for
      good, as a no is.)
    - **The server's own copy may be what has the flaw.** When a file that is a song fails
      its reading-through, and the next complete fetch brings exactly the same bytes (the
      same length and SHA-256) failing the same way, asking again will bring nothing else:
      it is kept, marked downloaded, its entry says what is wrong with it
      (`Downloaded.flaw`), and Diagnostics says the server's copy has a flaw. One that fails
      differently each time is fetched three times in all and then listed as failed with
      the reason. Something that is not a song at all is never kept, however often it comes.
    **Bounded**: one file is looked at or read through at a time across the app; a
    reading-through is called off when the download is; no embedded picture is decoded and
    an ID3 tag is stepped over rather than read into memory (Media3 reads the whole tag
    first); what it cannot avoid is what the player itself needs, an MP4's sample tables
    (TESTING.md has the times and memory).
  - **What "Done" guarantees, in three sentences.** A song marked Done that was fetched as
    the file it is on the server is a file on the phone exactly as long as the server said
    it was sending and as the server's library says the song's file is, whose beginning the
    player's own reader recognises as sound the phone can decode. A song marked Done that
    the server converted, or whose size the library doesn't give, was read from its first
    sample to its last when it was marked, ended whole, and ran as long as the song is known
    to (or, with no length to go by, to a clean end); unless its entry says it has a flaw,
    which means the server sent the very same bytes twice and they are all there is. It is
    not a promise that every byte inside is the sound that was recorded (nothing is decoded,
    and an original is not read past its beginning: a file the server holds damaged is
    downloaded damaged, as it would be streamed), and it is true of the file as it was then:
    a file whose length has changed since is not opened and is fetched again at once, and
    one that fails when played is kept, marked as needing a fresh copy, and replaced when
    one has arrived.
  - **Picking up where it stopped.** The file as it is can be carried on with, but only as
    part of the *same* file. A part has a record beside it (`.<name>.part.meta`): the
    server's validator for the copy it came from, the file's whole length, and how much of the
    part is known to have reached the disk (it is synced every megabyte and when a fetch
    stops, **and its record with it, renamed into place and the folder synced each time**:
    after a power cut a resume starts from there. Only the first record used to be written so,
    and a power cut could give back a record several checkpoints old, or none). **The
    validator must be a strong one**: an `ETag` that isn't marked weak, or a `Last-Modified`
    that is at least a minute older than the `Date` of the answer it came in (a file changed
    twice in one second has one date, and the server can't tell the copies apart: HTTP's rule
    for `If-Range`). A date closer than that, or an answer with no date to hold it against,
    is no validator, and the song is fetched from its start. The rest is asked for on condition that the server's copy is still that one
    (`If-Range`), and taken only if the answer is exactly the rest of it: the range from where
    the part stops to the end, the same total, the same validator in the answer (a server
    that ignores the condition is not believed). A changed copy, a server that gives no
    validator, "no such range", or anything else that doesn't fit throws the part away and
    fetches from nothing. A part is never made a download on the strength of its size. A
    conversion starts again. A server's error page in place of the song is never saved.
  - **A marked file that is not the length recorded is not a download, at sign-in and in
    the run alike**: the catalogue drops it as it is read, or the moment the player finds it
    so as it opens the song (`Downloads.notWhole`), and the engine fetches it again; the
    player opens a download only if it is still that length. The catalogue and the player
    never disagree about it.
  - **The catalogue survives being interrupted** (`Catalogue`). A mark is one line added to
    a log and synced. The log is only ever added to after its last whole line: a line cut
    short (the app killed, a write that failed) is cut off when the log is read and again
    before each line added, so no mark is joined to a fragment and lost with it. Now and
    then the list is written whole: the new list to its own file and synced, the old one
    renamed to a backup, the new one into place, the folder synced, and only then the log
    emptied in place, so that a stop at any step leaves a list and a log that together say
    everything; a list that can't be read falls back to the one before, and says so. If the
    list can't be written whole that is said in Diagnostics and tried again later. **Nothing
    is done on the strength of a folder that couldn't be synced** (`syncDirOrFail`): the log
    is not emptied, a mark is not written in a log whose own name may not be on the disk, a
    download is not marked when its file's name may not be, and a durable write (a list kept,
    a change held) is a write that failed. Every caller used to ignore whether the sync had
    worked. A download found at start-up to be missing or short, with no room to fetch it
    again, is listed as failed for want of room (it was dropped, while Diagnostics said it
    was being fetched). Unmarking
    comes before deleting: a download whose removal can't be written stays a download. The
    sweep that clears away what is nobody's runs once, when Media3's manager has read its
    queue and before any work is accepted, under the catalogue's lock, and leaves alone what
    an entry names, what the queue still wants, and any file as new as this run.
  - **The frame is Media3's `DownloadManager`**: the queue in a table of the account's own in
    Media3's database, two at a time, five tries at a download that can't reach the server,
    and what was waiting read back when the app starts. Our own downloader
    (`Downloads.SongDownloader`) stands in for Media3's, which writes into a cache a piece at
    a time and so cannot tell a whole song from part of one. Media3 takes a downloader that
    ends interrupted, without having been stopped by Media3 itself, as having **finished**
    (`DownloadManager.Task.run`): ours ends that way only when Media3 stopped it, and a test
    on Media3's own manager holds it to that. **Media3 is asked for no network of its own**
    (see "Downloads: what Android allows"): whether downloads may run is decided in one place
    (`Downloads.gate`). A new engine for a folder waits for an earlier one's fetches to let
    go of it (a quick sign-out and sign-in to the same account).
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
  - **A song's file is kept while anything wants it**: any list downloaded whole that holds
    it, or having been downloaded by itself (recorded with the lists, under a key of its own
    that is never shown as one). **Every removal or cancelling of a group goes through one
    path** (`Downloads.withdraw`): a list's own button, and a group's button on the Downloads
    page (which used to delete every song it showed and forget every list any of them was
    in). The wish for the group goes, and of its songs only those no other list downloaded
    whole holds are touched; no other list's standing changes. A group that is no list
    downloaded whole (an album made of songs downloaded one at a time) *is* those songs' own
    downloads, and removing it removes them. A list's button is a Cancel only when that list
    is itself on its way; with one of its songs arriving for another list, it downloads the
    list. **A group action covers every member of the group, arrived or not**
    (`Downloads.membersOf`): the engine works the members out (a list's songs as recorded;
    for an album that is no such list, every song of it downloaded or on its way), because
    the Downloads page can only name the songs it shows, which are the ones that have
    arrived. Removed, a half-arrived album is all removed: what has arrived is deleted and
    what is on its way is stopped. (The page's ids were all that was acted on: the one
    arrived song went, and the rest carried on arriving for a list just forgotten.) **A
    list's own button goes the same way**: what it is (Cancel, Remove) is decided by what
    its page shows, and what it acts on is every member of the list, so a song a top-up has
    queued that the page has not caught up with is cancelled with the rest.
  - **The wish goes first, on the disk, or nothing goes** (`Catalogue.forget`, `unsingle`).
    That a list is wanted whole, or a song for itself, is withdrawn durably before a file is
    deleted or a transfer stopped; if the phone won't take that write nothing else is done,
    and the person is told ("Nothing was removed: the phone wouldn't save the change", or
    "Nothing was cancelled"); so too for a song's own Cancel and Remove and for Cancel all,
    which say they are done only once it is written. It
    used to be forgotten in memory whatever became of the write: "Removed" was said, and at
    the next start the list was back and fetched its songs again. A song removed or
    cancelled by hand goes the same way, in its turn with every other asking.
  - **A Cancel stays cancelled.** Cancelling, one song or everything, stops what is waiting
    and in flight and withdraws the wish to keep whole the lists those songs were for (as
    removing a song by hand does), so the next look at the list, or the server coming back,
    doesn't fetch them again. What had already arrived stays downloaded. A page of a list
    that arrives while the list is being cancelled asks for nothing.
  - **Remove all** gives back everything in the downloads' folder (`Catalogue.clear`): the
    songs, parts of downloads cut short and their records, the covers kept with the
    downloads, the list before the present one, anything left lying (all but the empty
    list itself and what is known of the phone's decoders). **It looks at the folder
    afterwards and says whether everything went**: what is still there is tried once more,
    and if any of it stays, the answer is no and the person is told ("Some downloads
    couldn't be removed"). **And a cover still arriving cannot undo it**: from the moment it
    begins no cover is kept with the downloads, the requests for covers under way are
    cancelled, and one already being written is waited for before the folder is emptied.
  - **Nothing is started that won't fit**: what the server says each file is (or its length at
    the rate asked), **with what is already queued and arriving still needs**, and half a
    gigabyte to spare, against the space free (Windows' check and words). Requests take turns,
    each decided on what the one before left, so two albums asked for together cannot both
    pass a check that only one fits; and what is on its way is known the moment it is asked
    for, so asking again never starts it again (Media3 restarts a download whose request is
    added twice). **And the room is watched as bytes arrive**: that check is an estimate made
    when the songs were asked for, so each download looks again before it begins and every
    128 KiB, and stops cleanly, with half the spare room still free ("The phone is nearly out
    of space."), giving back what had arrived of it. Failures are kept with their reason in
    plain words (`failed.json`), and
    "Retry failed" asks again; the words name no server. Every write to the catalogue from the
    engine's side is made off the main thread, in order; one that fails (a full phone) is
    said in Diagnostics and never thrown.
  - **Progress**: per song (Waiting, arriving with how much, Done, Failed and why) and for the
    batch ("Downloading 3 of 12"), in the app's state (`DownloadsState`) and in a notification
    that names no song (song names go to the player's notification and the car as what is
    playing, and nowhere else). That notification needs the notifications permission from
    Android 13 (unlike the player's): it is asked for once, at the first download; refused,
    downloads run all the same and show their progress in the app. **The room downloads
    take**, as the person is told it, is everything in their folder (what is part-way, the
    covers kept with them, anything left lying): what removing them would give back.
  - **Covers of downloaded music** are copied into the downloads folder (`CoverStore.pin`),
    where the cache's trimming and the system's clearing don't reach, and let go when nothing
    downloaded uses them.
- **Offline mode** (`ServerWatch`, `HeldChanges`: a port of `src/offline.rs`). Offline is
  either switched on by hand (Settings, "Offline mode": nothing is asked of the server at
  all) or the server is out of reach. A request that couldn't reach the server is a reason to
  ask it at once whether it is there (a ping), not proof that it isn't: one cover timing out
  is not the server gone. If the ping fails too it is out of reach, and is asked again,
  quietly, until it answers.
  - **Back only when a check has returned a real, successful answer**, exactly as Windows'
    `check_connection` has it. An answer to some other request while out of reach (it may be
    a proxy's error page) is only a reason to ask now; an error from the check, of any kind
    but a refused sign-in, leaves it out of reach. Back: "Back online", held changes are sent,
    downloads carry on. Only a sign-in the server refuses signs anyone out, as on Windows
    ("Your saved sign-in no longer works. Sign in again."). Android 17 with a server on the
    home network and no permission to reach it isn't asked (it can't answer).
  - **Asked only while something wants to know** (the app in front, something playing or
    meant to be, downloads waiting, changes held), and **less often the longer it has been
    gone**: Windows' fifteen seconds for two minutes, every half minute to ten, every minute
    to an hour, then every five. Coming to the front, or pressing Play, asks at once.
  - **Known from the first instant.** When an account is taken on with no network at all,
    the app is offline at once; when the network is lost, at once. With a network, the server
    is asked straight away and given four seconds; until it has answered or failed, the state
    says it isn't sure (`Reach.sure`), and the car is answered from the phone wherever the
    phone has an answer. Each check belongs to the account it was made for: a late answer for
    one that has gone changes nothing.
  - **Offline mode switched off is not online yet.** The app is out of reach, and not sure of
    it, until a check of the server has succeeded (the same four seconds): the sender of held
    changes and the downloads are not let go on the strength of a switch.
  - **One ask at a time, and the newest answer wins.** Every ask goes one way and takes a
    number as it begins; an answer older than one already acted on changes nothing (the
    periodic ask used to go out beside one already in flight, and a slow failure could undo a
    later success). Something that wants to know now (the app coming to the front, Play, a
    network back) gives up an ask that has been out more than four seconds for a new one.
    **Asking whether an answer is the newest, and acting on it, are one step** under one
    lock, for every way an ask can end (an ask that may not be made, for want of the
    permission to reach the home network, used to skip the question, and one given up for a
    newer ask could put the app out of reach of a server it had just heard from); everything
    else that changes where the app stands takes the same lock.
- **Changes for the server go through one sender, once, in order** (`HeldChanges`): likes,
  ratings and plays are written to the phone (`held.json`) and then sent in their turn.
  There is no other way to the server, so nothing overtakes a change that is waiting: a like
  made in offline mode and an unlike made the moment it is switched off arrive as a like and
  then an unlike. **Two rules carry it** (the third round of hardening put them in place of
  what the first two had grown):
  - **A change is in the queue only once it is on the disk.** Making a change writes the
    queue with the change added (written, synced, the folder synced) and only then puts it
    in the queue, where it can be counted, seen or sent; its maker waits for that. If the
    phone won't take the write the change never joins the queue: its maker is told it
    didn't save (a play, which nobody is watching, is written in Diagnostics), **whether or
    not the server is there**. (It used to join the queue as it was made, its write
    following: a pass could send a change the phone had no record of, and a pass that ended
    had to tell "not written yet" from "couldn't be written", which it twice got wrong.)
  - **Nothing takes a change out of the queue but its own answer.** A pass that ends, for
    any reason, tells the makers of what is left that it is held, and removes nothing.

  It leaves the file as soon as its own answer arrives, **and the next is not sent until it
  has**: if the phone won't take that write, sending stops there, says so once, and is
  tried again later (an older like could otherwise stay on the disk after a newer unlike
  had reached the server, and be sent again, after it, at the next start). **One sender per
  account for the whole process**: the queue, its writer and its pass belong to the file,
  not to the object made for one sign-in, so signing out and straight back in to the same
  account cannot run two, or let an old write land over a new one. A file that can't be
  read is set aside (`held.damaged.json`) and said, never read as empty and written over;
  one that can't be set aside either is left as it is, and no change is taken on until it
  can be moved out of the way. What is said to have been sent ("the 2 changes you made
  offline…") counts what the server accepted, once.
  **What an answer means** (Windows' `send_all`, its tests ported, with one difference):
  taken, it goes; **refused for good only when the server says something definite about
  that change** (the thing isn't there, code 70; the account may not do it, code 50), it
  goes and its maker is told. **Any other error of the server's says nothing about the
  change** (a fault of its own, a code the app doesn't know; Windows takes these as final):
  it waits, later changes to the same thing wait behind it, the rest go on, and it is tried
  again later, **for as long as it takes, a like or a rating as a play** (a like or a rating
  used to be given up a day after it was made, with nobody told).
  A proxy's error page in the server's place is the same wait, and the server is asked
  whether it is there; no answer, or a sign-in it won't take, keeps the lot. The app no
  longer being offline starts the sender, however that came about. **And what is kept that
  way is tried again** in half a minute, then at twice the wait each time nothing comes of
  it, up to half an hour: if the server is there after all (one request lost as the phone
  went from Wi-Fi to mobile data), the app was never offline and nothing "comes back" to
  start the sender. **Plays are not exactly-once**: the Subsonic interface has no way to
  make a submission safe to repeat, so an app killed in the instant between the server
  accepting a play and the phone recording that (one small write, begun as the answer
  arrives) sends it again and it is counted twice. "Now playing" reports aren't held: they
  are of the moment.
- **A like still waiting to be sent wins over what the server says** (`Likes`). What is
  shown as liked is the server's last word, then every held like and unlike in the order
  made, then what is being sent this moment; what an earlier run left held is read as the
  account is taken on. (A held like used to be taken as if the server had it, so the
  server's Liked songs, asked for as it came back and before the like was delivered, turned
  the heart off until the next refresh.) **A held like keeps the song as it was shown**
  (`Pending.Star.song`), so Liked songs lists it while it waits, across a restart too, when
  the phone's copy of the list may by then be the server's, fetched while it was held (it
  used to show only the heart until the like was delivered; M3b, item 8).
- **What plays offline** is known in memory (`Availability`): downloaded (and not set aside
  this run), or kept whole in the form it would be streamed in now (`KeptIndex`). Rows ask it
  and the car asks it; it never looks at the disk.

### Downloads: what Android allows

A download outlives the screen that asked for it: the app is closed, killed, the phone
restarted, the network changed. What Android lets an app do to carry on, by Google's
documentation (read 24 November 2026) and Media3 1.11.1's source:

| Android | What an app may do | What the app does (`DownloadKeeper`) |
|---|---|---|
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

**When downloads may run, and on what** (`Downloads.gate`, `NetworkWatch`,
`UnmeteredNetwork`). Decided in one place, from: offline mode; the network; whether our own
server answers **on that network**; and whether Android has stopped the job. **Never from
whether Android has "validated" the network as reaching the internet**: a music server on a
home Wi-Fi with no internet beyond it is there all the same. Media3's own network requirement
waits for a validated network and re-checks on validation changing, so Media3 is asked for
none; and `JobInfo.setRequiredNetworkType` asks for a validated network too, so the job asks
for its network by what it is (`setRequiredNetwork`: carries ordinary traffic, VPN or not;
unmetered for Wi-Fi only).

**"Download on Wi-Fi only" is a promise, not a look at the default route, and it holds for
the whole life of a transfer.** It governs what downloading fetches, and only that: **the
songs downloaded, the covers kept with them, the one other thing a download may ask of the
server (the song as the library has it now, when a size disagrees), and the downloads' own
question whether the server answers on that network**. It does not govern playing a song
that is not on the phone, fetching ahead the next songs of the queue, artwork for the
screens and the car, or the app's own checks of whether the server is there
(`ServerWatch`): those go by the phone's own route, whatever it is. With Wi-Fi and mobile
data both up, the phone's default network can be mobile data while the Wi-Fi is there (a
Wi-Fi with no internet beyond it; a weak one), and can change between one look and the
next. So with Wi-Fi only on:

- while songs or covers of downloads are waiting the system is asked for an unmetered
  network (`ConnectivityManager.requestNetwork`; `CHANGE_NETWORK_STATE` is declared for
  it), and **every request of the downloads is bound to exactly the network it gives**: its
  sockets and its name lookups (`BoundRoute`). Nothing else is used;
- **if Android won't take that request, or reports it unavailable (which is also Android
  dropping it), it is asked again** after a pause that grows (five seconds, then twice as
  long each time, up to five minutes) for as long as something is waiting; **when nothing
  is waiting the request is given up** (no song, and no cover still to come: the last
  song's cover used to lose its network as the song arrived), so the phone is not kept
  holding a second network up for nothing;
- with no such network, nothing is transferred, whatever the default route looks like
  ("Waiting for Wi-Fi", which is then true);
- **whether the server can be reached is asked on that network**, not on the phone's own
  route (`Downloads.askOn`): **until it has answered there nothing is sent that way**, not
  a song's request nor a cover's (a worker carrying on as the network changed, or a cover
  asked for then, used to go by the new network at once; a cover waits a minute for the
  answer, and is otherwise not kept this time), and
  while it doesn't (a sign-in page; a network the server isn't on) it is asked again there,
  less often the longer it has been, and no song is thrown at it ("Waiting for the server").
  Nothing else is tried meanwhile: that is what the setting says. What the app as a whole
  knows of the server (`ServerWatch`, asked on the phone's own route) decides nothing here:
  downloads used to wait for a server the Wi-Fi reached perfectly well because mobile data
  didn't, and used to be thrown at a Wi-Fi the server wasn't on, a minute apart, because
  mobile data did;
- **a request under way is cancelled the moment its way out may no longer be used**
  (`Downloads.stopStrays`, `SongFetch.routeGone`): the network gone, turned metered, or
  given up for another; or Wi-Fi only switched on while a transfer was going by the phone's
  own route. The HTTP call itself is cancelled, a song's, a cover's or the download's
  question about the song, on the thread the change is heard on. (A transfer used to be asked before each read, so one on a stalled
  link was not told until its connection next gave it something, and a cover was never
  told.) **"The same way out" is the very route it began on, not the same network**: a
  network that turned metered and back, or went and came back, is another route. What had
  arrived is kept, and carried on from by the next unmetered network, at once if the system
  has already given one;
- each attempt takes its route as it begins.

**With Wi-Fi only on, the network the app asked for governs, not the job's.** Android also
gives the job a network (its own choice of one that meets the job's requirement). Transfers
are bound to the one `requestNetwork` gave the app, because that is the one the app is told
about the instant it goes or turns metered, and holds only while something waits; the job's
network is Android's condition for running the job, and when it goes Android stops the job
and the transfers with it. The two are usually the same. When they are not (two unmetered
networks up at once), that is written in Diagnostics, once for each pair, so that it is
never so without our knowing.

With Wi-Fi only off: any network, as the system reports it (there or not); whether the
server answers is what the app as a whole knows (`ServerWatch`, on the phone's own route);
and transfers go out on the network Android gave the job (followed when Android gives it
another: `onNetworkChanged`), falling back to the phone's own route, which is the one that
was asked, if the job's network doesn't reach the server. **When Android stops the job**
(`onStopJob`: the network it was given went, its time is up) the transfers stop with it,
there and then ("Paused by Android for now"), and resume when the job runs again or the app
is opened. Signed out, the job is cancelled, running or not, and the service stopped; a
schedule Android rejects is asked for again.

What was seen on the emulators (docs/TESTING.md, "Downloads"): on 16 and 17 the app killed
mid-download by `am kill` is not killed at all (a user-initiated job ranks it as perceptible,
as the playback service does); killed outright, Android ran the job again within ten seconds
on 16, and on 17 at its next look at the device (the screen turned on), on a quiet emulator
only then; restarted, the downloads carried on with nobody opening the app; Wi-Fi off with
Wi-Fi only on, they waited, and carried on when it was back.

### What the design left open, and what was decided (M2a)

- **Four tabs always**, Downloads empty until there are downloads (not a tab that appears).
- **Fetching ahead on mobile data**: the next song only; none with Data Saver (above).
- **A list started offline** from its play button or Shuffle starts at the first that plays;
  a song chosen that doesn't play starts nothing (see Playback above).
- **A song removed by hand from a list downloaded whole** makes the list no longer downloaded
  whole (Windows fetches it back).
- **"Now playing" reports** are not held offline (Windows doesn't hold them either).
- **The download notification** names no song, and the downloads go on without it.
- **The downloads folder** is the account's own data folder, no choice (the brief).
- **A server error page** in place of a song, or a file shorter than the server said, is a
  failure with its reason, never a download.
- **Download failures that can't come right by asking again** (refused, out of space) are
  final at once; a server out of reach is tried again, five times, and again when it is back;
  one that failed its checks (not audio, cut short, not the file the library has) is fetched
  afresh three times in all (the hardening, below).
- **A playback stream that fails for want of the server** asks whether it is there (Windows'
  player does the same); out of reach, the app is offline until it answers.
- **The covers of downloaded music** are copies of the 640-pixel cover, kept with the downloads.

### What the hardening of the offline engine decided (after M2b)

An independent review of M2a answered "would you trust this to have your music ready in a
dead spot?" with no. What was changed is described where it belongs above; what had to be
decided on the way:

- **How close "as long as the song is known to be" must be**: no more than a second and a
  two-hundredth of its length short, two seconds and a hundredth over. The library gives a
  length in whole seconds and an encoder adds a few hundredths; nothing else is allowed for.
- **How long a file is, is read as the player would read it** (since the second hardening:
  by where its last sample ends, every sample having been read; what follows is how it was
  first done, from what the container says). Media3's extractors: a FLAC,
  MP4, WAV or Matroska says; an Ogg's last page says; an MP3 says in its Xing or VBRI
  header, or, with neither (every conversion the server streams), by its size at its
  constant bit rate. A file that states no length (a bare AAC stream) is judged on its size
  alone. **Known cost**: a file whose length in the server's library is wrong (an old
  variable-rate MP3 with no header, which the server's own reader may estimate differently)
  fails to download, with the reason, rather than being marked Done on trust.
- **A file the phone has no way to play counts as that file's failure** (unsupported format,
  as against a decoder falling over): it is set aside and the song streamed, where the
  server can convert it.
- **A resume loses up to a megabyte after the app is killed outright**: only what was synced
  is carried on from, since after a power cut nothing else can be trusted and the two can't
  be told apart.
- **A list's download button, with one of its songs arriving for another list**, downloads
  the list (Windows would cancel; there would be nothing of this list's to cancel).
- **Cancelling one song by hand** left the lists it is in downloaded whole (the next look at
  the list fetched it again); removing one by hand makes them no longer whole. (The second
  hardening made a Cancel do the same as a removal: see below.)
- **The first check after start-up is given four seconds.** Long enough for a slow mobile
  link; short enough that a car is not kept from what is on the phone.
- **No network at start-up is "out of reach" without asking**, and the server is asked all
  the same (one on the phone itself, or over a link Android doesn't count, answers); its
  answer then says nothing ("Back online" is for a server that was there and went).
- **Before the phone is first unlocked after a restart** the app is not started at all
  (it is not "direct boot aware", and could not be without everything it builds at start-up
  being rebuilt to run with none of its data): see TESTING.md for what a car is shown then.

### What the second hardening of the offline engine decided

The reviewer, asked again, still answered no. What was changed is described where it belongs
above; what had to be decided on the way, and what was found:

- **A FLAC with a frame that fails its checksum is refused at download**, though Android's
  decoder would play on past it (seen on the emulator). The damage is as likely the
  journey's as the server's, and a fresh fetch puts it right; if all three fetches are the
  same, the server's own file is damaged, and the song is listed as failed with that reason
  rather than kept with a flaw nobody was told of.
- **No checksum, no word**: an MP3, AAC, Ogg or WAV carries nothing that can be checked
  without decoding, so bytes changed inside their frames go unseen at download. Decoding
  every download was not taken on (it is the cost of playing it).
- **A download is read through again when it has played to its end**, not when the player
  reports trouble: for a damaged file the player reported none.
- **Old downloads are read through only while the server can be asked.**
- **Cancelling one song by hand now takes its lists out of "downloaded whole"** (the first
  hardening left them whole, and the next look at the list fetched the song back), so that
  one Cancel and Cancel all mean the same.
- **A group on the Downloads page that is no list downloaded whole is removed by removing
  its songs' own downloads**; a song of it that another list holds stays.
- **An MP3's reader giving up before the file's end is damage unless the song's length had
  been reached** (then the rest is taken for a tag). With no length known, it is damage.
- **Sixty seconds** between a modification date and its answer's own date before the date
  counts as a validator (RFC 7232's figure; RFC 9110 says one).
- **Half the spare room** (250 MB) is where a download in flight stops.
- **With Wi-Fi only on and the Wi-Fi not reaching the server, downloads wait** (and try once
  a minute) even though mobile data would reach it.
- **While it isn't known whether the server is there, the car is shown the kept library as
  it is shown online**, so a folder of something that isn't on the phone can still be
  listed for those seconds; opened, it waits to know (four seconds at most) and is then
  empty.
- **What "changes sent" counts** is what the server accepted; one it turned down for good
  was not sent in any sense the person means.

### What the third hardening of the offline engine decided

The reviewer, asked a third time, still answered no, and part of what was found had been
made by the first two rounds. This round was told to simplify first. What changed is
described where it belongs above; what was decided, and what was taken out:

- **Taken out**: the reading of every sample of every original at download, with its
  running-time comparison and frame checksums (kept only where nothing else says a file is
  whole); the rule that a file's container must match its name; the re-reading of a
  download each time it had played to its end; the one-time re-reading of downloads from
  an earlier build, and the version kept with each download for it; the list of downloads
  "set aside" for the run; the deletion of a download on the spot when it failed in
  playback; the minute's "leaving alone" of a Wi-Fi that didn't reach the server; and, in
  the sender of held changes, the two flags that said whether a queued change had been
  written yet or refused.
- **An original the length the server stated and the size the library gives is Done.** The
  second round's decision to refuse a FLAC with a frame that fails its checksum is
  reversed for such a file: it is the server's own, and the player plays past it.
- **The server's own flaw is kept, not refused**: the same bytes twice, failing the same
  way, are downloaded and marked, with the flaw said. (The second round listed such a song
  as failed after three identical fetches.) "The same bytes" is their length and SHA-256,
  not the server's validator, which a conversion doesn't have.
- **A question about decoders that can't be put is a no**, and a no means the conversion:
  the first two rounds took it for a yes.
- **What the phone can decode is asked for the exact format**, which is stricter than the
  player itself: Media3's track selector will still *try* a format its renderer says
  exceeds the decoder's stated limits. A file a phone's decoder would in fact play, though
  it says it can't, is downloaded converted.
- **A change the phone won't write is not sent**, even with the server there (it used to be
  queued unwritten and sent). On a phone whose storage refuses a hundred-byte write, a
  like fails with its message and a play is lost with a line in Diagnostics.
- **Only codes 50 and 70 are final for a held change.** Any other error, and the change
  waits, a play for ever (a like or a rating was given up after a day: see "What the
  offline loose ends decided").
- **With Wi-Fi only on, the server is asked on the Wi-Fi**, and what the app as a whole
  knows of the server (asked on the phone's own route) decides nothing for downloads.
- **With Wi-Fi only on, the app's own unmetered network governs, not the job's** (above).
- **The lowest Android is 12.**
- **A Remove on the Downloads page removes all of the group**, arrived or not; a Cancel
  there (offered while what is shown is itself arriving) stops what is arriving and leaves
  what has arrived.
- **A download that needs a fresh copy is shown as on its way again**, and, with nothing
  fetching it (the fetch was cancelled), as "It wouldn't play, and needs downloading again"
  until Retry failed, or the next start, fetches it.
- **Left as it is**: with Wi-Fi only off, transfers still go out on the network Android gave
  the job and fall back to the phone's own route; a like held, the server's list fetched
  while it is held, and the app restarted before it is delivered shows the heart but not
  the song in Liked songs until it is delivered (put right in M3b: the held like keeps the
  song).

### What the offline loose ends decided

After the third round, an independent review left seven small faults, each fixed in its
simplest form (described where it belongs above). What had to be decided on the way:

- **A replacement's name** is the plain one with `.r` before the kind, and the next
  replacement goes back to the plain name; the part it arrives in keeps the plain name, so
  one left by an earlier run is carried on with.
- **A like or a rating is never given up**, whatever the server answers but 50 or 70.
- **"The whole list" for a list's own button** is the Downloads page's: its songs as
  recorded, and for an album every song of it downloaded or on its way.
- **"Nothing was cancelled"** is said for a cancel that could not be written down, beside
  the existing "Nothing was removed".
- **What a decoder's no is held to** is the Android version number (`SDK_INT`): a monthly
  security update that changes a codec is not noticed. A record from before this was kept
  is asked again.
- **A cover waits a minute** for the server to answer on a network that has just come; then
  it is not kept this time, as with no network.
- **One run of passing over** is the failures of the entries it passed to in turn; a song
  that has played, Play, or the person choosing another entry ends it.
- **The downloads' record answers for an album with no page kept** only when it has what
  was asked for: the song chosen, or for the album as a whole, every song of an album
  downloaded whole.

### What the design left open, and what was decided (M2b)

- **A list's download button offers removal** in a sheet rather than removing at once (Windows
  removes at once); **Remove all** in Settings asks first ("Remove all downloads?"), which
  Windows doesn't. Losing everything to a brushed button is costly where the music is for a
  drive with no signal.
- **A song that couldn't be downloaded is marked on its row** (Lucide's circle-alert, in the
  warm colour); Windows lists failures only on its Downloads page.
- **Downloads is a chip of Library** (the brief), and also a page (from the offline sheet,
  Home's "Show all" offline). The Playlists chip no longer has a Downloads row.
- **The Downloads page groups** as the car's tab does (playlists, albums, songs) with sizes;
  Windows' page is one list of songs.
- **Settings keeps Windows' sections as chips**, so Streaming quality is under Sound and
  Offline mode first under Storage, as on Windows, rather than in the order the brief listed.
  Its "playing now" line is the player's format line, not Windows' longer sentence.
- **The offline indication is a pill in the title bars**, as Windows' top bar has it, rather
  than a bar at the foot, where the notices, the home network's bar and the mini player are.
- **Held changes sent** is said in words of the app's own (Windows says only "Back online").
- **The account's server version** isn't shown under Account (Windows shows it): nothing keeps
  it yet.

### How it is built, and what was decided (M3b, the rest of browsing)

- **Changing a playlist** (`library/PlaylistEdit.kt`, a port of src/playlists.rs with its
  tests; `library/Playlists.kt`). A removal or a move names the songs themselves, which copy
  of a song included, and is carried out on the playlist as the server has it when it is sent
  (Windows' `edit_playlist`: read the playlist, remove by the positions the songs have now, or
  replace it with the reordered list). Additions go as Subsonic's `songIdToAdd`, renames and
  deletions as such, a new playlist as `createPlaylist`.
- **Offline: held, and sent in order, like a like** (*reversed in the M3b fixes*: see below; Windows has no offline editing,
  and fails with its error). Every change to a playlist goes through the account's one ordered
  sender (`Pending.PlaylistChange` in `held.json`), so playlist changes and likes, ratings and
  plays all keep their order, and a change made in a dead spot is not lost. Because a change
  says which songs it means, sending it later, to a playlist that has changed since, is as
  safe as sending it now: this is what made holding the right choice. **Only a new playlist
  isn't made offline** (the server gives it its id): "You're offline. A new playlist is made
  on your server, so try again when you're back online." What can be edited offline is what
  Library shows offline: the playlists on the phone.
- **What is held wins over what the server says** (*replaced in the M3b fixes*): a playlist is shown as the server last
  sent it with every change still on its way carried out on top, in order (`Playlists.onTop`,
  applied to every copy fetched, through `Library.playlistShown`). A fetch of a playlist and
  the sending of a change to it take turns, and a change is marked sent before its turn is
  let go, so no copy shows a change twice.
- **What can't be promised** (*no longer so: a change is now sent once*), as for plays: Subsonic has no way to make a change safe to send
  twice. Killed in the instant between the server taking a change and the phone recording it,
  the app sends it again: a move comes to the same thing; a removal of one of several copies of
  a song takes out another; songs added are added twice.
- **Refused** (the playlist deleted elsewhere: 70; not this account's: 50): the change is
  dropped, the playlist asked for again, and "That didn't save. …" said.
- **Someone else's playlist**: the owner is not the account, or Navidrome says `readonly`.
  It plays; its page says "This playlist belongs to <owner>. You can play it, but not change
  it." and offers no handle, no removal and no rename or delete (Windows offers them and lets
  the server refuse).
- **Undo**: the last removal or move, within a minute (Windows' rule), from the toast (eight
  seconds, Windows' `toast_with_undo`); it is another change, sent in its turn.
- **Reordering** is in the playlist's own order only, as on Windows: a playlist nobody has
  sorted opens newest added first (Windows' `list_sort`), so its handles appear once "Custom
  order" is chosen. The handle takes the touch at once (a resting finger is not a long press
  of the row). TalkBack has Move up and Move down on each row instead.
- **The warning before adding** asks the server for the playlist as it is now (the phone's
  copy offline); with Windows' words and choices, the cautious answer highlighted.
- **Recommended** (Windows' `playlist_extras`): under a playlist of the account's own only
  (one can only add to those), picked once when the page opens and not again while it stays
  open, so adding one doesn't reshuffle the rest; nothing offline.
- **Ratings** are shown where Windows shows them: the song's menu ("Rate", "Rated 3 stars")
  and Song details. Held offline; a rating held from an earlier run is shown after a restart
  (`Freshest` reads what is held).
- **Song radio and autoplay** (`playback/Autoplay.kt`): looked for when the last song of the
  queue *starts* (Windows looks near its end), so the music runs straight on. A song's radio
  is a one-song queue named "<title> radio" that autoplay fills whatever the setting; the
  queue's name is dropped when autoplay extends an ordinary list ("Next up"), kept for a radio.
  Nothing starts by itself after a pause or stop. A song tapped in search starts its radio.
- **Internet radio**: Radio is a row of Library's playlists (Windows has it in its sidebar)
  and a folder of the car's Library. A station plays from its own address; its name stays the
  title and the song it announces goes where the artist would. A stream that fails or ends is
  joined again after 1, 2, 4 and 8 seconds (three quiet minutes start the count again), then
  passed over; nothing is asked of the server about it. Offline, the Radio page says stations
  stream live and can't play.
- **Menus on cards**: a long press on an album, playlist or artist card opens its menu
  (Windows' right-click), as does the "…" on an album's, playlist's or artist's header and on
  each playlist in Library. Windows' "Export as CSV" isn't offered (no file to hand on a phone).
- **Selecting several songs**: a long press on any list of songs (a playlist, an album, Liked
  songs, the Songs page, search results, Recently played). Taps then choose; the bar in the
  title bar's place has the X (Back too), the count, Add to playlist, the heart, and Windows'
  several-songs menu. Chosen by place in the list itself; on the Songs page a newer copy of the
  library (every second during the first fetch) drops the selection, as its places mean other
  songs.
- **"Save queue as playlist"** (the queue sheet's heading): what is playing and everything
  after it, read from the service's own queue in the same process (`QueueNow`), not only the
  part the sheet shows.

### What the M3b fixes decided

A review of M3b found that holding playlist changes for later could spoil a playlist changed
elsewhere meanwhile, that a change could be sent twice, and that what a page showed could
be a copy the phone had written itself. These are the rules now.

- **A playlist is changed only with the server there, and a change is never kept for later**
  (`library/Playlists.kt`). Offline, or with the server not known to be reachable (a check
  must have succeeded: `reach.sure`), every change is unavailable and says so when tapped:
  "Playlists can only be changed while your server can be reached." (Add to playlist says
  it before its list opens; the drag handles aren't offered at all, as a drag can't say
  anything.) A change waits only in memory, behind the earlier changes to the same playlist,
  and goes with the process. *Why the reversal*: a change held for an hour is worked out on
  the playlist as it is an hour later, by which time the person has forgotten it and someone
  else may have rearranged the list; and while it was held, the page showed a copy the
  phone had written, not the server's. Windows never edits offline either. Nothing held in
  `held.json` was ever a playlist change on an installed build (0.3.0 had none), so there is
  nothing old to drop. Likes, ratings and plays are still held, as before.
- **One attempt.** A playlist change goes out on a client with OkHttp's retry switched off
  (`Subsonic.once`), so it is written once. It ends one of four ways: done; refused by the
  server ("That didn't save. …"); unconfirmed (written, no answer: "Couldn't confirm that
  change. Check the playlist.", and the playlist is read again); or provably never sent (the
  connection was never made, or the read before it failed: "Couldn't reach your server. The
  playlist wasn't changed.").
- *(Replaced in the second round, below: there is no queue.)* **A change that fails, or can't be confirmed, drops every change to that playlist waiting
  behind it**, unsent, reads the playlist again, and says so once. Undo is the reverse change,
  waiting behind the one it undoes, under the same rule. Remembering how to undo a change,
  queuing it, dropping changes on a failure, and taking the undo and queuing the reverse each
  happen under one lock, so a refusal however quick leaves no Undo, and a reverse never goes
  out without what it undoes.
- *(Replaced in the second round.)* **What a page shows is the server's copy with the changes still waiting on top**
  (`Playlists.shown`), worked out each time it is drawn and never written into the kept
  page: the kept page holds only what the server sent. A fetch and a change to the same
  playlist take turns (one lock per playlist); a done change leaves the waiting list only once
  the server's copy with it in has been kept, so it is never shown twice nor missing for a
  frame. The server saying a playlist is gone (70) takes its page and its Library row away,
  and says so ("That playlist is no longer on your server.").
- **The accepted window** (A5). Subsonic can't make a write conditional on what was read, so
  the playlist is read immediately before the write, with nothing between them but checking
  it is still the account's and working out positions (one pass along it). A change another
  device makes in that instant can be overwritten, or a removal can land on the song that has
  moved into the position. Windows has the same window. `PlaylistsTest.aChangeElsewhere…
  IsTheKnownWindow` holds the write after the read, changes the playlist, and shows the result.
- **Whose it is, positively**: a playlist can be changed only when the account is known to own
  it (the owner is the account, and it isn't `readonly`; *second round*: one whose owner isn't given is not the account's); checked in
  the menus and again on the copy read just before the write.
- **Adding**: the "already in this playlist" question is asked of a fresh copy from the
  server; Recommended's tick appears only once the server has the song, and Recommended is
  cleared when the server is out of reach.
- **A selection belongs to the list it was made on** (`rememberSelection`, by the list's
  identity): a refresh, another search answer, another order or find, another account, or
  leaving the page drops it, so what is done to it is done to the rows that were ticked.
- **Nothing starts music after the person stops it.** *(Second round: the counters are now one order, `Choices`.)* The player keeps one counter
  (`QueuePlayer.intent`) that every pause, stop, sleep-timer pause, loss of audio focus and new
  choice moves on, read on the player's thread; a station's rejoin and autoplay's songs each
  note it when asked for and act only if it hasn't moved. A rejoin is also asked for only
  while playing is wanted, and checked again when it acts: a station paused (by the person or
  the sleep timer) whose load fails afterwards is left as it is, neither joined again nor
  passed over. A station is also stopped when offline mode is switched on. (The rejoin's 1, 2,
  4, 8 seconds are unchanged.)
- **The collection menus** (an album's, artist's or playlist's Play, Play next, Add to queue)
  look the songs up away from the main thread and act on the main thread, only if nothing
  else was chosen and the account is the same (`PlayerConnection.afterLookup`); opened from a
  page, they use what the page drew. **In the car**, a station is chosen by its own id; one no
  longer there is "not found", never another lineup. The Radio folder is the stations alone
  (no "Shuffle": they are live), and a station playing shows the song it announces beneath its
  name (as the subtitle, which is what a car shows under a display title).
- **Touch**: the drag handles keep clear of the fast-scroll strip (the row ends where the strip
  begins, on any list long enough to have one); after a drop, the rows stay in the new order
  until the playlist has it, so a tap plays what is on screen.
- **Big lists cost what one song costs** *(the second round removed the matching by song and copy this bullet's last sentences describe)*: a drag is the playlist with one song moved (where it
  was, where it is), so a row crossed changes one number; taking out, finding and putting back
  many songs go along the list once; "Save queue as playlist" copies two arrays on the main
  thread and makes the songs off it; songs queued by hand are left for the player (`Drawn`),
  not written out and read back. Songs put back by Undo each go after a song that stayed
  (Windows: after the one put back before it), which is what lets them go in on one pass, and
  keeps them beside their own neighbour if someone added another copy of it meanwhile.

### What the second round of M3b fixes decided

Twice the reviews found the same shape of fault: a change described by the song and which copy
of it, queued, and carried out later on a playlist that was not the one the person was looking
at. Each round closed some cases and the next review found another. That idea came from the
Windows app (`src/playlists.rs`). **The phone departs from Windows here on purpose**: Windows
carries a change out on a playlist that has changed since, finding the songs it meant; the
phone refuses and asks the person to do it again on what they now see. The port of the
matching by song and copy (`library/PlaylistEdit.kt`) is gone.

- **On the playlist exactly as it was drawn, or not at all** (`library/Playlists.kt`). Every
  change that depends on what the playlist holds (remove, move, Undo, rename, delete) carries
  the copy it was drawn from: its songs in order, and its name. The playlist is read fresh
  just before the write; only if the two are the same is the change written, and then the
  rows the person chose are simply those rows. Otherwise nothing is sent, the page shows the
  fresh copy, and the person is told once: "This playlist was changed somewhere else. Nothing
  was changed here." A card's menu (from the list of playlists) has seen only the name, so for
  its rename and delete only the name must be the same. Songs added at the end go whatever
  the playlist holds (the "already added" question is asked of a fresh read; if that read
  fails, nothing is added and the person is told). A new playlist (including "Save queue as
  playlist" and a mix's "Save as playlist") has nothing to compare.
- **One change at a time, nothing waiting behind it.** While a change to a playlist is on its
  way, the playlist is busy: its drag handles are dimmed and do nothing, a recommendation's
  button is disabled, and every other change (Remove, the selection's Remove, Rename, Delete,
  Add, Undo) says "Still saving the last change to this playlist." and does nothing. There is
  no queue, so nothing to drop when one fails, and nothing carried out on top of a change
  that did not happen. The page is the server's copy, with the one change on its way shown as
  made.
- **After the server says yes, the page is what was sent**: the drawn copy with the change
  carried out is kept at once, not learnt from a read afterwards (which may not succeed).
  After an answer that never came, the playlist is read again to show what the server has.
- **Undo is the reverse of what the server confirmed**, offered only after it said yes,
  within a minute, on the playlist exactly as that change left it, under the same rule: if it
  is no longer that, nothing is sent ("Can't undo: the playlist has changed since."). With the
  server out of reach, Undo says so and is kept for later.
- **Nothing sends a playlist change twice, OkHttp included** (`Subsonic.once`): no retry on a
  dead connection, no redirect followed, and any reply but a plain success ends the call
  where it is (OkHttp would otherwise send again for a 503 with `Retry-After: 0`, a 408 or a
  421), taken as "nobody knows whether it was done". A Subsonic answer, "ok" or the server's
  own error, always comes as a success.
- **Gone, wherever it is learnt**: the server's error 70 on any read of a playlist (a page's
  refresh, the read before a write, a catch-up) removes its page and its row in Library and
  says so, decided in one place (`Playlists.fetched`). After a new playlist's answer never
  came, the list of playlists is asked for again.
- **The window that remains**: between the fresh read and the write, a change another device
  makes can still be overwritten (Subsonic can't make the write conditional). Nothing comes
  between the two but comparing them. `PlaylistsTest.aChangeElsewhereBetweenThePhonesReadAnd
  ItsWriteIsTheKnownWindow` shows it.
- **One order for the person's choices** (`playback/Choices`): a choice of music takes its
  place in it at the moment it is made (a tap whose songs are being looked up, a list played,
  songs queued, a start in the service, on the phone or in the car), and so do a pause, a
  stop, the sleep timer's pause, the sound taken away and offline mode switched on. Whatever
  acts later than it was asked for (a lookup's result, a start being prepared, a station's
  rejoin, autoplay's songs) acts only if nothing has taken a later place: the newest choice
  wins, and a stop beats everything before it. The phone's "chosen" counter and the player's
  start and intent counters were three; they are one. Autoplay takes its place when it starts
  looking, so a pause while it looks stops it.
- **A station has one thing retrying it, and it gives up.** Only the service's rejoin (1, 2,
  4, 8 seconds) tries a station again; Media3's load policy (`RideOut`) leaves stations alone.
  After the fourth rejoin fails too, the station is passed over as a song that can't be
  played ("Couldn't play this song"), and nothing tries it again until the person chooses it.
  One that played and drops later starts a fresh count of four. The general recovery for an
  ordinary song makes no try while the person has paused or stopped: Play tries.
- **Smaller**: an artist page's Play plays the albums as the page draws them (albums, then
  singles and EPs), and only if nothing was chosen meanwhile. The name sheet asks for its
  buttons to be brought into view when the keyboard opens, but in landscape they still sit
  under the keyboard (the bottom sheet's window doesn't make room for it): left open, with the
  keyboard's tick as the way to confirm.

### What the third round of M3b fixes decided

- **A playlist holding songs missing from the library is not changed by position**
  (`Playlist.hidesSongs`). Navidrome keeps the entry of a song gone from the library, leaves it
  out of the playlist's answer (and out of that answer's own count and length), but counts it
  in the list of playlists, in "remove at index" and in nothing it keeps when the list is
  replaced: removing the second row shown can take out the first, and a move or an Undo throws
  the hidden songs away for good (tried on 0.64.2: docs/TESTING.md). Every read of a playlist
  asks for the list too, at once (`Subsonic.playlistCounted`), and keeps the list's count with
  the page. Where it differs from the songs listed, the page dims the handles and says "Some
  songs in this playlist are missing from your library, so it can't be changed from here.",
  and a removal, a move or an Undo says it and sends nothing, checked on the page as drawn and
  again on the fresh read. Adding at the end, renaming and deleting go as before. A playlist
  the list doesn't have is taken to hide songs. *The Windows app removes and moves the same
  way and is exposed to the same fault.*
- **A card's change compares what the card showed: its name and how many songs** (Library's
  list shows both). If either has changed on the server, nothing is sent and the person is told
  ("This playlist was changed somewhere else. Nothing was changed here."), and the card is
  brought up to what was read. After the phone's own change, or a fresh read made for one, the
  list's row takes the new name and count too, so a card is never behind the phone's own
  changes. The count compared is the server's own (the list's, which counts songs missing
  from the library).
- **Which songs are already there is worked out on the read the add is made on.** The picker
  and a recommendation's button go straight to the add; the add reads the playlist fresh, as
  every change does, and works it out there. Not asked yet (none were there when they chose)
  and some are there now: nothing is added and the person is asked, with Windows' words. Their
  answer is a new add, worked out afresh: "Add new ones" adds those not in the playlist at that
  read (and none new means nothing sent, and "These are already in …" said); "Add all" adds
  all. The separate read before the question is gone.
- **After an add, the page is what the server says it holds.** An add shows nothing in advance
  (a song shown before the server had it could show twice while the change settled, and
  Navidrome says yes to a song it doesn't have and leaves it out). Once the server has said
  yes, the playlist is read again and that is the page; if that read fails, "Added to …" is
  still said and the page stays as it was until it can be read. A recommendation's tick means
  the song is in that read.
- **An Undo button is for one change.** A removal's or a move's toast carries the Undo the
  server's yes to it made (`PlaylistUndo`), not just the playlist. Tapped when that is no
  longer the last change the server said yes to on the playlist (another removal since, while
  the first toast was still showing), it does nothing and says "Can't undo: the playlist has
  changed since."
- **One confirmation, one action.** A sheet's choice is made once (`SheetHost`): a second tap
  on its button, or the keyboard's tick, while it closes does nothing. Every way a playlist is
  made goes through the name sheet, so one "Create" is one playlist; the same holds for
  "Delete" and every other sheet's button.
- **A confirmation is for what was on the screen when it was opened.** Every change takes the
  copy the screen was drawing at the moment its menu or sheet opened: a song's menu and the
  selection's sheet did already; the header's "…" on a playlist's page now takes the copy the
  page draws (with a removal on its way, the playlist without that song), not the kept server
  copy, so a Delete confirmed after that removal failed is refused rather than made on a copy
  the person never saw. While a change to the playlist is on its way, the header's Rename and
  Delete say "Still saving the last change to this playlist." and do nothing, like every other
  change. A drag takes its copy when the finger goes down (and, as before, a redraw with other
  songs stops it).
- **Accepted, and not put right:**
  - Between the phone's fresh read and its write another device's change can still land (the
    window written down in the second round; unchanged).
  - With Repeat all, a station that gave up (four rejoins) is tried again when the queue comes
    round to it: coming round to it is taken as choosing it again.
  - A playlist that was downloaded and has since been deleted on the server still shows offline,
    from the downloads' own record, until its download is removed.

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
- **A song's menu** is Windows' (`song_menu`), in its order and words: Play next, Add to
  queue; Add to playlist, like, Rate, Download (or Cancel download, or Remove download, as it
  stands); Go to album, Go to artist ("Go to <name>" for each credited artist when there are
  several), Song details, Start radio from this song; Remove from this playlist (a playlist
  the account can change) or Remove from queue (from the queue). A radio station has only
  Play next and Add to queue. Nothing that does nothing is shown (M3b).
- **Home**: the greeting, four quick tiles (two lines of two, as Windows on a narrow window),
  then Recently added, Recently played, Most played, It's been a while and Something
  different, each sideways. "Made for you" comes first of the shelves (M5b); "Artists you
  might like" comes with M7.
- **Library**: chips for Playlists, Albums, Artists, Songs, Genres and Downloads (Windows'
  sidebar order). Liked songs heads the playlists, then Recently played, then the pinned ones,
  then the rest by name. Albums have Windows' seven sorts
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
  how many albums couldn't be fetched, if any. "Popular" (the server's top five) and "Fans also
  like" (similar artists in the library) show when the server can say (it asks Last.fm), and
  nothing in their place when it can't or offline (M3b).
- **Search**: results as the words are typed (at once when the phone's copy of the library
  answers; 250 ms after the last key while the server still does; a newer search always
  replaces an older one), in Windows' order: the first four songs, then artists,
  albums and playlists, with the artists first when one matches the words better than any
  song does, judged by a port of Windows' scoring (`SearchRank`), and the playlists whose
  name has every typed word as the start of a word; chips narrow to one kind. Results, and
  what tapping one plays, belong to the words that found them, and so does a failure; rows
  are shown, and tappable, only once those words are the words in the box. Every change of
  words and every "Try again" is a request of its own (going back to words that failed asks
  again). It searches only while the app is in front. With nothing typed, "Browse by genre". Windows keeps
  no recent searches, so neither does the phone. A song chosen from the results plays the
  song's radio, as Windows does (M3b).
- **Toasts** are Windows': a pill in the text colour at the foot, three seconds, for things
  done ("Added to queue") and things that happened out of sight ("That didn't save. …" for
  a like the server refused).
- **Notifications** are asked for once, at the first download (by the shell, `Ui.askToDownload`,
  which is still there when the answer comes: a song's menu has closed by then), for the downloads' progress
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

How it is built (M2b):

- **Download marks on rows** (`RowMark`, `DownloadMarks`): a row shows where its song's
  download stands as Windows' `TrackRow` does (downloaded: the arrow in a circle before the
  artist; waiting or arriving: a bar in place of the length; couldn't be downloaded: a mark of
  its own in the warm colour, which Windows doesn't have), and offline a song that doesn't play
  is dimmed, can't be tapped, and is said to be unavailable. The downloads change twice a
  second while songs arrive; a page never reads them itself. Each row reads its own mark
  through `derivedStateOf`, so a tick redraws the rows whose mark changed and nothing else, as
  Windows marks only the row of the song that moved (`mark_download`). Measured: a 5,000-song
  list scrolls the same with every row marked as with none (TESTING.md).
- **A list's download button** is Windows' `ActionBar` button: the arrow; while any of it is
  on its way, a ring that fills (counting songs part-way) with a square to stop it and "12 /
  40" beside it; all of it downloaded, the filled arrow in the accent. Worked out
  (`ListDownload`) from the list's songs as a set, made once for the list. Tapped when all of
  it is downloaded, it **offers** to remove it (a sheet with "Remove from downloads"): Windows
  removes at once, but a thumb brushes a button more easily than a pointer.
- **The Downloads page** (`DownloadsContent`: Library's chip, and a page of its own) is
  Windows' `show_downloads` grouped for a phone, like the car's Downloads tab: the summary
  ("12 songs, 340 MB, and 3 more on their way"), play and shuffle, and while a batch runs its
  ring; "Downloading" with the batch's line ("Downloading 3 of 12", or why it waits: Wi-Fi, a
  connection, offline mode, the server), a bar, Cancel all and each song with its bar and
  Cancel; "Couldn't be downloaded" with Windows' line, Retry failed and each song with its
  reason; then Playlists (downloaded whole), Albums (anything of them downloaded) and Songs,
  each with the room it takes (`DownloadsView`). **Everything waiting and everything that
  failed are folded to their first five**, with how many more and a way to open them
  (`Folded`): five thousand waiting songs were five thousand rows ahead of the music. The
  grouping is made again only when what is downloaded changes, never for a song's progress
  (`DownloadsViews`). All from the downloads' own record: it opens at once with no server
  from a cold start. A playlist or album opened from it is the downloads'
  copy (`Route.Downloaded`, `DownloadedListPage`), which needs neither server nor kept page;
  an album, playlist or Liked songs page opened offline with no kept copy falls back to it.
  Played from there, a list is named `downloads`, `downloads:album:<id>` or `downloads:<list>`,
  and the service reads it from the downloads' record (`DownloadedViews.songsFor`), as the car
  does, rather than 5,000 songs being written across to it.
- **Offline, visibly** (`OfflinePill`): Windows' pill in the warm colour, in every page's title
  bar and on Now Playing, "Offline mode" when switched on by hand, "Can't reach server" when the
  server stopped answering. Tapped, a sheet says what it means and offers the way out (Turn off
  offline mode; Try again) and Downloads. It sits at the top so it never competes with the
  notices, the home network's bar or the mini player at the foot; while the home network's bar
  is up (which says why nothing answers), the pill doesn't repeat it. Pages don't each show a
  "can't reach the server" strip while offline (the pill says it once); a page with nothing
  kept says it isn't on this phone. **Home** leads with "Your downloads" (the lists and albums
  on the phone) and keeps only albums with something that plays; **Library**'s Albums, Artists
  and Genres are those of the songs that play (`OnThePhone`, which the car's tree uses too),
  its playlists every one downloaded whole (from the downloads' own record, whether or not
  the server's list of playlists is kept or has it) and those of the kept list with a song
  that plays; **Search** looks only in the songs that play (`LocalSearch`) and says so, and
  lists the same playlists; a genre shows its albums on the phone. **Everything shown
  opens, from what is on the phone**: an artist's page offline is their albums with
  something that plays (and its play button their songs on the phone); an album with songs
  kept from being played opens with those; "More by" keeps only albums on the phone; "Show
  all" on a Home shelf is the albums on the phone, not the Albums page that pages through
  the server.
- **Coming back**: "Back online" when the server answers after being out of reach; offline
  mode switched off by hand and the server found there catches up the same way (Windows'
  `check_connection`), without saying so; if held changes were sent, it is said once ("Back
  online. The 2 changes you made offline have been sent to your server"). Pages that failed
  ask again, as before.
- **Settings** (`SettingsPage`) is Windows' `SettingsPage`: its sections as a row of chips
  (Appearance, Playback, Sound, Storage, Account, About; Requests and Jam come with M6 and M7),
  its `SettingRow` (name, what it does, the control at the end, or under the words when it is
  wide, a rule under it), `SettingHeading`, and its order and words, with only what works on
  the phone: the theme; Report plays to Navidrome; Streaming quality with Windows' words for
  each and what is playing now (changed mid-song, the song switches at once, as Windows'
  `reload_current`; not one playing from its download); Offline mode; Downloads (Download
  quality, Download on Wi-Fi only, Downloaded songs with Remove all); Kept songs (the space,
  with what is kept now, and Fetch ahead); the account and Sign out; the version, Diagnostics
  and Licences (the fonts' OFL, Lucide's ISC and the known media apps' Apache notice, in full).

How it is built (M3a, finding music):

- **The library on the phone** (`LibraryCopy`, a port of Windows' `search::Library` and
  `load_library`). Every artist, album and song of the account, fetched with `search3` and no
  words, 500 of each at a time, in the background; checked against the server's scan status
  when the account is taken on, every five minutes while the app is in use (its screens in
  front, or something playing), and when the server can be asked again; fetched again when
  `is_stale` says so (another number of songs at once; another scan with the same number after
  fifteen minutes; a day old, for the play counts, only on a network that isn't metered).
  Never while the server is scanning. A newer copy is fetched beside the one in use, which
  stays until the new one is whole. It belongs to the account (the account's cache folder,
  `library/`; its work stops with the account).
  - **Its form, in memory** (`SongTable`): a song is a place in columns. Numbers in arrays of
    numbers (track, disc, year, length, rates, plays, rating; size and times as longs; the
    four levelling values as floats), and every piece of text songs share (album, artist,
    their ids, the cover's id, format, genre, the list of who is credited) kept once and
    referred to by number. Only the id and the title are a song's own. A `Song` is made when
    one is wanted: a row as it is drawn, a result, the queue. Times are kept to the
    millisecond and given back in the standard form, not character for character (a time that
    can't be read is kept as it was). 50,100 songs come to 12.2 MB of the app's heap, and the
    search index to 1.8 MB more (TESTING.md, "The library on the phone"). Held as objects,
    the same songs are well over a hundred megabytes.
  - **Its form, on the phone**: one file, `copy.bin` (9.5 MB for 50,100 songs), written whole
    or not at all, with a mark and version at the start and a CRC-32 at the end; read whole
    (30 ms in a release build), checked once, and taken apart in memory (`Packer`,
    `Unpacker`), with every count and reference checked as it is read. A file that doesn't
    pass is as good as not there, and is fetched again.
  - **Ready while still loading; killed mid-fetch.** Each page is written to a file of its own
    as it arrives (`fetching/page-NNNNN.bin`), beside a note of the scan status the fetch
    began with (`began.bin`). The first time, what has arrived is published once a second, so
    the Songs page fills as it comes. Started again with the server saying the same, the fetch
    carries on from the first page it hasn't got (the pages it has are shown at once);
    with anything else it starts from nothing. A page that can't be read is fetched again, with
    every page after it. Whole, the pages become `copy.bin` and go.
  - **A server that lists nothing to an empty search** (not Navidrome) leaves a copy with no
    songs; that is not taken for a library, and search stays the server's.
- **Search on the phone** (`SearchIndex`, a port of `Index` in src/search.rs with its tests;
  `PhoneSearch`). Every distinct word once; every distinct name (an album's, an artist's, a
  title) once, as its words; each typed word compared with each distinct word once per
  keystroke, then everything rated from those comparisons with Windows' scores (`SearchRank`):
  forgiving a slip or two, the short-word rule, the name's start counting nearly as much as
  the whole word, a matching artist first (`artistFirst`, carried in `Found`), liked songs
  (the account's likes as they stand) 0.25 above others, equal matches in the library's
  order. The best so many of each kind are kept as they are found, not sorted from all.
  It answers the Search tab, the car's search and spoken requests (through `Library.search`)
  once the first copy is whole; until then the server does, as on Windows. Off the main
  thread; a search more typing has replaced is cancelled and stops part-way. **Offline** the
  songs that play are searched (`LocalSearch`) with the same index and scores: an index of
  those songs and their albums and artists, kept while they don't change (known by the songs
  themselves, every field: another account can use the same ids).
- **The Songs page** (`SongsContent`; Library's Songs chip, and `Route.Songs`): every song,
  newest first as Windows' `all_songs` orders them (when added, then album, then track),
  from the copy: there at once with or without the server. The page holds places, not songs:
  50,100 rows are an array of numbers, and a row's `Song` is made as the row is shown. The
  newest-first order is worked out once per copy, off the main thread. A song tapped plays
  what is shown, in the order shown, from it, by name (`PlayerConnection.playPage`): the
  service reads every song from the copy itself (`Library.keptSongs("songs")`), so 50,000 songs
  never cross from the screen. On its side, the count, the play button and the find box share
  one line, so the first view has songs in it.
- **Finding and sorting in a list** (`ListView`, `Arrange`: a port of `TrackView`; `FindAndSort`)
  on a playlist, Liked songs and the Songs page. Windows' find box ("Find in this list") and
  sort button side by side under the list's play button; the button opens the orders as a
  sheet ("Sort by": Custom order for a playlist, Default order otherwise, Title, Artist, Album,
  Duration, Date added), the one in use marked with a tick or the way it runs. Choosing the
  order in use turns it round; dates start newest first. Each list remembers its order in its
  `ListPrefs` under its route key ("playlist:<id>", "liked", "songs") with Windows' numbers and
  the time chosen, so the sync milestone can share them; a playlist or Liked songs nobody has
  sorted opens newest added first (`list_sort`). What was typed is forgotten when the page goes,
  as on Windows. A list of up to 600 songs is arranged as it is drawn; a longer one off the
  main thread, the list as it was meanwhile.
  - **Playing from a found or sorted list plays what is shown, in the order shown.** The
    command carries (or names) the list as the phone keeps it and how it is shown
    (`AppCommands.putView`); the service arranges it the same way (`queueFor`). Each song in the
    queue keeps its place in the list itself, so the row playing is marked in any order, and
    when the list changes the queue follows it in the order the list remembers (`orderOf`, as
    Windows' `order_for`).
  - **Date added**: a playlist's own notes (`Added`, a port of src/added.rs with its tests and
    file, `added.json`: every copy of a playlist read is looked at, and a song that turns up
    later is dated), else the day the song joined the library; Liked songs, when each was
    liked; the Songs page, the day each joined the library.
- **Recently played** (`RecentlyPlayed`): the last hundred songs that started playing, newest
  first, the same song starting again straight after itself not counted twice (Windows'
  `record_played`; recorded whether or not plays are reported). Kept per account in
  Windows' `history.json` form; `merge_history` is ported with its test for the sync
  milestone. Its page lists them, each playing again by itself when tapped, the song playing
  now left off the top as Windows' panel leaves it. Reached from Home's title bar (Lucide's
  history icon) and from Library, under Liked songs.
- **A song's details** (`SongDetails`, from its menu): Windows' lines, in its words (artist or
  artists, album, track and disc, year, genre, length, format with rate, bit depth and bit
  rate, size, levelling tags or "none", plays and when last, your rating, added to the
  library), and where it is on the phone: "Downloaded as" (Windows' line), downloading,
  waiting, kept from playing it, or not on this phone.

### What the design left open, and what was decided (M3a)

- **The library copy lives in the account's cache folder**, beside the kept pages: it can
  always be fetched again (about 4 MB over the air for 50,000 songs, compressed), and the
  system clears caches only when short of room, oldest first.
- **The daily refresh waits for a network that isn't metered** (Windows refreshes every day
  on any); a library that changed is fetched on any network.
- **While the first copy arrives, search asks the server** (it has everything); the Songs page
  shows what has arrived, with how far it has got. The brief's "search covers what has
  arrived" is met offline, where what plays is searched whatever the copy's state.
- **Offline search is the same index and scores over the songs that play**, not the copy's
  index filtered: it finds a downloaded song the copy doesn't have (removed from the server
  since), and works before any copy exists.
- **"The preview of best matches as you type"** is the Search tab itself, which now answers
  every keystroke at once: a phone has no search box over every page for a preview to drop
  from. Its "All" view is Windows' order (artists first when one matches best).
- **The sort control is a button that opens a sheet** of Windows' orders, beside the find box,
  as Windows' `SortButton` opens its menu; not a row of chips (six orders and a find box
  don't fit a 360dp line at 1.3 font).
- **Songs is a chip of Library**, between Artists and Genres as in Windows' sidebar, and a
  page of its own (`Route.Songs`) for anything that opens it.
- **Recently played** is reached from Home's title bar and from Library's list under Liked
  songs (Windows has it as a tab beside the queue).
- **The Songs page's header** is its count and the order shown ("50100 songs, newest first",
  "…, by title, Z to A": Windows' words and number form; Windows' page has only its own
  order) without a cover or a second title: the chip, or the title bar, names it.
- **A queue started from the Songs page doesn't follow a newer copy of the library**: a
  refreshed 50,000-song copy would be compared with the queue on the main thread. It plays on
  as started.
- **Details**: "On this phone" is the phone's own line (Windows has only "Downloaded as");
  the play count, last play and rating are the newest the app has seen (`Freshest`), not asked
  of the server again: of two things the server said, the one read later (a page, by when it
  was asked for; a newer copy of the library over pages read before it); on top, a play
  reported here until the server is seen to have it, and a rating made here until the sender
  says it settled (taken: then the server's word, which is that rating; refused: forgotten).
  A rating held for later settles in a later pass, and the sender says so then, however
  long that took (`HeldChanges.answered`, which tells the account for every change that had
  been held; M3b, item 8).

### What the finding-music fixes decided

- **A copy fetched while the library moved is used, and fetched again** (as Windows does):
  after the last page the scan status is read again, waiting five seconds at most; only if
  both readings succeeded and differ, or a scan is running, is the copy stamped out of date
  (count -1) and the next ordinary check fetches it. A second reading that fails or is slow,
  or a song that came twice, marks nothing (small fixes, item 5). No refusing, no retries, no comparison with the server's count. Nothing is
  fetched while the server scans. A copy Android cleared is written again from memory; a
  resumed fetch uses only pages kept under its own scan. Whether there is another page is
  decided by how many the server returned (kept with each page), not by what is left once a
  song twice is set aside, so a resumed fetch never ends early.
- **A tap hands over what was drawn** (changed by the small fixes, item 1): the screen leaves
  the very list its rows were made from and the order it drew them in (the packed table, or
  the page's list, and the rows' places) in a holder in the process (`playback/Drawn`: a few
  slots, each taken once, nothing written), and the PLAY carries its number with the find
  text and order, the song tapped and its place. The service, in the same process (no
  `android:process`), takes that object and builds the queue from it: a row from the song
  tapped, the play button from the top row (anywhere, shuffled). Nothing is read again, so a
  refresh or the first copy arriving between the tap and the start changes nothing. Rows of
  an arrangement that a newer one is replacing play what they show, in the order shown. Only
  if the number isn't there (the process was started again in between) is the list read
  again, as before: the arrangement worked out again, from that song wherever it now is, and
  if it isn't there, nothing plays and the person is told ("That song isn't in the list as
  it was shown any more"), never a neighbour. A queue from a narrowed list follows its list
  narrowed the same way (Windows' follows it unnarrowed), except the song playing, which stays
  where it is and goes on playing even if it no longer matches; offline, as on Windows, a song
  joining the list joins the queue only if it plays; the saved queue keeps the find text
  in a field of its own (`list_filter`) Windows reads past.
- **A queue holds places, not songs**: a list from the copy is places in its table
  (`PlacedSongs`), a saved queue is read back into a table of its own, and the queue's play
  order, entry numbers and places are arrays of numbers. A song is made only for what is
  shown or played. The saved queue's file is Windows' form, written a song at a time.
- **A refresh holds the copy as far as it has got**: pages go into the packed table as they
  arrive, what is shown while the first copy arrives shares its arrays, and the file is
  written through a buffer.
- **Search folds as Rust does**, a code point at a time (each lower-cased alone, kept if Rust
  calls it alphanumeric); playlists found are listed pinned first, then by name.
- **A spoken request is never turned down for what search matched**: a match on search's
  terms (`SearchRank.rate`) is worth at least the voice scorer's cutoff; the voice scorer
  chooses between kinds. Playlists are judged on the same terms. The car's searches of the
  phone put liked songs first. A request that names an artist as well as a song or album
  ("Duke by Paper Lanterns") is never given one by another artist; with nothing by them that
  matches, it plays that artist's music if the artist is known, else what the words as said
  find (small fixes, item 6).
- **Long lists have a fast-scroll handle** (`ui/parts/FastScroll`, Compose only): shown while
  the list moves, draggable then, a bubble with the letter of the sort, the month songs were
  added, the year or the place. Only the handle itself takes touches, and only while it
  shows: the edge it moves in is the list's (a row's "…" under it opens), and all of it while
  hidden. A drag's last movement is applied before the release. For TalkBack it is an
  adjustable position in the list (up and down in steps of a twentieth), saying the bubble's
  label.
- **Sorted by date added** (and the Songs page's own order), a row shows its date where the
  length goes, as Windows' date-added column writes it (Today, Yesterday, "3 Mar 2024").
- **In large type** (font scale 1.3 and up) a title on the Songs page may take two lines.
- **The find box takes at most 200 characters**, the same limit as what is handed to the
  player. **Dates added** are kept in `playlist-added.json`, Windows' name (an `added.json`
  from before is moved there). The packed writer refuses what its reader would.

How it is built (M4a, sound and lyrics):

- **One audio processor of ours** (`sound/SoundProcessor`) does volume levelling, the
  equaliser and mono, in that order, as Windows' chain has them (the gain in `feed`, then
  `Effects::process`). It sits in Media3's own sink (`DefaultAudioSink`) as the first of the
  processors the sink is given, which Media3 runs after its own trimming (the encoder delay and
  padding that make joins gapless) and channel mapping, and before the debug builds' capture
  (`DebugAudio`), so a capture is what reaches the output. No renderer of our own, no second
  sink, and nothing ever signalled to the sink.
- **Always in the chain** (the fixes after the review, rule 1). It is installed from the start
  and stays. With levelling, the equaliser and mono all off and nothing ramping, it hands on
  exactly the samples it was given: the cost is one copy of each buffer, and the recording of
  a song with everything off is the file sample for sample (on Android 12 and 17). A setting
  switched on or off takes effect at the next buffer, with its ramp. **Why**: it used to leave
  the chain when off, and to come in or go it had the sink drain and flush its chain mid-song
  (a discontinuity, `SoundSink`). That drain went through Media3's trimmer as if the song had
  ended: just before a padded song's last short buffer it let the padding the trimmer was
  holding back out into the join (a test gave 8,400 frames where 7,900 are right), and it set
  the sink's clock again at the next buffer. Strict bypass was not worth a risk to gapless
  joins and the position, for a path that sounds the same.
- **Which song, from Media3, and the gain at the join.** At each flush the sink hands its
  processors the timeline and the period that comes next (Media3 1.11's `StreamMetadata`).
  The processor reads the song from that period's media item (ours carry it,
  `QueuePlayer.SongTag`) and the songs either side of it in ExoPlayer's playlist, which is the
  queue in playing order, and works out the gain (`Levelling`, Windows' `level_gain` and
  `in_album_order` with their tests), **at every flush**: a song played again takes what is
  known of it by then. At a join Media3 configures the sink for the second song before its
  first buffer, and drains the first song through the chain before it flushes for the second;
  so the new gain begins on exactly the second song's first sample (`CompressedJoinTest`:
  two padded MP3s through the app's renderer, each song's frames at its own gain from first to
  last). Inside an album played in order, in Auto or Album mode, both songs have the album's
  value: nothing changes at the join. **The limit, accepted**: the period is the one Media3's
  renderer is *reading* when the decoder's output changes format, and a decoder gives its
  output back some frames late, so a song shorter than what the decoder holds at once (tens of
  milliseconds) has been read to its end before its first sound comes out, and takes its
  neighbour's level. Marking each format with the period it was read from fixed that, but it
  sat in the renderer that keeps albums seamless and could stop playback after a queue edit
  (the period it named could be gone from the timeline): not worth it for songs no real album
  has, so it was taken out again.
- **What eases is the setting.** A change the person makes eases in over 50 ms, and what
  moves is the setting itself, something that means the same for every song, never one song's
  gain against another's: levelling as a share of each setting it is moving between (the new
  one coming in, its share rising to 1, the ones before it giving way in proportion, so a
  change of mode or of the untagged amount eases as well as on, off and the level), mono's
  share, the preamp and each band (in steps: a full 24 dB swing in 50 ms, the coefficients
  worked out again each 32 frames while they move). The gain heard is worked out from them
  for the song sounding. At a join the song changes, its gain under each setting is worked
  out, and the shares carry on from where they were; at a change of format the equaliser's
  coefficients are made again at the bands as they are and everything easing carries on. Only
  sound that begins with nothing before it (after a reset) has everything in place at once.
  (The last round's ramp was the ratio of the old gain to the new one, kept for the song
  playing: put under the next song's gain at a join it could start that song several times
  too loud.)
- **The filters' memory follows the sound** (rule 3): kept only when the sink drained
  everything through before the flush, which is what it does at a gapless join; cleared at any
  other flush (a seek, another song chosen, a new queue, a reset), so nothing of the sound
  before a seek rings into what follows. Repeat-one comes as a join like any other song's.
- **Settings reach it as one value** (`Sound`): made on the main thread from Settings and
  shuffle (Auto goes by song when shuffled), never changed, swapped in whole through one
  volatile field; the audio thread notices a new one at the next buffer. Nothing is made on the
  audio thread while it runs: filters and buffers are made when the format changes (an output
  buffer grows if a larger block arrives, once), and the song is looked up at the flush that
  begins it. Its own timing is a debug build's only.
- **Clipping** (rule 2). The gain is held to what the song's peak allows (Windows: Quiet and
  Normal never push a peak past full scale, Loud by up to 3 dB). Whenever a sample may have
  been pushed past where it was (the gain in force above 1, or the equaliser running), it goes
  through Windows' soft limiter (`soft_clip`, untouched below 0.8 of full scale), whole,
  whatever is ramping: never faded in or out, never blended with the unlimited sample. When
  neither, nothing can go over full scale and nothing is rounded off. Normal and Quiet never
  push a peak past full scale, but they can round one a little: a quiet song they turn up has
  its gain above 1, and a raised peak above 0.8 of full scale goes through the limiter's knee,
  as on Windows (the setting's words, Windows' own, say only the first). (The lead's rule was
  "always on"; on sound that nothing has raised the limiter could only round off peaks that
  Windows leaves alone, so it is on whenever anything could raise one.) What it costs: at the instant the gain
  first rises above 1, or the equaliser starts, a sample already above 0.8 of full scale is
  rounded where a moment before it wasn't; Windows switches its limiter the same way.
- **Formats.** It takes 16-bit and float PCM at any sample rate and channel count, and hands
  back the same. Media3's sink gives its processors 16-bit unless the player's float output is
  switched on, which it isn't (it never was: 0.3.0 plays a 24-bit file as 16-bit), so nothing
  is converted that wasn't; the sample rate is never changed. Mono mixes all the channels into
  each (stereo: left and right each the average; for more than two the LFE too, left as it is).
- **The gapless trimming and the position** are Media3's and untouched: the processor neither
  adds nor drops a frame, reports no change of duration, and signals nothing to the sink.
- **Offload and the car.** Audio offload is not switched on anywhere (Media3's default), and
  stays off: Media3 runs no processor on offloaded audio, so with it on levelling, the
  equaliser and mono would silently do nothing. A car (Android Auto) is sent what the phone
  plays, after the processor, so it hears the same; nothing of the settings goes to it.
- **Levelling values** come from the server with each song (`replayGain`, in the library copy
  and on every page) and are kept with a download (its `Downloaded.song`), so a download levels
  offline. A song that came without them (one kept before they were) has them looked up in the
  phone's copy of the library, off the main thread (`PlayerSound.songsHeld`). Those values
  **belong to an account** (rule 5): gone the moment another account takes over, before
  anything of the new one plays, a look still going for the old one called off, and filled
  whenever the copy arrives or changes as well as when the queue does.
- **Cost.** On the Android 17 emulator, with levelling and the equaliser on, 6 to 8 ms of the
  audio thread's time per second of sound, 0.6 to 0.8% (debug builds log it under `NexusSound`;
  a phone's processor is faster); with everything off, one copy of each buffer.
- **Crossfade (M4b) is not built on this path.** One sink cannot sound two songs at once: at a
  change of format or configuration it waits for the old song's sound to drain through the
  chain before it takes the new one's, so holding the old song's tail back in the chain stalls
  the handoff, and carrying it outside the drain would need rules of its own for a true end, a
  seek, an error, a replaced queue, the sleep timer and formats that differ; and a count of
  frames skipped moves one clock for the whole player, which would move the session's item
  boundary while the old song is still heard. A crossfade needs something that owns both
  streams, their gains and a mapping of playout time across the overlap: a second player (whose
  sink would have its own `PlayerSound`), kept away from the session and audio focus, with
  `Levelling.inAlbumOrder` to leave an album alone and "End of this song" to suppress the
  overlap. Nothing is left in this path for it.

- **Streaming quality changed while a song plays** (as before, Windows' `reload_current`): the
  song is opened again at the new quality where it had been heard (the player stopped and
  made ready again, which keeps the queue, the place and whether it plays); measured, 48 ms of
  silence and the song carries on. A download, or a kept copy being played, is not fetched
  again. **Not built: a second quality for mobile data** ("On Wi-Fi", "On mobile data"). It is
  not small: what a song is streamed as is read in four places (where its bytes come from,
  whether a kept copy plays offline, fetching ahead, the format line), and each would ask the
  network as well, with the song playing kept to the form it began in. Left for the lead.
- **The sleep timer** (`playback/SleepTimer`, held by the `Graph`, so it carries on with every
  screen closed): Windows' choices (5, 10, 15, 30, 45 minutes, an hour, the end of this song,
  off) from the moon on Now Playing, in Windows' menu ("Pause music after") and words. It
  counts, and the music is paused, on the player's thread, in turn with everything the person
  does there (Off, another timer, Play): a pause goes ahead only if the timer that asked is
  still the one set. The pause keeps the queue and the place as any pause does, and counts as
  the person having stopped (the queue's one check of that, `QueuePlayer.intent` and
  `carryOn`): autoplay, a station's rejoin and repeat all at the end of a long queue don't
  start it again. "End of this song" means the song playing when it ends, however it ends:
  ExoPlayer pauses at the end of each song while it is set (`pauseAtEndOfMediaItems`), at the
  sample, whatever comes after; and a song that fails and is passed over (the service's one
  way of doing that, `passTo`) ends it too, the next lined up, paused. A song the person
  chooses takes the timer, as on Windows. While it counts, the moon is lit with what is left beside it ("12 min", "After this
  song").
- **Lyrics** (`lyrics/LyricsBook`, a port of src/lyrics.rs and the lyrics parts of
  src/playback.rs): from Navidrome only; synced where the server has them (and every line has
  a time, and the times are in order: Windows does nothing about times that aren't, and they
  are shown here as plain lyrics), plain otherwise, "No lyrics for this song" said plainly. A
  line is a place to seek to only inside the song's length as the player reports it (as the
  library has it where the player doesn't know; where neither does, no line is): one timed
  past the end is shown but not tappable. One result per song,
  which the view and fetching ahead both update: each opening of a song's lyrics shows what's
  kept at once and, with the server there, asks again, showing and keeping what it says if
  that differs; "couldn't ask" is never kept as "none". Kept per account. The song playing
  and the next five have theirs fetched ahead on Wi-Fi, the next one on mobile data, none with
  Data Saver on or offline (as songs are), the network looked at immediately before each
  request. Lyrics fetched for a download go through the same one result, so an open panel
  shows them. Kept in
  the account's cache folder, and for a downloaded song also in its data folder, where Android
  doesn't clear them: fetched as the download arrives, and for one already on the phone,
  whenever lyrics may next be fetched (the server back, the network or the Wi-Fi-only setting
  changing; with Wi-Fi only on, on Wi-Fi). Nothing of lyrics goes to the car. **On Now Playing** the lyrics button puts them in
  the cover's place: the line being sung lit, those sung dimmed, the view following the music
  a third of the way down; a tap on a line plays from it; scrolled by hand, it is left where it
  was put for four seconds after the finger lifts. Only the line being sung is worked out from
  the position (`derivedStateOf`), so the screen redraws when the line changes, not at every
  tick. With no lyrics, what's up next is shown beneath, as Windows' Now Playing shows it.

### What the design left open, and what was decided (M4a)

- **Presets of one's own** are saved (the same name again replaces it) and deleted, as Windows
  allows; Windows has no renaming, so neither has the phone (saving under a new name and
  deleting the old does it).
- **The equaliser's panel** keeps Windows' plot with a point per band; each band is a column a
  sixth of the panel wide (48 dp at 360 dp) to drag in, or to tap where the point should go (the
  alternative to dragging), in half-decibel steps as Windows; for TalkBack each is an adjustable
  control ("60 Hz band", "+3 dB") with Raise and Lower a decibel at a time. Its scale labels
  sit above and below the plot, not beside it, to leave the columns their width.
- **The sleep timer and lyrics** are buttons on Now Playing beside the queue (Windows has them
  in the player bar); lyrics take the cover's place rather than a page of their own.
- **A setting changed while music plays is ramped** (Windows applies the equaliser at once and
  starts the song again for a levelling change); a change of song is a step of its own.
- **The limiter is one, after the equaliser** (Windows has one after the gain and one after
  the equaliser), switched rather than faded (see "Clipping" above).
- **No dither** when a processed sample is put back into 16 bits: rounding, as Media3's own
  processors do; with nothing to do, every sample is handed on as it came.
- **Saved equaliser presets are the phone's**, with every other setting, not an account's: a
  second account on the same phone sees the same presets.
- **Known, and accepted** (the second round of fixes):
  - Switching "End of this song" off in the last few milliseconds of a song can lose the race
    with the player's own pause at its end, and the music pauses once: the switch reaches the
    player's own thread a moment after it is made, and the player may have paused by then;
    one press of Play carries on.
  - If a stream's timestamps jump mid-song and Media3 resynchronises, the equaliser's memory
    is kept across it and can ring for a moment: Media3 gives its processors no flush there
    (it is one stream), and a jump like that is a damaged file's, already heard as a break.
  - The limiter's knee is entered and left in one step when the gain crosses 1 or the
    equaliser starts or stops: only a sample already above 0.8 of full scale moves, by a
    little, and fading the limiter in is what let samples over full scale during a ramp.
- **Offline, a song with no lyrics kept** says so in words of the app's own ("None are kept on
  this phone, and your server can't be asked while you're offline"), not Windows' "Your music
  server doesn't have any for it", which would not be known.

How it is built (M5b, the log of plays, Your stats and the mixes):

- **The log of plays** (`store/PlayLog.kt`, a port of src/plays.rs with its test, in its file
  form: `plays.log` in the account's data folder, `["<song>",<seconds>]` a line, with the
  playlist's route key as a third field for a play made from one). **One place decides "this
  was a play"**: `PlayReporter` (half the song or four minutes; an internet radio station
  never, as Windows' `radio_url.is_empty()`: it used to be reported to the server after four
  minutes; nor is it told to the server as "now playing", though it stays in Recently
  played). What it reports goes to the log always and to the server if plays are reported,
  from one place, one straight after the other (`AccountGraph.played`, under a lock the
  account's `close` takes too): signing out can't come between them. Where a play was made from is taken as its
  queue entry begins: the queue's source if it is a playlist and the song is one of that
  list's, not queued by hand (Windows' `tick`). With no file it starts empty. (Windows
  started its log from Recently played; on the phone Recently played holds every song that
  was started, even one skipped at once, so a phone upgraded from 0.3.0 would have counted
  those as plays in Your stats. `AccountPlaysTest.recentlyPlayedIsNotTakenForPlaysThatCounted`.)
  Bounded as Windows bounds it (200,000; past 205,000 the oldest go and the
  file is written whole, and the names only the oldest used go with them). **Its interface**
  is Windows': `add`, `absorb` (plays from other devices: a play is a song and a second, taken
  in at most once, whether offered twice or already held anywhere in the log), `shared`,
  `sources` (one per song and second, the last noted, as Windows' map keeps them),
  `sharedWithSources` (the two from one moment, for syncing), `state` (the same as one value
  that shares the log's arrays, `Plays`, for whatever reads every play), and `close`.
- **Written safely.** Appended off the main thread, in order: each play has a number, the file
  is known to hold up to a number, and whatever write runs next writes what is missing, so
  none is written twice or skipped. Lines are synced. **The log knows the file's length up to
  its last record known to be written, and every append first cuts the file back to it**: a
  write that failed part-way (a full disk) or whose sync failed is taken back, not written again
  after itself, and `flushed()` says that it failed. A record is a whole line; a last line with
  no newline is one if it reads as one (Windows' reading takes it) and gets its newline before
  the next line, and one that doesn't read is cut off; opening and appending go by the same
  rule. A file that is there but can't be read is never written over: that run's plays are kept
  in memory and Diagnostics says so. **One owner of the file at a time**: a log holds the file's
  lock from opening to closing, and closing writes everything it holds before letting the file
  go, so signing straight back in to the same account reads the file only after the old log is
  done with it, and a closed log never writes again (`AccountGraph.close` closes it). What is
  in memory goes with the account (`AccountGraph.playLog`).
- **The mixes** (`library/Mixes.kt`, a port of src/mixes.rs with its test): On repeat (from
  Recently played, as Windows: what was played twice or more in thirty days), Your top songs
  (the server's counts), Rediscover, the two genres played most, and mixes of one's own made to
  rules (Windows' `Rules`, `PLAYED`, `ADDED`, `ORDERS`, `SIZES`, the summary in its words). **The
  day's shuffle is Windows' own**: fastrand 2.5's generator (wyrand and its way of taking a
  number below n) reproduced bit for bit, seeded with the UTC day and the mix's id as Windows
  seeds it, so the phone and the PC show the same mix on the same day when they know the same
  plays. Chosen from the copy of the library on the phone, read by column without making a
  song (new accessors on `SongTable`), sorted as places with a stable sort of its own that
  boxes nothing (`IntSort.kt`). `MixBook` (`AccountGraph.mixes`) works them out again off the
  main thread whenever the copy, the likes, Recently played, the rules or the UTC day change;
  null until there is a whole copy. Until the whole of Liked songs has been heard (`Likes.known`),
  a song is liked if the copy says it is starred, as Windows' `is_liked` falls back to the song's
  own star: offline with no kept Liked songs page, liked-only rules and Rediscover still work.
- **Mixes of one's own** (`store/RuleMixes.kt`, `rule-mixes.json` in the account's data
  folder, in Windows' `Rules` form): each change written atomically and durably before it is
  shown; a change the phone won't keep is not made, and said ("That didn't save…"); a file
  that can't be read is set aside, not written over. Not shared between devices: Windows
  keeps them with its settings and doesn't.
- **Home** puts "Made for you" first of the shelves, filled in when the mixes are ready (Home
  never waits for them). **A mix's page** (`Route.Mix`, `ui/mixes`) is a list of songs like
  any other: played and shuffled from it, its rows marked as the queue plays them (its source is
  Windows' `mix:<id>`), its "…" Windows' `mix_menu`: Save as playlist (through the playlists'
  own way, `askNewPlaylist`: only with the server there), and for a mix of one's own its rules,
  Rename and Delete mix, each rule a sheet of Windows' choices or a question in its words.
  Library has "New mix from rules" (Windows' "+" menu beside Playlists).
- **Your stats** (`library/Stats.kt`, a port of `stats_span`, `stats_counts`, `stats_filters`
  and `stats` in src/pages.rs with its test; `Route.Stats`, `ui/stats`), from Library's list
  (Windows has it in its sidebar under Radio). Windows' chips, the finer choices under All time
  (Everything, the last 3, 6 or 12 months, each calendar year the log reaches into), the four
  figures, the note on where the numbers come from, Top artists, Top songs (playing as "Your
  top songs"), Top albums, Top genres, Top playlists. All time is the server's counts in the
  copy plus what the log has since that copy was fetched; the rest is the log. Days, weeks
  (from Monday) and months by the phone's clock. Counted into arrays by place in the table and
  by the log's number for a name, not maps of boxed counts. From the phone alone, so the same
  offline (a song that doesn't play is dimmed, as anywhere). A ranking carries the copy of the
  library it was worked out from (`Stats.Page.table`), and the page draws it against that and no
  other, so a newer copy arriving can't pair one ranking's places with another table.
- **In the car**: under Home, after Liked songs, a group "Made for you", each mix a folder
  (`mix/<id>`) of Shuffle and its songs, playing the mix from the song chosen; nothing asked
  of the server. Offline a mix lists what plays, each song keeping its place in the whole mix,
  and one with nothing that plays isn't listed. The car is told to look again at Home only
  when what the row shows changes, and at an open mix only when what it holds does (its songs'
  ids, and offline which play), looked at whenever the mixes, being offline or what is
  downloaded change. Stats don't go to the car.
- **Cost** (Android 17 emulator, debug build, 50,120 songs; `DebugControl --es library
  mixes`): every mix (four of one's own among them) 17 ms, allocating 2.9 MB; the slowest
  stats period with a full 200,000-play log 50 ms, allocating 2.9 MB (before the arrays, 146
  ms and 9.5 MB). See TESTING.md.

### What the design left open, and what was decided (M5b)

- **"Newest first" in a mix of one's own compares the time, to the millisecond**; Windows
  compares the server's text. The two agree except between songs added in the same second
  that the server wrote with different numbers of decimals (Navidrome drops trailing zeros),
  where Windows' order is the text's, not the time's. The phone's copy keeps times, not their
  text, so matching it would mean changing the copy's file.
- **Offline, a mix is the day's mix with only what plays**, each song in its place, not a mix
  chosen afresh from the songs on the phone (the brief allowed either): the same mix as on the
  PC, less what can't be played; a mix with none that plays is left off Home and the car.
- **Your stats is reached from Library's list** (beside Liked songs, Recently played and Radio),
  as Windows has it in the sidebar; its chips are page state, not pages of their own, and
  scroll with the page.
- **The playlists' names in Your stats** come from the account's list of playlists as the phone
  keeps it, so they show offline too (Windows asks the server and does without them offline).
- **The note's date** ("which start on 1 November 2026") is written in English whatever the
  phone's language, as the rest of the app's words are.
- **A day that begins in a summer-time gap** begins at its first moment (Windows' `day_began`
  would give 1970 there).
- **Deleting a mix of one's own** goes back from its page (Windows goes Home), with Windows'
  toast and no question, as on Windows.
- **One sheet opening another** (a menu's "Save as playlist") used to open hidden: every sheet
  now has its own bottom sheet state (the shell keys its host on the sheet).

Known, and accepted (the M5b fixes):

- **A play older than everything the log still holds** (taken out at its limit) is not known,
  and is taken in again if another device offers it again, as on Windows: the log can't
  remember what it has let go.
- **A kill of the process between a play's line in the log and its change for the server**
  leaves it in one and not the other: making the pair one write would need a journal of
  counted plays, which the rare window doesn't pay for. And a play whose answer from the server
  was lost is still sent twice (so before this milestone too: the Subsonic interface has no way
  to make a submission safe to repeat).
- **A mix opened in the car just before midnight (UTC) and tapped just after** plays the song
  tapped with the new day's mix after it, and a song that has left the mix plays by itself: the
  folder is the day's mix, and a car's tap names a song, not a day.
- **Restarting the app part-way through a song starts the count of what was heard again**: the
  count lives with the player, as before this milestone; a play straddling a restart may not
  count.
- **Every song that starts works the mixes out again** (16 ms off the main thread, for On
  repeat's sake, which follows Recently played): not worth splitting the mixes by what each
  depends on.

How it is built (M5a, in step with the person's other devices):

Everything the phone had written to the server before this was the phone's own. This writes
into a record the Windows app on the person's PCs reads and trusts, where the newer value wins
on every device: a phone that puts "now" on something the person did not change overwrites
the real thing everywhere, and the PCs cannot defend themselves. So the rules below come
before any feature, and each has a test that fails with the rule's code taken out
(docs/TESTING.md, "In step with other devices"). They are as they stand after an adversarial
review against the Windows code (the M5a fixes); the letter after a rule is its item there.

**Switched off in this build** (`sync/InStepBuild.kt`, one constant, `EXISTS = false`). Two
reviews judged it not yet safe to let write into the record the person's PCs trust, and its
rules are to be rewritten once, in Rust shared with the Windows app, which will replace the
Kotlin logic here. Until then nothing of it runs on a phone. With it off the setting is not
shown; the account has no exchange and no shared queue (`AccountGraph.step` and `queueStep`
are null), so nothing starts one, schedules one or asks the server anything for one: the sync
playlist is never looked for, read, made or written, and the server's saved queue is neither
saved nor read; "Continue here" is never offered. What stays on, because it protects the
person whether or not the feature exists: the sync playlist is never listed, played,
downloaded or edited on the phone, and its name is reserved. The pins, each list's choices
and the theme are plain local settings: what the phone shows is the settings' own, at once,
and kept across a restart. Their changes waiting to be shared are never shared, and so never
forgotten, but they do not grow without end: one for each playlist ever pinned or unpinned,
one each for a list's shuffle and its sort (lists Windows also has), and one for the theme,
the last word on each. When the feature is switched on they are what goes out at the first
exchange. `SwitchedOffTest` holds all of this; the feature's own tests switch it on for
themselves (`InStepBuild.forTests`), so its code goes on being tested.

- **What is shared, and where** (a port of the Windows app's src/sync.rs). The pins, each
  list's shuffle and sort, the theme, when songs were added to playlists, Recently played and
  the latest 500 plays live as text in the comment of one playlist, "Nexus Music sync"
  (`sync/Shared.kt`: Windows' `Shared`, field for field). The play queue and the place in it go
  through the server's own saved queue. Both under one setting, "Keep my devices in step"
  (Settings, Account: where Windows has it, in Windows' words; on unless switched off). Off,
  the phone neither reads nor writes any of it, and makes no playlist.
- **One function puts the two records together** (`sync/Settle.kt`: `Settler.settle(server
  text, this phone's record as text, now)` gives the record to hold and whether to write it).
  Nothing else in the app knows how it is done, so that it can be the Windows app's own Rust
  compiled into the phone app if that is decided: one object to replace. Behind it now is a
  Kotlin port (`sync/Merge.kt`), which is not trusted on its ported tests alone: `tools/instep`
  compiles the Windows source itself (by path, read-only) and writes what `merge`,
  `merge_history`, `merge_plays`, `merge_notes` and serde make of 93 texts and 51 pairs of
  records, and the Kotlin is held to every one (`InStepCasesTest`); the same tool reads back
  what the Kotlin wrote, and plays the PC in an end-to-end exchange.
- **Read neither more kindly nor more strictly than Windows** (`SharedText`). A general JSON
  reader differs from serde both ways: it takes `"12"` and `1e3` for a number, a field named
  twice, half a pair of escapes; and it would refuse what serde reads (a record written as a
  list of its fields in order). Either difference matters: a text the phone reads and
  Windows can't is one Windows would overwrite; a text Windows reads and the phone can't is
  one the phone would never exchange with again. So the reader is the app's own, written
  against what serde does and held to the Rust-written cases. Text is compared by its bytes,
  as Rust compares it (Kotlin compares UTF-16 units, which orders some characters
  differently), and a list's choices in Windows' order of fields. In a `\u` escape only the
  sixteen plain digits are digits, as serde has it (Java's own reading of a digit also takes
  the full-width ones and those of other scripts).
- **Written byte for byte as serde_json writes it**: the same field names and order, whole
  numbers, pairs and triples as lists, only the quote, the backslash and control characters
  escaped; a map's entries by key (Windows writes them in no particular order).

**The rules that keep the PCs safe.**

1. **A change made on this phone is kept as the change, and becomes a value with a time on
   it only at an exchange, on top of what the server has then** (A1). Between devices each
   item (the pins, the theme, one list's choices) is a whole value and a time, and the newer
   wins everywhere. A phone that has not read what the PCs have (new, offline, its first read
   still on its way, sharing just switched on) holds none of it: if pinning one playlist
   there made "the pins" the list of that one, stamped now, every PC's pins would become that
   one; and a shuffle tapped before a list's choices had arrived would go out with a sort of
   nothing under one time, and the PC's sort for that list would be gone. So the settings
   hold three things (`store/Settings.kt`):

   - `agreed`: the pins, theme and lists as last agreed with the server, with the record's own
     times. This, and nothing else, is what the phone offers of them to the merge
     (`Sharing.choices`).
   - `waiting`: what the person changed here and has not yet been shared, as the change:
     "pin this", "unpin that", "shuffle on (or off) for this list", "this sort for this
     list", "this theme" (`sync/Changes.kt`). The last word on one thing is the one that
     waits. They are saved with the settings: through the process being killed, being
     offline, and the setting being off.
   - `pinnedPlaylists`, `lists`, `theme`: what the screens show: what is agreed with the
     waiting changes on top (and the phone's own where nothing is agreed).

   At an exchange the server's record is merged with what was agreed, and then the waiting
   changes are made to the result (`Changes.onto`): the playlist is added to the pins the
   server has; the shuffle is set on the list's choices as the server has them, its sort left
   alone. Only an item a change really altered is given a time: the later of now and one
   second after the time it had, so it is the newer on every device whatever clock put the
   earlier time on. A change that makes no difference to what is there (pinning what is
   pinned) alters nothing and puts a time on nothing. Once the record is written, and only
   then, the changes that went into it are forgotten (`PhoneShared.take`); one made again
   meanwhile still waits, and goes with the next.

   Every place a time is put on something shared, and what reaches it:

   | What | Where the time is put on | Reached only by |
   |---|---|---|
   | the pins, the theme, a list's shuffle and sort | `Changes.onto`, at an exchange, on the server's value: the one place | a change that is waiting. `Changes.pin`: "Pin to top" / "Unpin" in a playlist's menu (`Menus.togglePin`). `Changes.theme`: a theme swatch tapped (`SettingsPage`). `Changes.sort`: the sort sheet (`Ui.rememberSort`). `Changes.shuffle`: a list page's shuffle button (`Ui.toggleListShuffle`); the shuffle button pressed while the list plays (`PlaybackService.queueChanged`, by `QueuePlayer.shufflePresses`: the app's, the notification's, a car's); "Shuffle" at the top of a list in the car (`PlaybackService.rememberShuffle`, by `PlayList.shuffleChosen`) |
   | when a song was added to a playlist | `Added.changed`, `Added.create`: now | the person adding to, removing from or making a playlist on this phone, once the server has taken it (`Playlists.send`) |
   | Recently played; the plays | `RecentlyPlayed.played`; `PlayLog.add`: now | a song starting to play here; a play that counted here |
   | the queue's own time | `PlaybackService.save` (`queueStamp`): now | the queue changed or played on here; never while one is put back, follows its list, or is taken from another device (`quietly`). The server puts its own clock on a saved queue; this time decides whether another device's is taken up, and whether this phone's is saved (below) |
   | what an earlier build left | `Belongs.demoted`: Windows' "long ago" (1); `Added`, reading its file: no time at all | an upgrade (rule 2) |

   One time is not shared: each list's `at` in `Settings.lists` is when its order was last
   chosen on this phone, kept only to tell whether a sort that arrives is newer than an order
   only the phone offers (below, "Taking in").

   Three places used to put "now" on what had merely been found different, and were changed
   when this was first built. **A list's shuffle** was stamped whenever the queue's shuffle
   and the list's remembered choice disagreed, which is also the case when the choice was
   changed on a PC, the queue was put back from the last run, or the app itself chose (a
   spoken "play <artist>" is shuffled): the phone would have overwritten the PC's choice at
   the next song. **When a song was added**: Windows dates what it notices in a playlist with
   "now" and shares it (`observe`), so a device that looks late overwrites the day the song
   really was added, on every device. The phone keeps what it notices for its own "Date
   added" in a note with no time on it, which is never shared and gives way to any dated note
   (`Added.sawCopy`); a dated note changes only by the person's own change here or by a newer
   one from another device. **The saved queue's time** was "now" at every save, a restore
   included.
2. **What the phone held before it could keep in step carries no weight** (A2). The released
   build before this (0.3.0) put times on things under no rule that survives: a playlist
   looked at twice dated what it had noticed with the time of the look; the pins, the theme
   and each list's choices were stamped; and the settings belonged to no account. The first
   time this build reads them (`Settings.stepMark`, and a mark in `playlist-added.json` and
   `in-step.json`):

   - **Notes of when songs were added** all lose their time: which of them were the person's
     own doing can't be told from the file, so every one becomes what the phone merely saw,
     kept for its own "Date added" and never shared (a note that only said "it went" says
     nothing then, and is dropped).
   - **Settings that belong to no account** stay the phone's own: shown as they were, agreed
     with nobody, in nobody's record. They are not given, with the times they carry, to
     whichever account is opened first (chosen under one account, they would have reached
     another's PCs as its newest choices). An account's own take their place on the screen
     item by item, as its record has something to say: the pins when the record has pins (or
     a time on them), a list when the record has that list, the theme when the record has one.
   - **Choices already under an account's name** (only the first build to keep in step wrote
     those, and it was never released) are offered as "long ago": they lose to anything
     chosen since on any device.

   Files as 0.3.0 wrote them are kept as a fixture (`app/src/test/resources/instep-upgrade`).
3. **Nothing it has not loaded.** No exchange until the choices in the settings are this
   account's own (`Belongs.ready`), and Recently played and the log of plays have been read
   from the phone for it (`AccountGraph.historyRead`; the notes of when songs were added are
   read as they are first asked for): `PhoneShared.record` gives nothing before, and the
   exchange then asks the server nothing. Notes are passed on for every playlist unless the
   list of playlists is known and not empty (an empty list is "not loaded", not "none exist").
4. **What the server did not say is not "empty"** (A3). A playlist is the account's only if
   the server says so: its owner stated, and the same (`InStep.isOurs`; Windows takes one with
   no owner stated for its own). With none known to be the account's, one of that name whose
   owner the server left out may be anybody's: nothing is made beside it, nothing written
   into it and nothing read out of it, and Diagnostics says so. That is also what protects
   the comment. Navidrome, told to say little to a client (`Subsonic.MinimalClients`), leaves
   a playlist's owner and comment out together; read as "ours, and empty", the record would
   look like one with nothing in it, and the phone's own would be written over everything
   the PCs have. A comment left out of an answer that *does* state the owner is an empty
   one: Navidrome leaves an empty comment out of every answer (seen on 0.64.2; the check
   against a real server fails if that ever changes), so a comment's absence alone says
   nothing, and a sync playlist just made and not yet written to could otherwise never be
   filled. And a record is made on the server, or an empty comment filled, only when the
   phone has something of the person's to put in it (`Shared.says`): a phone with nothing to
   say writes nothing and makes no playlist.
5. **Only what Windows understands.** What the phone has, against Windows' `ListPrefs` and
   its neighbours, field by field:

   | Windows' field | The phone has | Shared | Kept on the phone |
   |---|---|---|---|
   | `pins` (playlist ids, in order) | the same | as they are | — |
   | `theme` (one of Violet, Green, Ember, Ocean, Rose, Graphite) | the same six, by the same names | the name, once it has been chosen under this account or has come from its record | the look the phone had before any account's record said anything; a name Windows doesn't have (none today) is never written, and one the phone doesn't have is not taken in |
   | a list's key (Windows' route keys) | `playlist:<id>`, `album:<id>`, `artist:<id>`, `genre:<name>`, `liked`, `songs`, `downloads`, `mix:<id>` | under the same key | `downloads:album:<id>`, `downloads:playlist:<id>`, `downloads:liked` (a list as the downloads have it), `radio` (a song's radio; Windows' `radio` is its stations page), `mix:rule:<id>` (a mix of one's own: the id is this device's) |
   | `shuffle` | the same | as it is | — |
   | `sort` (0 Custom order, 1 Title, 2 Artist, 3 Album, 4 Duration, 5 Date added) | the same six, by the same numbers (`ListView`), on a playlist, Liked songs and the Songs page | 0 to 5 | anything else: a sort only the phone offers never waits to be shared, and the shared one is left as it was (none today: the guard is for an order added later) |
   | `descending`, `sort_chosen` | the same | as they are | — |
   | `at` | the record's own time for the item; for a change made here, the time it gets at the exchange (rule 1) | as it is | when the order was last chosen here (`Settings.lists`) |

   A list's choices taken in from the record are kept whatever their key and number (as
   Windows keeps them); only what this table allows is shared back.
6. **The phone writes only if it can write back everything it read** (A4). A record in a
   later layout is read and never written (as Windows). A comment that is not empty and is
   not a record is left exactly as it is, with a line in Diagnostics, and nothing is
   exchanged until it is one (Windows takes it for "nothing shared yet" and writes over it);
   only the empty text is "nothing shared yet", so a comment of spaces is such a comment.
   Fields this version doesn't know at the top of the record are written back, exactly as
   they were written, after the ones it does (Windows drops them). A field it doesn't know
   *inside* a list's choices or a note, where it has nowhere to keep it, makes the whole
   record one to read and not write (`Shared.more`, `Settled.ReadOnly`), with a line in
   Diagnostics: written back without it, a later version's addition would be gone from
   every device.
7. **Byte for byte**: see above, and `scripts/instep-check`.
8. **Nothing is shared while the phone's clock disagrees with the server's** (A5). The times
   on everything shared are compared between devices, so a phone whose clock is far ahead
   would put on one change a time no later choice on a PC could beat, push rightly dated
   plays out of the 500 shared, and (ninety days ahead) drop the notes that say a song was
   removed. Each answer's `Date` is compared with the phone's clock at every exchange; more
   than five minutes apart (`InStep.CLOCK_SLACK_SECONDS`), what the server has is taken in
   and nothing is written: not the record, and not the queue (nor is the saved queue looked
   at: whether another device's is the newer can't be told by a clock that is wrong). The
   changes wait. One line in
   Diagnostics when it begins, and under the setting, for as long as it lasts: "This phone's
   clock is wrong, so its changes aren't being shared." A server that gives no date can't be
   compared with and is taken to agree.
9. **Two exchanges never cross.** One at a time for an account in the whole process
   (`InStep.turnOf`, by the account's key: signing out and straight back in makes a second
   set of objects while the first may still be writing); one asked for meanwhile does
   nothing but ask for another when the first is back. What comes back is taken in against
   what is here *then*: a change made while the exchange was out is still waiting, stays on
   the screen, and goes with the next. When the account goes its work is cancelled; an answer
   that comes back afterwards changes nothing (the settings are no longer that account's:
   `PhoneShared.take`), and the choices themselves are put away with the account (below).
10. **What comes back is kept before anything is reported as done** (C1). An exchange goes:
    read; merge; the waiting changes on top; **keep** (`PhoneShared.keep`: the plays in the
    record go into the phone's log, and the log is made sure of on the disk, everything it
    holds in memory, at every exchange whether or not this one added any); write; **take**
    (the settings, the dates, Recently played; the changes forgotten). If the log can't be
    written the exchange has failed as a whole: nothing is written to the server on the
    strength of plays the phone could not keep, Diagnostics says so, and they are asked for
    again at the next. (Asked to write only when something was new, a failed write left the
    plays in memory, the next exchange found them "already here", and they were never
    written.) A play already in the log whose source arrives later is given that source,
    without being counted again (`PlayLog.absorb`).
11. **The playlist that holds the record** (A6) is the one with the lowest id (by bytes)
    among those of that name that are the account's own, looked for afresh at every exchange.
    Nothing is remembered between exchanges: a phone that went on using an id it knew, after
    a lower one appeared (two devices that each made one, having found none), would never
    meet the devices that use the lower one again. It is made only when the server has just
    listed the playlists and there is none, sharing is on, the server may be asked, and the
    phone has something to say (rule 4); with a request that is sent once. Its name is
    reserved: making a playlist of that name, or renaming one to it, is refused with "Nexus
    Music keeps that name for itself, for keeping your devices in step. Choose another."
    (`Playlists.reserved`). It is nowhere among the playlists: `Library.playlists()`, which
    everything that lists playlists reads (Library, the car, search, the picker, Your stats,
    spoken requests), leaves it out of what the server sends and of a copy kept by a version
    that didn't know of it; asked for by id it is "not found" (`Library.playlist`); the
    downloads' own list leaves it out; and `Playlists.canEdit` is false for it.

- **Whose the choices are** (`sync/Belongs.kt`). The pins, list choices and theme, with what
  was agreed and what is waiting, are in the phone's settings, where every screen reads them,
  and are shared into an account's record. So they belong to one account at a time
  (`Settings.sharedFor`): when another is taken on, before anything of it exists, they are
  put away with the account they belong to (`in-step.json` in its data) and its own brought
  out. The theme stays the phone's look: an account that has none of its own takes the look
  as it is, and has nothing to say about it to its other devices until one is chosen under
  it or comes from its record. Windows keeps all of this in its one settings file and shares
  it with whoever is signed in.
- **When an exchange runs on a phone, and what it costs** (C2). At start (once the account's
  stores are read); **each minute while the app's screens are in front**; when they come to
  the front (not within ten seconds of the last); when the server comes back or sharing is
  switched on; and three seconds after something shared changes here (Windows' `share_soon`:
  a pin, a list's choice, the theme, a song added or removed, a song starting). **The
  minute's timer itself runs only while the screens are in front** (`Graph.front`, set by
  `MainActivity.onStart` and `onStop`; the loop is cancelled when it goes false): with them
  gone nothing wakes each minute to find it has nothing to do. With music playing and the
  screen off the record still goes out as each song starts (a change), which also brings in
  what changed elsewhere, and nothing runs when nothing is playing; no wake lock, alarm or
  job is used. A look is three requests: the list of playlists (rule 11; what Navidrome
  sends for it has no songs in it), the record's playlist (the record is at most about
  40 KB, a few KB compressed; 1.6 KB for the test account's) and the saved queue, of which
  the server sends every song in full (119 KB for a hundred songs, 11 KB compressed as
  Navidrome sends it; about five times that for the 500 a long queue is cut to). The record
  is written only when the phone adds something. On the phone, an exchange reads only the
  last 500 plays of the log, where they lie (`Sharing.plays`): not a copy of the whole log.
- **The record's write goes beside `library/Playlists.kt`, not through it.** That is the way
  the person's own changes to a playlist go: queued behind their other changes, shown on the
  page while they wait, refused with words, sent once and never again. None of that is
  wanted for a write that is the app's own: it must never be shown, toast, or hold up an
  edit. And it is the whole value, safe to make again: a write that gets no answer is
  followed, at the next exchange, by a read of what the server has, and written again only
  if it still adds something (`InStepTest.aWriteThatGetsNoAnswerIsSafeToMakeAgain`). It
  shares only the account's client.
- **Taking in** (`PhoneShared.keep` and `take`, Windows' `take_shared`): the plays into the
  log, first (rule 10); then what was agreed becomes the record's pins, theme and lists, and
  the screens show that with whatever still waits on top; dated notes of when songs were
  added; Recently played merged with the record's, the songs it names looked up in the
  phone's copy of the library and otherwise asked of the server one at a time, as Windows
  asks (one the server says is gone is not asked for again this run; Windows asks at every
  exchange). **A list that is open shows the sort it now has** (C3): the pages read the order
  from the settings each time they are drawn, not once as they open, so a sort chosen on a
  PC shows on a list that is already on the screen. An order only the phone offers is the
  phone's own choice for that list, and stays unless the sort arriving was chosen after it.
- **The queue** (`sync/QueueStep.kt`; Windows' `upload_queue`, `check_server_queue`,
  `note_elsewhere`, `adopt_queue`, `continue_here`). Saved on the server under the phone's
  own device name (`NexusMusic-<eight hex digits>`, made once) three seconds after the
  person changes it, and once a minute while a song simply plays on (Windows: every twenty
  seconds; each save sends the ids again): 50 songs behind the one playing and 450 from it
  on, stations left out, nothing while a station plays. At each look the saved queue is
  read: if another device saved it after this phone's queue last changed and nothing is
  playing here, it takes this one's place, paused; otherwise, if another device has been
  playing something else in the last half hour, the queue sheet offers it ("Playing on
  another device", the song, "Continue here": Windows' card and words), and a tap picks it
  up where it had got to and plays. Nothing starts playing by itself.
- **The phone saves its queue only if it is still the newest thing that happened** (B1).
  Saves go one at a time, in the order they were asked for (two could finish the wrong way
  round). Immediately before each, the server's saved queue is read, and it is left alone
  if another device saved it after this phone's queue last changed: three seconds pass
  between a change here and its save, and the save takes the server's clock for its time, so
  it would land on top of anything saved in between. And a queue taken up from another
  device is not saved by the phone until it has been changed or played on here: a save
  asked for before it was taken up would pass that device's queue on as the phone's own,
  changed now.
- **"Continue here" is a choice like any other** (B2). The tap takes its place in the one
  order of the person's choices (`playback/Choices`), and by the time its queue is ready it
  is played only if nothing has taken a later place: a pause, a stop, something else to
  play. A queue taken up without being asked is nobody's choice and takes no place: it gives
  way to anything chosen while it was being made ready, and a start the person made just
  before it, still being made ready itself, plays over it. Once the setting is off nothing
  is offered and nothing of another device's goes into the player, whatever was already on
  its way: the setting is asked again when the server's answer arrives, as an offer is
  noted, at the tap, and at the last moment before a queue goes in (`QueueHere.replace`).
  The server being out of reach is no reason to refuse a tap on an offer already made.
- **The phone's own saved queue and the server's, at start-up.** The phone's own is put
  back first, as it was, with the time it last changed. The server's takes its place only
  if another device saved it after that time and nothing is playing here, and never before
  the phone's own is back (`QueueHere.replace` waits for it). So the two can't fight: the
  local file is how this phone remembers its queue; the server's is how the queue moves
  between devices; and which one stands is decided by which was changed last, exactly as
  between two PCs.

### Known, and accepted (the M5a fixes)

None of these is fixed on the phone, each for the reason given.

- **Two devices that read the record at the same moment both write, and the second write
  wins.** The Subsonic interface has no way to make a write conditional on what was read (as
  for a playlist's edits), so the later of two crossed writes can be made without the
  other's part. Nothing is lost for good by it: each device still holds what it wrote (the
  phone in `Settings.agreed`, with its time) and offers it again at its next look, where it
  is merged back in. `InStepTest` crosses two devices at every step. The same holds for the
  saved queue, between the phone's look at it and its save (B1).
- **Recently played can lose an earlier play of the same song, depending on the order
  devices exchange in; and two notes of when a song was added that carry the same time and
  different contents never settle** (each device keeps its own, and writes it over the
  other's at every exchange). Both are in the shared merge (`merge_history`, `merge_notes`),
  which the phone must do exactly as the PCs do: a phone that merged differently would be a
  third opinion, and the record would change at every exchange for ever. They are to be put
  right in both apps at once.
- **The half hour of "another device has been playing" runs from when that device last saved
  its queue**, not from when it last played: a queue only edited on a PC, with nothing
  playing there, is offered as "Playing on another device" for half an hour, as between two
  PCs.
- **The time a queue changed here is by the phone's clock, and the time another device saved
  one is by the server's.** The two are compared (which is the newer), with at most the five
  minutes between them that rule 8 allows before the phone stops sharing.

### What the design left open, and what was decided (M5a)

- **The minute's look is for the screens in front only**; in the background the record moves
  with each song (above). The brief allowed "on screen or music playing".
- **The phone's theme follows the account** signed in, when that account has one of its own.
- **A song noticed in a playlist is not dated for other devices** (rule 1). What it costs:
  a song added in another app (Navidrome's own page) and seen only by the phone gets its
  shared date when a PC first sees it; and a dated note is not cleared when its song is
  noticed gone, so a song taken out and put back elsewhere, seen by no PC in between, keeps
  its old date.
- **An unreadable comment stops the exchange** until someone puts it right (a PC running
  Windows' app will, by writing over it).
- **Unknown fields are written back** although the Windows app would drop them; if the merge
  becomes Windows' own code, that is lost unless it is done there too.
- **The sync playlist's id is not remembered at all** (rule 11), rather than remembered and
  checked: the list of playlists is asked for at every exchange, one more small request.
- **A phone with nothing to say makes no playlist** (rule 4): a second account signed in on
  the phone, with nothing chosen under it and no PC, has none on its server until it pins,
  sorts, plays or adds something.
- **After an upgrade from 0.3.0, the pins made on the phone stay on its screen only until
  the account's record has pins of its own** (rule 2): then the record's are shown, and a
  playlist pinned only on the old phone is no longer pinned. Pinning it again shares it.
  The same for a list's shuffle and sort, list by list, and for the theme.
- **A change made here is timed one second after the value it changes, if that is later than
  now** (rule 1): a PC whose clock runs ahead (within what its own version allows) can't
  make the phone's later change the older one.
- **The saved queue is sent once a minute while a song plays**, not every twenty seconds: a
  PC that continues from a phone still playing starts up to a minute behind it.
- **A queue taken from another device plays in its own order, unshuffled**, with no list
  behind it, as on Windows.
- **Not built**: Windows looks at the theme alone before its window opens (`preload_theme`);
  the phone's first exchange comes a moment after start, and a newer theme shows then. A
  list on screen sorted by date added is not drawn again when another device's *dates*
  arrive (it is right the next time it is arranged); a *sort* that arrives does show (C3).

**Smoothness**: lists are lazy with stable keys and content types; nothing is computed in
composition that could be remembered; images are asked for at the size shown. Judge speed
on release builds only.

## Jams and the person's other devices (M6)

A port of the Windows app's `src/jam.rs` against its relay (`relay/` in that repository, read
as the definition of the protocol). Two features, one protocol: a **jam** is one queue that
several people add to while the music plays where its host is; **linked devices** are the
person's other copies of the app, listed under "Connect to a device", which the music moves
to and which are steered from here. Both go through a small relay the owner runs.

**Switched off in this build** (`jamsExist = false` in app/build.gradle.kts, the one switch;
the app reads it as `jam/JamBuild`). A security review found that the way linked devices
prove they are the person's own leaks a guessable fingerprint of the music-server password to
the relay and its logs (the Windows app does the same; it is to be redesigned once for both),
and ways a guest or another app can interfere with playback. With it off nothing of it shows
(no Jam in Settings or Library, no strip, no "Connect to a device" or its volume on Now
Playing, no route to the jam page: `Navigator.open` refuses one) and nothing of it runs:
`Graph.jams` is never made (it refuses to be), so no relay is asked anything, no room joined,
no session opened, no timer set; no relay key is asked for, kept, read or deleted; nothing
about jams is written. The player's other player (`Steered`) is never shown, so the media
session, the notification, the car and a headset act on the phone's own queue as before M6.
The switch also decides whether `app/src/jam/AndroidManifest.xml` is merged into the APK: off,
the APK has no activity taking a jam's link (no share target, no link filter), no
`JamService`, and no `FOREGROUND_SERVICE_CONNECTED_DEVICE`, so other apps can find nothing of
it. The order of choices that came with M6 stays on. `jam/SwitchedOffTest` holds this (with
the switch on, it fails at the route and at the share target); `jam/`'s own tests build their
objects directly and run whatever the switch says. With the feature on, `Jams.scheduleRecheck`
wakes once a second once the ten-minute grace after a pause has run out while linking is on
(the review's finding): not fixed here, as nothing of it runs in this build.

How it is built (`jam/`, `ui/jam/`, `playback/Steered.kt`):

- **The relay** (`RelayLine`): its address and the app's HTTP client made fit for it. The
  long waits for news are reads; **anything that changes a jam is sent once** (rule 6): no
  retry on a dead connection, no redirect followed, and a reply OkHttp would follow up by
  itself (a 408, a 421, a 503 with `Retry-After`) handed back as it is. No answer, or the
  relay's own 504 (the host's app didn't answer, and may have done it), is "no answer": the
  jam is read again and shown, and the person does it again if they still want to. The
  relay's connections are a pool of their own, closed ten seconds after they are idle and
  all at once when nothing of the relay is open (rule 1).
- **Joining** (`GuestSide`, `Jams`): a link pasted on the jam page, or shared to the app from
  another (`JamLinkActivity`, which only reads the link and puts it in the box: nothing is
  joined until Join is pressed). While joined, the phone's player shows the jam
  (`Steered`): `PlayerConnection`'s flows are the jam's, and what is done in the player bar,
  Now Playing, the queue sheet and anywhere in the app (Play, Add to queue, a menu's Play) is
  asked of the host, as Windows' `remote*` functions do. The player shows what was asked at
  once and the host's word follows (`Steering.steer`, Windows' `remote`). A song this
  phone's library has is shown as anywhere else (its cover, its menu, like); one it doesn't
  is shown as the host described it, its cover from the relay (`JamCovers`), with no like and
  no menu. What was playing on the phone pauses (a choice, in the order of choices) and is
  still there afterwards. A strip in the accent colour above the player says so, with Find
  songs and Leave jam.
- **The person's devices** (`RoomSide`, `Answers`, `OwnPlayer`): with a host link entered in
  Settings, Jam ("Host jams", the link, "Link my devices", this device's name, in Windows'
  words and order), the phone joins the room worked out from the account's name and
  password exactly as Windows does (`account_words`: neither is sent), says what it plays,
  and opens a session of its own that the others steer it through. Connect lists the other
  devices with what each is doing. Choosing one while music plays here hands it the queue
  (what plays and everything after it, 3000 songs at most, at the same place) and pauses
  here; while nothing plays, steers what it has on. The volume of a device being steered is
  a slider on Now Playing. "Play here" takes the other device's queue and pauses it there.
- **A queue handed to the phone** (rule 3, `PlaybackService.handOver`): it takes its place
  in the order of choices as it arrives (before anything is looked up) and plays only if
  nothing has taken a later place by the time it is ready (`QueuePlayer.playFor`); offline,
  only its songs that play here are kept, as for any list, and with none it is refused; in a
  call (the audio mode) or with the sound held by an app that won't give it up (a request
  for the sound refused) it is refused, and the other device is told why. Being steered
  (play, pause, a move, the volume) is a choice like a tap here. The phone's volume, as a
  PC turns it, is its media volume.
- **Hosting** (`Hosting` in `Jams`, `HostingPage`): "Start a jam" makes the phone's own
  session public (or opens one); the page shows the link, a code to scan for it (drawn
  without a library: `Qr`, byte mode at level M, as Windows' `qr_image` draws its own),
  Android's share sheet, who is there with Remove, "Guests can steer", and End jam. Guests'
  searches are answered from the phone's copy of the library (`LibrarySnapshot.find`, 40 at
  most) and covers from the phone's cover store, cut down as Windows cuts them
  (`GuestCovers`: 128 px for a row, the 640 px copy as it is unless heavy). A song a guest
  names that the copy doesn't have is asked of the server by id, as Windows does.
- **Guests are strangers** (rule 5, `Answers`): a guest is never given the server's
  address, the sign-in or any audio; what they can ask is exactly what Windows answers
  (`jam_asked`): add (50 at once, songs this library has, not twice), remove (their own
  unless steering is allowed), and with steering the transport and queue; `hello`, `queue`,
  `load` and `volume` only for a device that shows the owner's word. Every number and list
  is bounded as Windows bounds it (100 of the queue told, 40 found, 50 added, 3000 handed),
  ids that aren't ids are dropped, and a request that isn't understood does nothing. **A jam
  joined is someone else's server**: everything it sends is read as untrusted
  (`Wire.kt`): text cut to a line of fit length with what can't be seen taken out, numbers
  held to what they can mean, ids and cover ids that aren't ids dropped (they would be sent
  back, or put in an address), at most 100 songs of a queue, and a cover used only if its
  first bytes are a JPEG, PNG or WebP, no larger than the relay takes and no larger than
  4096 px a side.
- **The key** (rule 4, `KeyVault`): the host link's key is sealed with the Android Keystore
  as the password is, sealed together with the account it belongs to (another account
  can't open it), in a file of its own beside the password's, never in the settings file,
  a log or Diagnostics (no line of the jam code logs anything), sent only in the
  `Authorization` header to the relay whose link it came with, and removed at sign-out. The
  relay's address is in the settings (the screen shows its host name only).

**When the phone holds a connection to the relay, and what it costs** (rule 1, `Reasons`).
Windows keeps every linked PC at the relay all the time; a phone doing that from the
background would keep its radio awake all day. The phone is in the room, with its session
open, only while one of these is so: **the app's screens are in front; music is playing on
the phone, or was less than ten minutes ago** (the playback service stays in the foreground
that long after a pause, so a PC that paused the phone can play it again); **or the phone is
in a jam, hosting one, or steering another device.** For the last three a foreground service
of the connected-device kind (`JamService`) runs with a notification that says which ("In
Jo's jam", "Hosting a jam", "Steering Desk PC") and offers Leave, End jam or Stop steering,
so Android lets the app hold the connection with its screens closed and the person can see
it and end it. With none of these, the phone leaves the room, ends its private session, and
closes the relay's connections: the other devices simply do not list it. **What it costs
while open**: two requests held open at the relay (the room's news and the session's), each
answered at once when something changes and otherwise after twenty seconds, so at least six
small requests a minute (a few hundred bytes each, a few kilobytes a minute), plus a short
post whenever what the phone is playing changes and every thirty seconds besides. On mobile
data that keeps the radio from settling into its idle state for as long as it lasts, which is
why it lasts only while one of the reasons does. Checked on the emulator with `dumpsys
activity services` and `/proc/net/tcp6` (docs/TESTING.md).

**What the car, the notification and the lock screen show** (rule 2). Nothing of a jam or
of another device reaches the media session:

| The phone is | The media session, notification, lock screen and car | Play in the car | A headset's or the notification's Play/Pause, Next, Previous |
|---|---|---|---|
| playing its own music | its own song, playing (as before) | as before | as before |
| hosting a jam | its own song (it is what plays), playing or paused; a second notification says "Hosting a jam" | as before: the jam's music is the phone's | as before |
| in a friend's jam | its own queue, paused where it was (or nothing, if it had none); a second notification says "In Jo's jam" | not carried out: the car's player says "When it's safe, open Nexus Music on your phone to start playing", with Play still offered; the phone's own music does not start over the jam | steer the jam (play/pause, skip, back), as Windows' media keys do, when the host lets guests steer; the phone's own player does not move |
| steering another of its devices | its own queue, paused (the music moved there); a second notification says "Steering Desk PC" | as in a jam | steer that device, as in a jam |

A headset's keys and the notification's own buttons reach Media3 as media-button events and
are given to the other player there (`onMediaButtonEvent`, `Steered.key`); a transport command
under the notification controller's name likewise (`Steered.press`). Any other Play of the
phone's own player while it shows another is refused (`mayPlay`): the car is one, and so is
the phone's resume control.

**Joining from a link that is opened.** Android opens a web link in an app by itself only
for an address the app has proved it owns (an App Link, checked against a file on that
site). A relay's address is the person's own and can't be known here, so from Android 12 a
jam link someone taps opens in the browser, on the relay's own page, which works as a guest
from any phone. The app takes a link two ways: **shared** to it (a chat's Share: always
works), or **opened** where the app showing it offers a choice of apps (the filter matches
`https://<any host>/j/…` and `/…/j/…`, not verified). The jam page also has Paste.

### What the design left open, and what was decided (M6)

- **The car while the phone is in a jam** shows the phone's own queue, paused, and its Play
  is refused with the existing "open the app" line rather than steering a jam the car can't
  show. A headset steers it, as Windows' media keys do; the lock screen and the media
  notification's buttons do too, because Android hands them over as the same media keys.
  So the lock screen can show the phone's own song paused while its Play/Pause pauses the
  jam. The jam's own notification says what is going on beside it.
- **When "music was playing" stops being a reason**: ten minutes after it stops, the time
  Media3 keeps the playback service in the foreground. A PC that paused the phone can play
  it again within that time, not after.
- **Moved here from a PC "at the moment it was sent"**: the relay passes a request on to
  the phone the moment it arrives (the phone's wait is held open there), so its arrival is
  taken as the moment it was sent. A choice made on the phone in the second before it
  arrives is not known to be earlier, and the queue from the PC wins over it. That second is
  the window that remains; anything chosen after the arrival wins.
- **The phone's volume** as another device turns it is the media volume (what turning a
  phone up or down means), not a level of the app's own.
- **Words** are in `res/values/strings_jam.xml`, beside `strings.xml`, so the feature's words
  are found together; Windows' wording, with "this PC" made "this phone".
- **The device's name** is the phone's own (Android's device name, else its model), unless
  one is typed in Settings, Jam.
- **Not shown on the phone that Windows shows**: the jam window's progress for a cover
  being sent; the devices' list in a side panel (the phone's is Connect, a sheet from Now
  Playing).

**What remains, and what needs real devices**: Android Auto in a car with the phone in a
jam (the emulator car check passes; see TESTING); a real call arriving (the emulator's calls
don't change the audio mode: rule 3's refusal in a call is shown in the service under
Robolectric); battery on mobile data while in a jam (the cost above is reasoned, not
measured on a radio).

### The order of choices, completed (with M6)

Every choice takes one place in the order of choices, once (`playback/Choices`), so that the
newest wins and a stop beats what was asked before it. The gaps the M3b review found, each
closed by the same rule (`OrderOfChoicesTest`, in the real service, a case each):

- **Resuming, Pause, and a tap on an entry of the queue** take their place at the tap
  (`PlayerConnection.playPause`, `jump`). A Play or Pause tapped takes no other when it reaches
  the player (`Choices.tapPlayPause`, `arrivedFromTap`; `QueuePlayer.placeTakenAtTap`). A Pause
  from outside the app's screens (a car, a headset, the notification) takes its one place as it
  arrives (`QueuePlayer.handleSetPlayWhenReady`); the player's listener takes none for a pause
  asked of the player, only for one it came to by itself or was put in by the service (the end
  of the queue, the sound taken away, the server given up on). The sleep timer firing while
  the music is already paused takes its place all the same.
- **A Play from a list carries its tap's place** (`AppCommands.PLACE`) and the service plays
  it at that place (`QueuePlayer.playFor`) instead of taking a second one on delivery. As
  decided: anything that takes a later place before it lands (the sound taken away, a choice
  in the car, offline mode switched on) drops it.
- **A Play waiting for the saved queue** (`onPlaybackResumption`) takes a place as it arrives
  and is dropped if anything takes a later one while it waits.
- **A Play waiting for a call to end** remembers its place (taken once the refusal itself has
  taken its own), not a flag a Stop left standing.
- **The retry of a song that failed** (`retryLater`) is dropped by anything that takes a
  place before it is due, a Stop included (which leaves "play when ready" as it was).
- **A Pause takes a place even when already paused** (`QueuePlayer.handleSetPlayWhenReady`).

The jams' starts follow the same rule: joining a jam or moving the music away pauses the
phone (a place); a queue handed over takes its place as it arrives; "Play here" at its tap;
Play or Pause asked of the phone from another device is a choice like a tap.

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
  multi-select. M3a (built): the library on the phone, the local search, the Songs page,
  finding and sorting in a list, Recently played, a song's details, "Go to" each artist.
- **M4 Sound.** Quality switch, levelling, equaliser, mono, crossfade, sleep timer, lyrics.
  M4a (built): levelling, the equaliser, mono, the quality switch, the sleep timer, lyrics.
  M4b: crossfade (not on this path: see "Crossfade (M4b) is not built on this path").
- **M5 In step with your other devices.** Saved queue and "Continue here", the sync
  playlist, "Your stats", "Made for you" and mixes of your own. M5b (built): the log of
  plays, "Your stats", "Made for you", mixes of one's own, the mixes in the car. M5a (built):
  the sync playlist, the saved queue and "Continue here", and the rules that keep the PCs
  safe from the phone.
- **M6 Jams and linked devices** (built): joining a jam, the person's own devices, hosting a jam from the phone; and the order of choices completed.
- **M7 Requests.** Lidarr, MusicBrainz, ListenBrainz, browser sign-in through Authentik,
  "Artists you might like", bringing playlists over from Spotify.
- **M8 Shipping.** The signing key, updates from GitHub releases, checks on every push,
  start-up profiles, diagnostics, the README.

Not coming to the phone: exclusive mode and choice of output device, the mini-player
window, tray and taskbar, keyboard shortcuts.
