#BUILD
FROM maven:3.6.3-jdk-11 AS build
WORKDIR /usr/src/app
COPY pom.xml .
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
WORKDIR /usr/local/teamspeak
RUN wget https://files.teamspeak-services.com/releases/client/3.3.2/TeamSpeak3-Client-linux_amd64-3.3.2.run -O ./install.run && \
    chmod +x ./install.run && \
    echo -ne "\ny" | ./install.run

WORKDIR /usr/local/spotifyd
RUN wget -qO- https://github.com/Spotifyd/spotifyd/releases/latest/download/spotifyd-linux-slim.tar.gz | tar xvz

COPY ./docker-fs /


COPY --from=build /usr/src/app/target/clientv2-0.0.1-SNAPSHOT.jar /usr/local/musikbot/musikbot.jar
ENTRYPOINT ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]