# syntax=docker/dockerfile:1.28@sha256:bb22d9815c728170f72750f4e5b0d672e06176142e1d602c7e66c050100b7e5b

#BUILD APP
FROM maven:3.10.0-amazoncorretto-25@sha256:da0f0354bbe233537c3bf33cdf907536ae9b178dfd7c41e3eb9f815b7f8f14c9 AS build_app
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
FROM rust:1.99.1-bookworm@sha256:e12566d5abc17a0da741628782cdb504b68b8961f5bfcf677936bf3e8b32de29 AS build_spotifyd
RUN apt-get update \
 && apt-get install -y --no-install-recommends libasound2-dev libssl-dev libpulse-dev libdbus-1-dev cmake libclang-dev \
 && rm -rf /var/lib/apt/lists/*
RUN git clone https://github.com/Spotifyd/spotifyd.git /usr/src/spotifyd && \
    git -C /usr/src/spotifyd fetch origin refs/pull/1374/head:tmp && \
    git -C /usr/src/spotifyd checkout tmp
WORKDIR /usr/src/spotifyd
RUN cargo build --release --no-default-features --features pulseaudio_backend

# PACKAGE DISCORD CLIENT
FROM debian:13.7-slim@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f

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
