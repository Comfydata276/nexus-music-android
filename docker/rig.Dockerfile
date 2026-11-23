# The test rig's environment: everything in the build image, plus what the rig needs to
# make the test library (Python, ffmpeg), to give Google's Desktop Head Unit a screen it
# can draw on with nobody watching (a virtual display), and to capture what the emulator
# plays. scripts/lib.sh builds this the first time a rig command needs it.
FROM nexus-music-android

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      python3 python3-mutagen python3-pil python3-numpy ffmpeg fonts-dejavu-core fonts-noto-cjk \
      xvfb x11-utils x11-apps xdotool imagemagick netcat-openbsd procps file \
      pulseaudio pulseaudio-utils \
      libsdl2-2.0-0 libc++1 libc++abi1 libportaudio2 libpng16-16t64 libusb-1.0-0 \
      libgl1-mesa-dri libegl1 libxrandr2 libxss1 libxinerama1 \
    && rm -rf /var/lib/apt/lists/*
