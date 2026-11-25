# instep: the Windows app's in-step code, run here

The phone writes into a record the Windows app reads and trusts (the comment of the playlist
"Nexus Music sync"). This tool is how the phone's Kotlin is held to what Windows itself does:
it **is** the Windows code, compiled from the Windows repository's source where it lies
(`../nexus-music/src`, read-only, by path), not a copy of it.

| What | How |
|---|---|
| The cases the Kotlin is held to | `tools/instep/run cases` writes `app/src/test/resources/instep-cases.json`: what Windows' `merge`, `merge_history`, `merge_plays`, `merge_notes` and serde (reading a text, writing a record) make of a list of inputs. `InStepCasesTest` holds the Kotlin to every one. |
| The other direction | The same test writes the texts the Kotlin produced to `app/build/instep/kotlin-wrote.json`; `tools/instep/run reads /work/app/build/instep/kotlin-wrote.json` has Windows' code read each one back and says whether it is the record Windows made. |
| Playing the PC | `tools/instep/run pc --server … --user … --password … --state <file>` makes one exchange with a server exactly as the Windows app does (its own `exchange`, `find_or_create` and API client), with `<file>` as what that PC holds. `peek` shows the playlist's comment byte for byte and changes nothing; `plant` puts a text of your choosing there; `queue-save` and `queue-get` are the server's saved queue as Windows saves and reads it. `scripts/instep-check` runs the whole end-to-end check with it (docs/TESTING.md). |

| A household on mixed versions | `scripts/instep-check --mixed` builds this tool twice, from the Windows code under test (`NEXUS_WINDOWS_SRC`) and from 0.40.4's source, and runs `instep household`: two devices on the new code and a PC on 0.40.4 against a real server, saying a line at a time what each did. `ok` lines are what the new code must do; `old` lines are what a PC on 0.40.4 goes on doing until it is updated. See `src/household.rs`. |

**Which Windows versions.** Up to 0.40.4 the exchange is the Windows app's own (`exchange`
in its `src/sync.rs`), and a build of this tool from such a checkout has `pc` and
`pc-looks`. After it the exchange is decided in the Windows app's library
(`nexus_core::step`), which the phone is to share; a build from such a checkout has
`household` instead, and drives that code as the Windows app does (`sync::look`,
`sync::send`). `cases`, `reads`, `peek`, `plant` and the queue modes are in both.

**What is compiled** (see `build.rs`): `subsonic.rs`, `added.rs` and `plays.rs` whole;
`sync.rs` from its top down to `impl Controller` (the record, the merges, `exchange`); from
`config.rs`, `ListPrefs`, `Played` and `write_atomically`. Nothing is rewritten; if the Windows
code moves so that a cut can't be found, the build fails and says which.

From the release after 0.40.3 the Windows repository is the app and a library, `core/` (nexus-core), beside
`src/`: the record, the merges, the notes and what a server's answers are read into are
there, and the files above name them from it. The tool depends on that library by path, as
the app does (`run` puts `../nexus-music/core` at `/win/core` in the container), so what is
run is still the Windows code itself: the merges straight from the library, `exchange` and
the API client from `src/`. A Windows checkout from before the library (0.40.3 and earlier)
is still compiled as it was; `before-core/` is an empty stand-in for the library then, to
delete once no such checkout is used.

**What it needs**: Docker, and the Windows app's build image `nexus-music-dev`
(`docker build -f ../nexus-music/docker/dev.Dockerfile -t nexus-music-dev ../nexus-music/docker`).
It builds under `~/.cache/nexus-music-android/instep-target`, outside both repositories (in a
folder of its own for each Windows checkout it is pointed at), and
fetches its few crates into `~/.cache/nexus-music-android/cargo` the first time. `Cargo.lock`
here began as the Windows app's own, so serde and the rest are the releases Windows is built
with.

**It has no tests of its own** (`cargo test` here builds nothing to run; `test = false` in
`Cargo.toml`): the Windows files carry the Windows app's tests, which need the rest of that
app and are run there. What this tool checks, it checks by running.

**When the Windows code changes**: run `tools/instep/run cases` and the unit tests. A case
that no longer matches is a difference between the two apps: fix the Kotlin, or raise it.
New awkward inputs go in `src/main.rs` (`read_texts`, `merge_pairs`, `play_lists`,
`note_pairs`), never into the JSON by hand.
