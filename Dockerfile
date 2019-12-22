#BUILD
FROM maven:3.6.3-jdk-11 AS build
WORKDIR /usr/src/app
COPY pom.xml .
COPY ./libmusikbot-0.0.1-SNAPSHOT.jar /usr/lib/libmusikbot/libmusikbot-0.0.1-SNAPSHOT.jar
RUN mvn org.apache.maven.plugins:maven-install-plugin:install-file -Dfile=/usr/lib/libmusikbot/libmusikbot-0.0.1-SNAPSHOT.jar
RUN mvn org.apache.maven.plugins:maven-dependency-plugin:3.0.2:go-offline
COPY . .
RUN mvn -f ./pom.xml clean package

#PACKAGE
FROM openjdk:13-buster
RUN apt-get update && apt-get install -y\
    wget \
    xvfb \
    pulseaudio \
    supervisor \
    vlc \
    libglib2.0-0 \
    libgl1 \
    libnss3 \
    libfreetype6 \
    libfontconfig1 \
    libxcomposite1 \
    libxcursor1 \
    libxi6 \
    libxtst6 \
    libxss1 \
    libpci3 \
    libasound2 \
    libdbus-1-3 \
    libxslt1.1 \
    libegl1 \
    libxkbcommon0

RUN rm -f /usr/lib/x86_64-linux-gnu/vlc/lua/playlist/youtube.luac
RUN wget -q https://raw.githubusercontent.com/videolan/vlc/master/share/lua/playlist/youtube.lua -O /usr/lib/x86_64-linux-gnu/vlc/lua/playlist/youtube.lua

WORKDIR /usr/local/teamspeak
RUN wget -q https://files.teamspeak-services.com/releases/client/3.3.2/TeamSpeak3-Client-linux_amd64-3.3.2.run -O ./install.run && \
    chmod +x ./install.run && \
    echo -ne "\ny" | ./install.run

WORKDIR /usr/local/spotifyd
RUN wget -qO- https://github.com/Spotifyd/spotifyd/releases/latest/download/spotifyd-linux-slim.tar.gz | tar xvz

WORKDIR /

COPY ./docker-fs /


COPY --from=build /usr/src/app/target/clientv2-0.0.1-SNAPSHOT.jar /usr/local/musikbot/musikbot.jar
ENTRYPOINT ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]