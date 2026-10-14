package com.emby.dto;

import lombok.Data;

@Data
public class MediaInfoFilter {
    private String containers;
    private String videoCodecs;
    private String subtitleLanguages;
    private Boolean hasSubtitles;
    private Boolean is4K;
    private Integer minWidth;
    private Integer maxWidth;

    public boolean isEmpty() {
        return isBlank(containers)
                && isBlank(videoCodecs)
                && isBlank(subtitleLanguages)
                && hasSubtitles == null
                && !Boolean.TRUE.equals(is4K)
                && minWidth == null
                && maxWidth == null;
    }

    public static MediaInfoFilter from(String container, String videoCodec, String resolution, String subtitle) {
        MediaInfoFilter filter = new MediaInfoFilter();
        filter.setContainers(blankToNull(container));
        filter.setVideoCodecs(blankToNull(videoCodec));
        applyResolution(filter, resolution);
        applySubtitle(filter, subtitle);
        return filter;
    }

    private static void applyResolution(MediaInfoFilter filter, String resolution) {
        if (resolution == null || resolution.trim().isEmpty()) {
            return;
        }
        String r = resolution.trim().toLowerCase();
        if ("4k".equals(r) || "2160p".equals(r) || "uhd".equals(r)) {
            filter.setIs4K(true);
        } else if ("1080p".equals(r) || "1080".equals(r)) {
            filter.setMinWidth(1920);
            filter.setMaxWidth(3839);
        } else if ("720p".equals(r) || "720".equals(r)) {
            filter.setMinWidth(1280);
            filter.setMaxWidth(1919);
        } else if ("480p".equals(r) || "480".equals(r) || "sd".equals(r)) {
            filter.setMaxWidth(1279);
        }
    }

    private static void applySubtitle(MediaInfoFilter filter, String subtitle) {
        if (subtitle == null || subtitle.trim().isEmpty()) {
            return;
        }
        String s = subtitle.trim();
        if ("has".equalsIgnoreCase(s) || "true".equalsIgnoreCase(s)) {
            filter.setHasSubtitles(true);
        } else {
            filter.setSubtitleLanguages(s);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
}
