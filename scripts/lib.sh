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
# Two devices are defined: the phone (default) and the car (Android Automotive OS).
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
    phone28)
      # Android 9, the oldest version the app supports. Not downloaded until first used.
      avd=nexus-phone28; port=5558
      image="system-images/android-28/google_apis/x86_64"
      ram="${NEXUS_PHONE_RAM:-1536}"
      ;;
    car)
      # Android Automotive OS: a car's own built-in system, whose media app browses
      # media services with the same library Android Auto's screens are built on.
      avd=nexus-car; port=5556
      image="${NEXUS_CAR_IMAGE:-system-images/android-35-ext15/android-automotive/x86_64}"
      ram="${NEXUS_CAR_RAM:-2560}"
      ;;
    *) die "unknown device '$device' (use phone, phone28 or car)" ;;
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
