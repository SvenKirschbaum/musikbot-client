package de.elite12.musikbot.clientv2.services;

import de.elite12.musikbot.clientv2.core.Clientv2ServiceProperties;
import de.elite12.musikbot.clientv2.events.SongFinishedEvent;
import de.elite12.musikbot.clientv2.events.StartSongEvent;
import de.elite12.musikbot.clientv2.events.StopSongEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/***
 * Updates Clients description in Teamspeak on a best effort basis. No errors are raised if the update fails, and success is not verified.
 */
@Service
@ConditionalOnProperty(
        value = "teamspeak.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class TeamspeakService{

    @Autowired
    private Clientv2ServiceProperties properties;

    private final Logger logger = LoggerFactory.getLogger(TeamspeakService.class);

    private void updateDescription(String description) {
        try (
                Socket socket = new Socket(InetAddress.getLoopbackAddress(),25639);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()))
        ) {
            socket.setSoTimeout(1000);
            writer.write(String.format("auth apikey=%s", properties.getTs3apikey()));
            writer.newLine();
            writer.write("whoami");
            writer.newLine();
            writer.flush();
            for(int i = 0; i<10; i++) logger.debug(reader.readLine());

            String clidline = reader.readLine();
            logger.debug(clidline);
            Matcher matcher = Pattern.compile(".*clid=(\\d+).*").matcher(clidline);
            if(!matcher.find()) throw new IllegalStateException("Unable to match clid");
            String clid = matcher.group(1);

            writer.write(String.format("clientvariable clid=%s client_database_id", clid));
            writer.newLine();
            writer.flush();

            for(int i = 0; i<3; i++) logger.debug(reader.readLine());

            String cldbidline = reader.readLine();
            logger.debug(cldbidline);
            Matcher matcher2 = Pattern.compile(".*client_database_id=(\\d+)").matcher(cldbidline);
            if(!matcher2.find()) throw new IllegalStateException("Unable to match cldbid");
            String cldbid = matcher2.group(1);

            writer.write(String.format("clientdbedit cldbid=%s client_description=%s",cldbid,description.replace(" ", "\\s")));
            writer.newLine();

            for(int i = 0; i<3; i++) logger.debug(reader.readLine());

        } catch (IllegalStateException | IOException e) {
            logger.debug("Exception updating client description", e);
        }
    }

    @EventListener
    public void onSongStart(StartSongEvent event) {
        this.updateDescription(event.getSong().getTitle());
    }

    @EventListener
    public void onSongStop(StopSongEvent event) {
        this.updateDescription("");
    }

    @EventListener
    public void onSongFinished(SongFinishedEvent event) {
        this.updateDescription("");
    }
}
