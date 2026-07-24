# syntax=docker/dockerfile:1.25@sha256:0adf442eae370b6087e08edc7c50b552d80ddf261576f4ebd6421006b2461f12

#BUILD APP
FROM maven:3.9.16-amazoncorretto-25@sha256:4de04d5fe425efd2a5c21ea6c3c53f9f2c4c1381f1d7890d203d237c83fbc816 AS build_app
WORKDIR /usr/src/app
RUN dnf install -y binutils && dnf clean all
COPY pom.xml .
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    mvn -s /root/.m2/settings.xml dependency:go-offline
COPY src/ ./src/
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    mvn -s /root/.m2/settings.xml -f ./pom.xml package
RUN "$JAVA_HOME/bin/jlink" \
    --add-modules java.se,jdk.crypto.ec,jdk.net,jdk.unsupported,jdk.zipfs \
    --strip-debug \
    --no-header-files \
    --no-man-pages \
    --compress=zip-6 \
    --output /opt/corretto-jre
RUN /opt/corretto-jre/bin/java --describe-module jdk.net

#BUILD SPOTIFYD
FROM rust:1.97.1-bookworm@sha256:77fac8b98f9f46062bb680b6d25d5bcaabfc400143952ebc572e924bcbedc3fa AS build_spotifyd
RUN apt-get update \
 && apt-get install -y --no-install-recommends libasound2-dev libssl-dev libpulse-dev libdbus-1-dev cmake libclang-dev \
 && rm -rf /var/lib/apt/lists/*
RUN git clone https://github.com/Spotifyd/spotifyd.git /usr/src/spotifyd && \
    git -C /usr/src/spotifyd fetch origin refs/pull/1374/head:tmp && \
    git -C /usr/src/spotifyd checkout tmp
WORKDIR /usr/src/spotifyd
RUN cargo build --release --no-default-features --features pulseaudio_backend

# PACKAGE DISCORD CLIENT
FROM debian:13.6-slim@sha256:020c0d20b9880058cbe785a9db107156c3c75c2ac944a6aa7ab59f2add76a7bd

RUN \
    apt-get update \
 && apt-get install -y --no-install-recommends \
    ca-certificates \
    libasound2 \
    libdbus-1-3 \
    pulseaudio \
    supervisor \
 && rm -rf /var/lib/apt/lists/*

COPY ./docker-fs/etc /etc

ENV JAVA_HOME=/opt/corretto
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=build_app /opt/corretto-jre /opt/corretto
COPY --from=build_app /usr/src/app/target/clientv2-0.0.1-SNAPSHOT.jar /usr/local/musikbot/musikbot.jar
COPY --from=build_spotifyd /usr/src/spotifyd/target/release/spotifyd /usr/local/spotifyd/spotifyd
ENTRYPOINT ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
