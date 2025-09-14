#BUILD APP
FROM maven:3.9.11-amazoncorretto-21@sha256:e2d145396855bc17b8d2778b052c03de10032d6ab384116ec10648d69bbd9240 AS build_app
WORKDIR /usr/src/app
COPY pom.xml .
COPY lib lib
RUN for file in ./lib/*; do mvn org.apache.maven.plugins:maven-install-plugin:install-file -Dfile=$file; done; mvn dependency:go-offline
COPY src/ ./src/
RUN mvn -f ./pom.xml package

#BUILD SPOTIFYD
FROM rust:1.89.0-bookworm@sha256:948f9b08a66e7fe01b03a98ef1c7568292e07ec2e4fe90d88c07bb14563c84ff AS build_spotifyd
RUN apt-get update && apt-get install -y libasound2-dev libssl-dev libpulse-dev libdbus-1-dev cmake libclang-dev
RUN git clone https://github.com/Spotifyd/spotifyd.git /usr/src/spotifyd && \
    git -C /usr/src/spotifyd fetch origin refs/pull/1360/head:tmp && \
    git -C /usr/src/spotifyd checkout tmp
WORKDIR /usr/src/spotifyd
RUN cargo build --release --no-default-features --features pulseaudio_backend

#PACKAGE
FROM debian:12.12-slim@sha256:df52e55e3361a81ac1bead266f3373ee55d29aa50cf0975d440c2be3483d8ed3

ADD https://files.teamspeak-services.com/releases/client/3.5.6/TeamSpeak3-Client-linux_amd64-3.5.6.run /usr/local/teamspeak/install.run
ADD https://apt.corretto.aws/corretto.key /tmp/corretto.key

RUN \
    apt-get update \
 && apt-get install -y gnupg2 software-properties-common \
 && gpg --dearmor -o /usr/share/keyrings/corretto-keyring.gpg /tmp/corretto.key \
 && (echo "deb [signed-by=/usr/share/keyrings/corretto-keyring.gpg] https://apt.corretto.aws stable main" | tee /etc/apt/sources.list.d/corretto.list) \
 && apt-get update \
 && apt-get install -y \
    curl \
    java-21-amazon-corretto-jdk \
    libasound2 \
    libdbus-1-3 \
    libegl1 \
    libfontconfig1 \
    libfreetype6 \
    libgl1 \
    libglib2.0-0 \
    libnss3 \
    libpci3 \
    libxcomposite1 \
    libxcursor1 \
    libxi6 \
    libxkbcommon0 \
    libxslt1.1 \
    libxss1 \
    libxtst6 \
    pulseaudio \
    supervisor \
    vlc \
    xvfb \
 && rm -rf /var/lib/apt/lists/* \
 && rm -f /usr/lib/x86_64-linux-gnu/vlc/lua/playlist/youtube.luac \
 && mkdir -p /usr/local/teamspeak \
 && chmod +x /usr/local/teamspeak/install.run \
 && echo -ne "\ny" | (cd /usr/local/teamspeak/ && ./install.run)

ADD https://raw.githubusercontent.com/videolan/vlc/master/share/lua/playlist/youtube.lua /usr/lib/x86_64-linux-gnu/vlc/lua/playlist/youtube.lua

COPY ./docker-fs /

ENV ENABLE_TEAMSPEAK false
ENV TS3SERVER ""
ENV TS3_APIKEY M2I6-MKSK-OCHP-JR0T-CY8L-T3H3

COPY --from=build_app /usr/src/app/target/clientv2-0.0.1-SNAPSHOT.jar /usr/local/musikbot/musikbot.jar
COPY --from=build_spotifyd /usr/src/spotifyd/target/release/spotifyd /usr/local/spotifyd/spotifyd
ENTRYPOINT ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
