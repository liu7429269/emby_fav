package com.emby.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmbyAuthResponse {
    // Emby API返回的字段名可能是 "User" 或 "user"
    @JsonProperty("User")
    private EmbyUser user;
    
    // Emby API返回的字段名可能是 "AccessToken" 或 "accessToken"
    @JsonProperty("AccessToken")
    private String accessToken;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmbyUser {
        // Emby API返回的字段名可能是 "Id" 或 "id"
        @JsonProperty("Id")
        private String id;
        
        // Emby API返回的字段名可能是 "Name" 或 "name"
        @JsonProperty("Name")
        private String name;
    }
}
