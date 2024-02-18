#BUILD APP
FROM maven:3.9.6-amazoncorretto-21@sha256:f004b534b13f0a785ad0a904ed012606b0939b40344939fe7e4cf1be9a3f92e5 AS build_app
WORKDIR /usr/src/app
COPY pom.xml .
COPY lib lib
RUN for file in ./lib/*; do mvn org.apache.maven.plugins:maven-install-plugin:install-file -Dfile=$file; done; mvn dependency:go-offline
COPY src/ ./src/
RUN mvn -f ./pom.xml package

#BUILD SPOTIFYD
FROM rust:1.76.0-bookworm@sha256:a71cd88f9dd32fbdfa67c935f55165ddd89b7166e95de6c053c9bf33dd7381d5 AS build_spotifyd
RUN apt-get update && apt-get install -y libasound2-dev libssl-dev libpulse-dev libdbus-1-dev
RUN git clone https://github.com/Spotifyd/spotifyd.git /usr/src/spotifyd
WORKDIR /usr/src/spotifyd
RUN cargo build --release --no-default-features --features pulseaudio_backend

#PACKAGE
FROM debian:12.5-slim@sha256:d02c76d82364cedca16ba3ed6f9102406fa9fa8833076a609cabf14270f43dfc
RUN \
    apt-get update \
 && apt-get install -y wget gnupg2 software-properties-common \
 && (wget -O - https://apt.corretto.aws/corretto.key | gpg --dearmor -o /usr/share/keyrings/corretto-keyring.gpg) \
 && (echo "deb [signed-by=/usr/share/keyrings/corretto-keyring.gpg] https://apt.corretto.aws stable main" | tee /etc/apt/sources.list.d/corretto.list) \
 && apt-get update \
 && apt-get install -y \
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
 && wget -q https://raw.githubusercontent.com/videolan/vlc/master/share/lua/playlist/youtube.lua -O /usr/lib/x86_64-linux-gnu/vlc/lua/playlist/youtube.lua \
 && mkdir -p /usr/local/teamspeak \
 && wget -q https://files.teamspeak-services.com/releases/client/3.5.6/TeamSpeak3-Client-linux_amd64-3.5.6.run -O /usr/local/teamspeak/install.run \
 && chmod +x /usr/local/teamspeak/install.run \
 && echo -ne "\ny" | (cd /usr/local/teamspeak/ && ./install.run)

COPY ./docker-fs /

ENV ENABLE_TEAMSPEAK false
ENV TS3SERVER ""
ENV TS3_APIKEY M2I6-MKSK-OCHP-JR0T-CY8L-T3H3

COPY --from=build_app /usr/src/app/target/clientv2-0.0.1-SNAPSHOT.jar /usr/local/musikbot/musikbot.jar
COPY --from=build_spotifyd /usr/src/spotifyd/target/release/spotifyd /usr/local/spotifyd/spotifyd
ENTRYPOINT ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
