package com.emby.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmbyItemsResponse {
    @JsonProperty("Items")
    private List<EmbyMediaItem> items;
    
    @JsonProperty("TotalRecordCount")
    private Integer totalRecordCount;
}
