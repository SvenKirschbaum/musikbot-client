#BUILD APP
FROM maven:3.9.16-amazoncorretto-25@sha256:10a4c5d155c12014589a4d474901c36f1258f3f066fea2317a7722f42c6a2a25 AS build_app
WORKDIR /usr/src/app
COPY pom.xml .
COPY lib lib
RUN for file in ./lib/*; do mvn org.apache.maven.plugins:maven-install-plugin:install-file -Dfile=$file; done; mvn dependency:go-offline
COPY src/ ./src/
RUN mvn -f ./pom.xml package

#BUILD SPOTIFYD
FROM rust:1.97.0-bookworm@sha256:7d0723df719e7f213b69dc7c8c595985c3f4b060cfbee4f7bc0e347a86fe3b6a AS build_spotifyd
RUN apt-get update && apt-get install -y libasound2-dev libssl-dev libpulse-dev libdbus-1-dev cmake libclang-dev
RUN git clone https://github.com/Spotifyd/spotifyd.git /usr/src/spotifyd && \
    git -C /usr/src/spotifyd fetch origin refs/pull/1374/head:tmp && \
    git -C /usr/src/spotifyd checkout tmp
WORKDIR /usr/src/spotifyd
RUN cargo build --release --no-default-features --features pulseaudio_backend

#PACKAGE
FROM debian:13.5-slim@sha256:28de0877c2189802884ccd20f15ee41c203573bd87bb6b883f5f46362d24c5c2

ADD https://files.teamspeak-services.com/releases/client/3.5.6/TeamSpeak3-Client-linux_amd64-3.5.6.run /usr/local/teamspeak/install.run
ADD https://apt.corretto.aws/corretto.key /tmp/corretto.key

RUN \
    apt-get update \
 && apt-get install -y gnupg2 ca-certificates \
 && gpg --dearmor -o /usr/share/keyrings/corretto-keyring.gpg /tmp/corretto.key \
 && (echo "deb [signed-by=/usr/share/keyrings/corretto-keyring.gpg] https://apt.corretto.aws stable main" | tee /etc/apt/sources.list.d/corretto.list) \
 && apt-get update \
 && apt-get install -y \
    curl \
    java-25-amazon-corretto-jdk \
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

COPY ./docker-fs /

ENV ENABLE_TEAMSPEAK false
ENV TS3SERVER ""
ENV TS3_APIKEY M2I6-MKSK-OCHP-JR0T-CY8L-T3H3

COPY --from=build_app /usr/src/app/target/clientv2-0.0.1-SNAPSHOT.jar /usr/local/musikbot/musikbot.jar
COPY --from=build_spotifyd /usr/src/spotifyd/target/release/spotifyd /usr/local/spotifyd/spotifyd
ENTRYPOINT ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
