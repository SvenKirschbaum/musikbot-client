# syntax=docker/dockerfile:1.28@sha256:bb22d9815c728170f72750f4e5b0d672e06176142e1d602c7e66c050100b7e5b

#BUILD APP
FROM maven:3.10.0-amazoncorretto-25@sha256:ccc394dd4226c146c2e81f68b3d95e68227e9ffaa88fe04b9f22fd6552190e0b AS build_app
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
FROM rust:1.99.1-bookworm@sha256:9181b56c2a21ab1d5e73cffe04fc762426d30aa7fac3c91141dd033f49a7fc8b AS build_spotifyd
RUN apt-get update \
 && apt-get install -y --no-install-recommends libasound2-dev libssl-dev libpulse-dev libdbus-1-dev cmake libclang-dev \
 && rm -rf /var/lib/apt/lists/*
RUN git clone https://github.com/Spotifyd/spotifyd.git /usr/src/spotifyd && \
    git -C /usr/src/spotifyd fetch origin refs/pull/1374/head:tmp && \
    git -C /usr/src/spotifyd checkout tmp
WORKDIR /usr/src/spotifyd
RUN cargo build --release --no-default-features --features pulseaudio_backend

# PACKAGE DISCORD CLIENT
FROM debian:13.7-slim@sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4

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
