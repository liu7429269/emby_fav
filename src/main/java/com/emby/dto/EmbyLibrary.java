package com.emby.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmbyLibrary {
    @JsonProperty("Id")
    private String id;
    
    @JsonProperty("Name")
    private String name;
    
    @JsonProperty("CollectionType")
    private String collectionType;
    
    @JsonProperty("Path")
    private String path;
    
    @JsonProperty("Type")
    private String type;
    
    @JsonProperty("ParentId")
    private String parentId;

    @JsonProperty("ChildCount")
    private Integer childCount;

    @JsonProperty("ImageTags")
    private Map<String, String> imageTags;

    @JsonProperty("PrimaryImageAspectRatio")
    private Double primaryImageAspectRatio;
}
