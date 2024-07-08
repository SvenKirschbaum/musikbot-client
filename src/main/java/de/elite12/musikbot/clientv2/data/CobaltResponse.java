package de.elite12.musikbot.clientv2.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class CobaltResponse {
    public String status;
    public String url;
}
