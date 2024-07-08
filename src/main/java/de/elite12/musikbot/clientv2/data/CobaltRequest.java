package de.elite12.musikbot.clientv2.data;


import lombok.Data;

@Data
public class CobaltRequest {
    public final String url;
    public final String aFormat = "best";
    public final boolean isAudioOnly = true;
}
