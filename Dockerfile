# syntax=docker/dockerfile:1.27@sha256:bde3983e9c939224420ddaf6b784cc30e09b035a4dea01f581230c50809f372e

#BUILD APP
FROM maven:3.9.16-amazoncorretto-25@sha256:490bf1b0b852f8ae833f134933f30ca38024e4db475b2db05ee58b2f819179f0 AS build_app
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
FROM rust:1.98.1-bookworm@sha256:ae1a730a949f727611a5c684e1e26e5a9bb9885b34f65a442744ca8a61c86ca5 AS build_spotifyd
RUN apt-get update \
 && apt-get install -y --no-install-recommends libasound2-dev libssl-dev libpulse-dev libdbus-1-dev cmake libclang-dev \
 && rm -rf /var/lib/apt/lists/*
RUN git clone https://github.com/Spotifyd/spotifyd.git /usr/src/spotifyd && \
    git -C /usr/src/spotifyd fetch origin refs/pull/1374/head:tmp && \
    git -C /usr/src/spotifyd checkout tmp
WORKDIR /usr/src/spotifyd
RUN cargo build --release --no-default-features --features pulseaudio_backend

# PACKAGE DISCORD CLIENT
FROM debian:13.7-slim@sha256:93b9a6764e5d7a53b6b3682cc370cbb85ace510ffe5f60b6a03821655a4b3e52

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
