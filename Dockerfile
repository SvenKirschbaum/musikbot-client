# syntax=docker/dockerfile:1.26@sha256:ecfaec9ed6d810b56388c508f4121597bfbba70d41a6dfeee4d8cad5f295fc32

#BUILD APP
FROM maven:3.9.16-amazoncorretto-25@sha256:d203e5601a3fe7bb2c5cdbbe4aa778aaa95ab165a72c574b5cfcdae3ea525ae9 AS build_app
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
FROM rust:1.98.0-bookworm@sha256:e70e2eec3d495fd5c8e0be74adda86507dfac7f51a724fbf9813ff59b2b247c7 AS build_spotifyd
RUN apt-get update \
 && apt-get install -y --no-install-recommends libasound2-dev libssl-dev libpulse-dev libdbus-1-dev cmake libclang-dev \
 && rm -rf /var/lib/apt/lists/*
RUN git clone https://github.com/Spotifyd/spotifyd.git /usr/src/spotifyd && \
    git -C /usr/src/spotifyd fetch origin refs/pull/1374/head:tmp && \
    git -C /usr/src/spotifyd checkout tmp
WORKDIR /usr/src/spotifyd
RUN cargo build --release --no-default-features --features pulseaudio_backend

# PACKAGE DISCORD CLIENT
FROM debian:13.6-slim@sha256:3a39a0592364683e6bab97937b72cad5a8fa6dcbbee90edb3bb48c7f8e94f258

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
