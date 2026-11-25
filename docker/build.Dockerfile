# Build and test environment for Nexus Music on Android.
# The Android SDK itself lives in a folder kept between runs (see scripts/dx), so adding a
# platform or an emulator image doesn't mean rebuilding this image.
FROM eclipse-temurin:25-jdk

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl unzip git \
      libpulse0 libnss3 libgl1 libx11-6 libxcb1 libxkbfile1 libxcomposite1 libxcursor1 \
      libxi6 libxtst6 libxdamage1 libxrender1 libxext6 libxfixes3 libdbus-1-3 libasound2t64 \
      libxcb-cursor0 libdrm2 libgbm1 \
    && rm -rf /var/lib/apt/lists/*

# Google's command-line tools, which fetch the rest of the SDK. Raise the number on purpose.
ARG CMDLINE_TOOLS=16111833
RUN curl -fsSL -o /tmp/tools.zip \
      https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip \
    && mkdir -p /opt/android && unzip -q /tmp/tools.zip -d /opt/android && rm /tmp/tools.zip

ENV ANDROID_HOME=/sdk ANDROID_SDK_ROOT=/sdk ANDROID_USER_HOME=/home/dev/.android \
    PATH=/opt/android/cmdline-tools/bin:/sdk/platform-tools:/sdk/emulator:$PATH

# The shared core: the part of the app that is the Windows app's own code (its `core/`),
# compiled into a library the app carries (see native/ and docs/DESIGN.md, "The shared
# core"). Rust, with its standard library for the two kinds of device the app is built for;
# and a C linker for this machine's own kind, because the unit tests run that same library
# on the JVM. The version is the one the Windows app is built with: raise the two together.
# (The Android NDK, whose linker the two device builds need, is not in the image: like the
# rest of the SDK it is kept in the cache folder, and native/build fetches it when first
# needed.) What a build downloads (the crates, the Windows repository at its pinned commit)
# is kept under the cache folder's home, as Gradle's downloads are: scripts/dx says where.
ARG RUST_VERSION=1.100.0
ENV RUSTUP_HOME=/opt/rustup RUSTUP_TOOLCHAIN=${RUST_VERSION} PATH=/opt/cargo/bin:$PATH
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev \
    && rm -rf /var/lib/apt/lists/* \
    && curl -sSf https://sh.rustup.rs | CARGO_HOME=/opt/cargo sh -s -- -y --profile minimal \
         --default-toolchain ${RUST_VERSION} --target aarch64-linux-android --target x86_64-linux-android \
    && chmod -R a+rX /opt/rustup /opt/cargo

WORKDIR /work
