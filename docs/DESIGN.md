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
    by name counts as on the phone) all go the same way. Only where the phone has nothing at
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
  the Windows app's form (`SavedQueue`, `QueuePlace` and its `queue_shape`), ready for
  "Continue here" in M5. It stays saved through signing out.
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
  the heart off until the next refresh.) One gap is left: a like held, the server's own
  list fetched while it is still held, and the app then started again before it is
  delivered, shows the heart everywhere but does not list the song in Liked songs until
  the like has gone (the phone's copy of the list is then the server's, and the song's
  details are not in the held change).
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
  the song in Liked songs until it is delivered.

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
- **A song's menu** has what works so far: Play next, Add to queue, like, Download (or Cancel
  download, or Remove download, as it stands), Go to album, Go to
  artist ("Go to <name>" for each credited artist when there are several, as Windows), Song
  details, and Remove from queue from the queue. Nothing that does nothing yet is shown.
- **Home**: the greeting, four quick tiles (two lines of two, as Windows on a narrow window),
  then Recently added, Recently played, Most played, It's been a while and Something
  different, each sideways. "Made for you" and "Artists you might like" come later.
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
  how many albums couldn't be fetched, if any. "Popular" and "Fans also like" need the server to ask Last.fm, which
  the test server can't; they come with the rest of browsing (M3).
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
  results from it (Windows starts radio from it, which comes with M3).
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
  the play count, last play and rating are the newest the app has seen (`Freshest`: a page
  from the server since, or what the app itself just reported), not asked of the server again.

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
  narrowed the same way (Windows' follows it unnarrowed); the saved queue keeps the find text
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
  phone put liked songs first.
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
  multi-select. M3a (built): the library on the phone, the local search, the Songs page,
  finding and sorting in a list, Recently played, a song's details, "Go to" each artist.
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
