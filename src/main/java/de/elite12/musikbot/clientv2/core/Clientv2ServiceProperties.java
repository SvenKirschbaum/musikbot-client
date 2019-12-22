package de.elite12.musikbot.clientv2.core;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Musikbot Configuration
 */
@Component
@EnableConfigurationProperties
@ConfigurationProperties(prefix="musikbot")
@Getter
@Setter
public class Clientv2ServiceProperties {
	
	/**
	 * Key used to authenticate Client
	 */
	private String clientkey;

	/**
	 * Server URL to connect to
	 */
	private String serverurl;

	/**
	 * Spotify Client Id
	 */
	private String spotifyapiid;

	/**
	 * Spotify Client Secret
	 */
	private String spotifyapisecret;

	/**
	 * Spotify Refresh Token
	 */
	private String spotifyrefreshtoken;
}
