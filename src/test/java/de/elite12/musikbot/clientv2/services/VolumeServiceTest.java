package de.elite12.musikbot.clientv2.services;

import de.elite12.musikbot.clientv2.audio.SoftwareVolume;
import de.elite12.musikbot.clientv2.events.CommandEvent;
import de.elite12.musikbot.shared.dtos.VolumeCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VolumeServiceTest {

    @Test
    void commandUpdatesSoftwareVolumeAndClampsOutOfRangeValues() {
        SoftwareVolume volume = new SoftwareVolume();
        VolumeService service = new VolumeService(volume);

        service.onApplicationEvent(commandEvent(new VolumeCommand((short) -1)));
        assertEquals(0, volume.percent());

        service.onApplicationEvent(commandEvent(new VolumeCommand((short) 37)));
        assertEquals(37, volume.percent());

        service.onApplicationEvent(commandEvent(new VolumeCommand((short) 101)));
        assertEquals(100, volume.percent());
    }

    private CommandEvent commandEvent(VolumeCommand command) {
        return new CommandEvent(this, command);
    }
}
