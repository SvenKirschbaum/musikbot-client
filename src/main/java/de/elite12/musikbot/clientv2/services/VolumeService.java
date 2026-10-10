package de.elite12.musikbot.clientv2.services;

import de.elite12.musikbot.clientv2.audio.SoftwareVolume;
import de.elite12.musikbot.clientv2.events.CommandEvent;
import de.elite12.musikbot.shared.dtos.VolumeCommand;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

@Service
public class VolumeService implements ApplicationListener<CommandEvent> {

    private final SoftwareVolume volume;

    public VolumeService(SoftwareVolume volume) {
        this.volume = volume;
    }

    @Override
    public void onApplicationEvent(CommandEvent event) {
        if (event.getCommand() instanceof VolumeCommand command) {
            volume.setPercent(command.getVolume());
        }
    }
}
