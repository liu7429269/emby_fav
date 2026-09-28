package com.emby.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "emby.server")
public class EmbyProperties {
    private String url;
    private String username;
    private String password;
    private String apiKey;
}
