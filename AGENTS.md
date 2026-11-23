# Working in this repository

Nexus Music for Android. Read `docs/DESIGN.md` before changing anything: it is the design
the app is built to, and it lists what matters most.

## Ground rules

- **The Windows app at `../nexus-music` is read-only.** It is the reference for behaviour,
  wording and looks. Never edit, build or commit there.
- **Never push, publish, or create anything on GitHub.** Commit locally on the branch you
  were given. The lead decides what ships.
- This repository will be public. Keep server addresses, account names, passwords, keys and
  anything personal out of code, tests, commits and logs.
- No analytics, crash reporters, ads or Google Play services. Ask before adding a
  dependency of any size.
- Check an API against the version in `gradle/libs.versions.toml` before relying on memory.
- If the brief you were given is unclear, or the design looks wrong, say so in your report
  instead of guessing at something large.

## Building and testing

Everything runs inside Docker, so the host needs nothing but Docker.

```sh
docker build -f docker/build.Dockerfile -t nexus-music-android docker   # once
scripts/dx ./gradlew --no-daemon :app:assembleDebug        # debug build
scripts/dx ./gradlew --no-daemon :app:testDebugUnitTest    # unit tests
scripts/dx ./gradlew --no-daemon :app:lintDebug            # lint
scripts/dx ./gradlew --no-daemon :app:assembleRelease      # shrunk build: the one to measure
```

The machine has 12 GB of memory, no swap, and is shared with other agents and an emulator.
Run one Gradle build at a time, always with `--no-daemon`, and do not raise the memory
settings in `gradle.properties`.

## Finishing a piece of work

- The debug build, unit tests and lint pass. Say plainly if anything does not.
- New logic that was ported from the Windows app has its tests ported with it.
- Commit with a message that says what changed and why, in the style of the Windows
  repository's history.
- Report: what you built, what you checked and how, what you left out, and anything the
  lead needs to decide.
