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

WORKDIR /work
