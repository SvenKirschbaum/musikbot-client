#BUILD
FROM maven:3.6.3-jdk-14 AS build
WORKDIR /usr/src/app
COPY pom.xml .
COPY ./libmusikbot-0.0.1-SNAPSHOT.jar /usr/lib/libmusikbot/libmusikbot-0.0.1-SNAPSHOT.jar
RUN mvn org.apache.maven.plugins:maven-install-plugin:install-file -Dfile=/usr/lib/libmusikbot/libmusikbot-0.0.1-SNAPSHOT.jar
RUN mvn dependency:go-offline
COPY src/ ./src/
RUN mvn -f ./pom.xml package

#PACKAGE
FROM openjdk:14-buster
RUN apt-get update \
 && apt-get install -y \
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
    wget \
    xvfb \
 && rm -rf /var/lib/apt/lists/* \
 && rm -f /usr/lib/x86_64-linux-gnu/vlc/lua/playlist/youtube.luac \
 && wget -q https://raw.githubusercontent.com/videolan/vlc/master/share/lua/playlist/youtube.lua -O /usr/lib/x86_64-linux-gnu/vlc/lua/playlist/youtube.lua \
 && mkdir -p /usr/local/teamspeak \
 && wget -q https://files.teamspeak-services.com/releases/client/3.5.3/TeamSpeak3-Client-linux_amd64-3.5.3.run -O /usr/local/teamspeak/install.run \
 && chmod +x /usr/local/teamspeak/install.run \
 && echo -ne "\ny" | (cd /usr/local/teamspeak/ && ./install.run) \
 && mkdir -p /usr/local/spotifyd \
 && wget -qO- https://github.com/Spotifyd/spotifyd/releases/latest/download/spotifyd-linux-slim.tar.gz | tar -C /usr/local/spotifyd/ -xvz

COPY ./docker-fs /

ENV TS3_APIKEY M2I6-MKSK-OCHP-JR0T-CY8L-T3H3
COPY --from=build /usr/src/app/target/clientv2-0.0.1-SNAPSHOT.jar /usr/local/musikbot/musikbot.jar
ENTRYPOINT ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]