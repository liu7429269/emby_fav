package com.emby.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmbyTag {
    @JsonProperty("Name")
    private String name;

    @JsonProperty("Id")
    private String id;
}
