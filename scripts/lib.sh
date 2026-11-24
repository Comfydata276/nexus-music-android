# Shared by the test rig's scripts (server, emu, carcheck, dhu, measure). Sourced, not run.
# Everything the rig does happens in Docker; the host needs Docker and /dev/kvm, nothing else.

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cache="${NEXUS_ANDROID_CACHE:-$HOME/.cache/nexus-music-android}"
rig_image=nexus-music-android-rig
base_image=nexus-music-android

die() { echo "error: $*" >&2; exit 1; }
say() { echo "$*" >&2; }

command -v docker >/dev/null || die "docker is not installed or not on PATH"

# Build the images the first time they are needed.
need_rig_image() {
  if ! docker image inspect "$base_image" >/dev/null 2>&1; then
    say "building $base_image (once)..."
    docker build -q -f "$root/docker/build.Dockerfile" -t "$base_image" "$root/docker" >/dev/null \
      || die "could not build $base_image"
  fi
  if ! docker image inspect "$rig_image" >/dev/null 2>&1; then
    say "building $rig_image (once)..."
    docker build -q -f "$root/docker/rig.Dockerfile" -t "$rig_image" "$root/docker" >/dev/null \
      || die "could not build $rig_image"
  fi
}

# Run one command in a throwaway rig container, as the current user, with the repository
# at /work and the cache at /cache. Extra docker arguments go in RIG_DOCKER_ARGS.
rig_run() {
  need_rig_image
  mkdir -p "$cache/sdk" "$cache/home"
  local tty=()
  [ -t 0 ] && [ -t 1 ] && tty=(-t)
  # shellcheck disable=SC2086
  docker run --rm -i "${tty[@]}" ${RIG_DOCKER_ARGS:-} \
    -u "$(id -u):$(id -g)" --network host \
    -e HOME=/home/dev -e GRADLE_USER_HOME=/home/dev/.gradle \
    -v "$root":/work -v "$cache":/cache -v "$cache/home":/home/dev -v "$cache/sdk":/sdk \
    -w /work "$rig_image" "$@"
}

container_running() {
  [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" = "true" ]
}

# --- which emulator a command talks to -------------------------------------------------
# The devices: the phone (default, Android 17), the same on Android 16 (phone36) and on
# Android 12 (phone31), and the car (Android Automotive OS).
# Choose with `-d car` on scripts/emu, or NEXUS_DEVICE=car for the other scripts.
device_setup() {
  device="${1:-${NEXUS_DEVICE:-phone}}"
  case "$device" in
    phone)
      # Android 17: what a current flagship runs, and the level the app targets.
      # "Google APIs" rather than "Google Play": the same system, but adb can become root
      # (which the measurements need) and nothing asks for a Google account.
      avd=nexus-phone; port=5554
      image="${NEXUS_PHONE_IMAGE:-system-images/android-37.0/google_apis/x86_64}"
      ram="${NEXUS_PHONE_RAM:-2048}"
      ;;
    phone36)
      # Android 16 (its second quarterly release, API 36.1: what One UI 8.5 is built on),
      # the version the first phone the app goes onto runs. Not downloaded until first used.
      avd=nexus-phone36; port=5560
      image="system-images/android-36.1/google_apis/x86_64"
      ram="${NEXUS_PHONE_RAM:-2048}"
      ;;
    phone31)
      # Android 12, the oldest version the app supports. Not downloaded until first used.
      avd=nexus-phone31; port=5558
      image="system-images/android-31/google_apis/x86_64"
      ram="${NEXUS_PHONE_RAM:-1536}"
      ;;
    car)
      # Android Automotive OS: a car's own built-in system, whose media app browses
      # media services with the same library Android Auto's screens are built on.
      avd=nexus-car; port=5556
      image="${NEXUS_CAR_IMAGE:-system-images/android-35-ext15/android-automotive/x86_64}"
      ram="${NEXUS_CAR_RAM:-2560}"
      ;;
    *) die "unknown device '$device' (use phone, phone36, phone31 or car)" ;;
  esac
  container="nexus-emu-$device"
  serial="emulator-$port"
}

need_emulator() {
  container_running "$container" \
    || die "the $device emulator is not running. Start it with: scripts/emu${device:+ -d $device} start"
}

# adb against the chosen emulator, run inside its container (where the adb server lives).
adb_() {
  docker exec -i "$container" adb -s "$serial" "$@"
}
adb_sh() {
  docker exec -i "$container" adb -s "$serial" shell "$@"
}

# The person at the wheel. A phone has one user, 0. A car's system runs its screens as a
# second user (10 on the emulator) and keeps user 0 for the system with no screen at all,
# so anything started without saying which user would start where nobody can see it.
current_user() {
  adb_sh am get-current-user | tr -d '\r\n'
}

# --- the rig's own tools (the car probe and the stand-in fixture) ----------------------
probe_apk="$root/tools/carprobe/build/outputs/apk/debug/carprobe-debug.apk"
standin_apk="$root/tools/standin/build/outputs/apk/debug/standin-debug.apk"

# Build them if an APK is missing or older than its sources. One Gradle build at a time on
# this machine: if the app's build is running, wait for it rather than fight it for memory.
build_tools() {
  local stale=0 apk
  for apk in "$probe_apk" "$standin_apk"; do
    if [ ! -f "$apk" ]; then stale=1; continue; fi
    local module; module="$(echo "$apk" | sed -E 's|.*/tools/([^/]+)/build/.*|\1|')"
    if [ -n "$(find "$root/tools/$module/src" "$root/tools/$module/build.gradle.kts" "$root/tools/settings.gradle.kts" \
               "$root/gradle/libs.versions.toml" -newer "$apk" -print -quit 2>/dev/null)" ]; then stale=1; fi
  done
  [ "$stale" = 1 ] || return 0
  need_rig_image
  local waited=0
  while docker ps --format '{{.Image}} {{.Command}}' | grep -q -E "^$base_image .*gradlew"; do
    [ "$waited" = 0 ] && say "another Gradle build is running; waiting for it to finish..."
    waited=$((waited + 5)); [ "$waited" -lt 900 ] || die "another Gradle build has been running for 15 minutes; try again later"
    sleep 5
  done
  say "building the rig's tools (about a minute)..."
  local log="$cache/logs/tools-build.log"; mkdir -p "$cache/logs"
  if ! "$root/scripts/dx" ./gradlew --no-daemon -q -p tools :carprobe:assembleDebug :standin:assembleDebug >"$log" 2>&1; then
    tail -n 30 "$log" >&2
    die "the rig's tools did not build (full log: $log)"
  fi
}

# Is this package installed, at the same version as this APK file? Installs it if not.
ensure_installed() {
  local apk="$1" package="$2" stamp
  stamp="$(stat -c %Y "$apk")"
  if [ "$(adb_sh "pm path $package >/dev/null 2>&1 && cat /data/local/tmp/$package.stamp 2>/dev/null" | tr -d '\r\n')" != "$stamp" ]; then
    docker exec -i "$container" sh -c "cat > /tmp/$package.apk" < "$apk"
    adb_ install -r -t -g "/tmp/$package.apk" >/dev/null 2>&1 \
      || { adb_ uninstall "$package" >/dev/null 2>&1; adb_ install -r -t -g "/tmp/$package.apk" >/dev/null; } \
      || die "could not install $(basename "$apk") on the $device emulator"
    docker exec "$container" rm -f "/tmp/$package.apk"
    adb_sh "echo $stamp > /data/local/tmp/$package.stamp"
  fi
}
