package com.emby.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmbyMediaItem {
    @JsonProperty("Id")
    private String id;
    
    @JsonProperty("Name")
    private String name;
    
    @JsonProperty("Type")
    private String type;
    
    @JsonProperty("Overview")
    private String overview;
    
    @JsonProperty("PremiereDate")
    private String premiereDate;
    
    @JsonProperty("ProductionYear")
    private String productionYear;
    
    @JsonProperty("CommunityRating")
    private String communityRating;
    
    @JsonProperty("OfficialRating")
    private String officialRating;
    
    @JsonProperty("Genres")
    private List<String> genres;

    @JsonProperty("Tags")
    private List<String> tags;

    @JsonProperty("TagItems")
    private List<TagItem> tagItems;

    /** 优先用 TagItems（此 Emby 版本实际返回），其次 Tags */
    public List<String> getTags() {
        if (tags != null && !tags.isEmpty()) {
            return tags;
        }
        if (tagItems != null && !tagItems.isEmpty()) {
            java.util.List<String> names = new java.util.ArrayList<>(tagItems.size());
            for (TagItem t : tagItems) {
                if (t != null && t.getName() != null) {
                    names.add(t.getName());
                }
            }
            return names;
        }
        return tags;
    }
    
    @JsonProperty("ImageInfos")
    private List<EmbyImageInfo> imageInfos;
    
    @JsonProperty("UserData")
    private UserDataInfo userDataInfo;
    
    @JsonProperty("SeriesId")
    private String seriesId;
    
    @JsonProperty("SeriesName")
    private String seriesName;
    
    @JsonProperty("ParentId")
    private String parentId;
    
    @JsonProperty("MediaType")
    private String mediaType;
    
    // 用于图片标签
    @JsonProperty("ImageTags")
    private Map<String, String> imageTags;
    
    @JsonProperty("Prefix")
    private String prefix;
    
    @JsonProperty("CanDelete")
    private Boolean canDelete;
    
    @JsonProperty("Container")
    private String container;
    
    @JsonProperty("PrimaryImageAspectRatio")
    private Double primaryImageAspectRatio;
    
    @JsonProperty("RunTimeTicks")
    private Long runTimeTicks;
    
    @JsonProperty("IsFolder")
    private Boolean isFolder;
    
    @JsonProperty("ServerId")
    private String serverId;
    
    @JsonProperty("SupportsSync")
    private Boolean supportsSync;

    @JsonProperty("Path")
    private String path;

    @JsonProperty("MediaSources")
    private List<MediaSource> mediaSources;

    @JsonProperty("Chapters")
    private List<Chapter> chapters;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MediaSource {
        @JsonProperty("Path")
        private String path;

        @JsonProperty("Container")
        private String container;

        @JsonProperty("Size")
        private Long size;

        @JsonProperty("DateCreated")
        private String dateCreated;

        @JsonProperty("MediaStreams")
        private List<MediaStream> mediaStreams;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MediaStream {
        @JsonProperty("Type")
        private String type;       // "Video" / "Audio"

        @JsonProperty("Codec")
        private String codec;      // "h264","aac"

        @JsonProperty("Width")
        private Integer width;

        @JsonProperty("Height")
        private Integer height;

        @JsonProperty("Channels")
        private Integer channels;

        @JsonProperty("Language")
        private String language;

        @JsonProperty("IsDefault")
        private Boolean isDefault;

        @JsonProperty("DisplayTitle")
        private String displayTitle;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TagItem {
        @JsonProperty("Name")
        private String name;

        @JsonProperty("Id")
        private String id;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Chapter {
        @JsonProperty("StartPositionTicks")
        private Long startPositionTicks;

        @JsonProperty("Name")
        private String name;

        @JsonProperty("ChapterIndex")
        private Integer chapterIndex;

        @JsonProperty("ImageTag")
        private String imageTag;

        /** ticks → "0:00" / "5:00" / "1:05:00" */
        public String getStartTimeText() {
            if (startPositionTicks == null || startPositionTicks < 0) {
                return "0:00";
            }
            long totalSeconds = startPositionTicks / 10_000_000L;
            long hours = totalSeconds / 3600;
            long minutes = (totalSeconds % 3600) / 60;
            long seconds = totalSeconds % 60;
            if (hours > 0) {
                return hours + ":" + twoDigits(minutes) + ":" + twoDigits(seconds);
            }
            return minutes + ":" + twoDigits(seconds);
        }

        private static String twoDigits(long n) {
            return n < 10 ? "0" + n : String.valueOf(n);
        }
    }

    /** 取本项文件路径：优先顶层 Path，其次 MediaSources[0].Path */
    public String resolvePath() {
        if (path != null && !path.isEmpty()) {
            return path;
        }
        if (mediaSources != null && !mediaSources.isEmpty()) {
            MediaSource ms = mediaSources.get(0);
            if (ms != null && ms.getPath() != null && !ms.getPath().isEmpty()) {
                return ms.getPath();
            }
        }
        return null;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmbyImageInfo {
        @JsonProperty("ImageType")
        private String imageType;
        
        @JsonProperty("Path")
        private String path;
    }
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UserDataInfo {
        @JsonProperty("IsFavorite")
        private Boolean isFavorite;
        
        @JsonProperty("PlayedPercentage")
        private Double playedPercentage;
        
        @JsonProperty("PlayCount")
        private Integer playCount;
        
        @JsonProperty("Played")
        private Boolean played;
    }

    /** 取首个 MediaSource，便于模板访问 container/dateCreated */
    public MediaSource getPrimaryMediaSource() {
        if (mediaSources != null && !mediaSources.isEmpty()) {
            return mediaSources.get(0);
        }
        return null;
    }

    /** 取首个 MediaSource 的 MediaStreams，无则 null */
    private List<MediaStream> getPrimaryStreams() {
        MediaSource ms = getPrimaryMediaSource();
        return ms != null ? ms.getMediaStreams() : null;
    }

    /** 格式化时长：RunTimeTicks → "1h 23m" / "45m" / "1m"，无则 null */
    public String getRuntimeText() {
        if (runTimeTicks == null || runTimeTicks <= 0) return null;
        long totalSeconds = runTimeTicks / 10_000_000L;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return hours + "h " + (minutes > 0 ? minutes + "m" : "");
        }
        if (minutes > 0) {
            return minutes + "m" + (seconds > 0 ? " " + seconds + "s" : "");
        }
        return seconds + "s";
    }

    /** 视频流摘要：首条 Video stream → "4K H264" / "1080p H264"，无则 null */
    public String getVideoSummary() {
        List<MediaStream> streams = getPrimaryStreams();
        if (streams == null) return null;
        for (MediaStream s : streams) {
            if ("Video".equalsIgnoreCase(s.getType())) {
                String res;
                Integer w = s.getWidth();
                Integer h = s.getHeight();
                if (w != null && w >= 3840) res = "4K";
                else if (h != null && h >= 2160) res = "4K";
                else if (h != null && h > 0) res = h + "p";
                else if (w != null && w > 0) res = w + "x";
                else res = null;
                String codec = s.getCodec() != null ? s.getCodec().toUpperCase() : null;
                if (res != null && codec != null) return res + " " + codec;
                if (res != null) return res;
                if (codec != null) return codec;
                return s.getDisplayTitle();
            }
        }
        return null;
    }

    /** 音频流摘要：首条 Audio stream → "Und AAC stereo (Default)"，无则 null */
    public String getAudioSummary() {
        List<MediaStream> streams = getPrimaryStreams();
        if (streams == null) return null;
        for (MediaStream s : streams) {
            if ("Audio".equalsIgnoreCase(s.getType())) {
                StringBuilder sb = new StringBuilder();
                String lang = s.getLanguage();
                sb.append(lang != null && !lang.isEmpty() ? lang : "Und");
                if (s.getCodec() != null && !s.getCodec().isEmpty()) {
                    sb.append(" ").append(s.getCodec().toUpperCase());
                }
                Integer ch = s.getChannels();
                if (ch != null && ch > 0) {
                    if (ch == 1) sb.append(" mono");
                    else if (ch == 2) sb.append(" stereo");
                    else sb.append(" ").append(ch).append("ch");
                }
                if (Boolean.TRUE.equals(s.getIsDefault())) sb.append(" (Default)");
                return sb.toString();
            }
        }
        return null;
    }

    /** 文件大小格式化：size bytes → "294 MB" / "1.2 GB"，无则 null */
    public String getSizeText() {
        MediaSource ms = getPrimaryMediaSource();
        if (ms == null || ms.getSize() == null || ms.getSize() <= 0) return null;
        long bytes = ms.getSize();
        double mb = bytes / (1024.0 * 1024.0);
        if (mb < 1024) return String.format("%.0f MB", mb);
        double gb = mb / 1024.0;
        return String.format("%.1f GB", gb);
    }
}
