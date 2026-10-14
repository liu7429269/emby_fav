package com.emby.service;

import com.emby.config.EmbyProperties;
import com.emby.dto.EmbyItemsResponse;
import com.emby.dto.EmbyLibrary;
import com.emby.dto.EmbyMediaItem;
import com.emby.dto.EmbyTag;
import com.emby.dto.MediaInfoFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;

import javax.servlet.http.HttpSession;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrowsePageService {

    private static final List<Integer> PAGE_SIZES = Arrays.asList(200, 500, 1000, 2000, 5000);
    private static final Set<Integer> PAGE_SIZE_SET = new HashSet<>(PAGE_SIZES);
    private static final int DEFAULT_LIMIT = 200;
    private static final String DEFAULT_PLAY_STATUS = "unplayed";

    private final EmbyApiService embyApiService;
    private final EmbyProperties embyProperties;

    public boolean isLoggedIn(HttpSession session) {
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        return accessToken != null && !accessToken.isEmpty() && userId != null && !userId.isEmpty();
    }

    public void fillLogin(HttpSession session, Model model) {
        String sessionUrl = (String) session.getAttribute("embyServerUrl");
        model.addAttribute("embyServerUrl", sessionUrl != null ? sessionUrl : embyProperties.getUrl());
        model.addAttribute("defaultUsername", embyProperties.getUsername() != null ? embyProperties.getUsername() : "");
        model.addAttribute("defaultPassword", embyProperties.getPassword() != null ? embyProperties.getPassword() : "");
    }

    public void fillLibraries(HttpSession session, Model model) {
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        List<EmbyLibrary> libraries = embyApiService.getLibraries(accessToken, userId);
        model.addAttribute("libraries", libraries);
        model.addAttribute("userName", session.getAttribute("embyUserName"));
        model.addAttribute("searchScope", "all");
        model.addAttribute("searchQuery", "");
    }

    public void fillMedia(String libraryId, String parentId, String tags, String excludeTags,
                          Boolean isFavorite, String playStatus, int startIndex, int limit,
                          String flat, String view, String sortBy, String sortOrder,
                          String container, String videoCodec, String resolution, String subtitle,
                          String folderQ, HttpSession session, Model model) {
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        int effectiveLimit = normalizeLimit(limit);
        String effectiveSortBy = EmbyApiService.normalizeSortBy(sortBy);
        String effectiveSortOrder = EmbyApiService.normalizeSortOrder(sortOrder);
        String viewMode = resolveView(view, flat);
        boolean wantCollection = "collection".equals(viewMode);
        boolean wantFlat = "movie".equals(viewMode);
        String folderQuery = blankToNull(folderQ);
        boolean folderNameSearch = "folder".equals(viewMode) && folderQuery != null;
        boolean folderView = "folder".equals(viewMode);
        String effectivePlayStatus = wantCollection ? null : resolvePlayStatus(playStatus);
        // 文件夹浏览不按播放状态筛；否则默认「未播放」会让 hasFilters 为真，误走电影平铺查询
        String queryPlayStatus = (wantCollection || folderView) ? null : effectivePlayStatus;
        String selectedContainer = blankToNull(container);
        String selectedVideoCodec = blankToNull(videoCodec);
        String selectedResolution = normalizeResolution(resolution);
        String selectedSubtitle = blankToNull(subtitle);
        MediaInfoFilter mediaFilter = wantCollection
                ? new MediaInfoFilter()
                : MediaInfoFilter.from(selectedContainer, selectedVideoCodec, selectedResolution, selectedSubtitle);

        String effectiveParent = (parentId != null && !parentId.isEmpty()) ? parentId : libraryId;
        boolean hasFilters = (tags != null && !tags.isEmpty())
                || (excludeTags != null && !excludeTags.isEmpty())
                || Boolean.TRUE.equals(isFavorite)
                || queryPlayStatus != null
                || (!folderView && !mediaFilter.isEmpty());
        boolean recursive;
        String includeTypes;
        String searchTerm = null;
        MediaInfoFilter queryFilter = mediaFilter;

        EmbyLibrary currentLibrary = findLibrary(accessToken, userId, libraryId);
        if (folderNameSearch) {
            recursive = true;
            includeTypes = "Folder";
            searchTerm = folderQuery;
            queryFilter = null;
        } else if (wantCollection && effectiveParent.equals(libraryId)) {
            recursive = true;
            includeTypes = "BoxSet";
            effectiveParent = libraryId;
        } else if (wantFlat || hasFilters) {
            recursive = true;
            includeTypes = "Movie,Series";
            if (wantFlat) {
                effectiveParent = libraryId;
            }
        } else if (currentLibrary != null && "boxsets".equalsIgnoreCase(currentLibrary.getCollectionType())
                && effectiveParent.equals(libraryId)) {
            recursive = false;
            includeTypes = null;
        } else if (effectiveParent.equals(libraryId)) {
            recursive = false;
            includeTypes = null;
        } else {
            recursive = false;
            includeTypes = null;
        }

        EmbyItemsResponse itemsResponse = embyApiService.getItems(
                accessToken, userId, effectiveParent, recursive, includeTypes,
                folderNameSearch ? null : tags,
                folderNameSearch ? null : excludeTags,
                folderNameSearch ? null : isFavorite,
                folderNameSearch ? null : queryPlayStatus,
                startIndex, effectiveLimit,
                effectiveSortBy, effectiveSortOrder, searchTerm, queryFilter);

        if (!folderNameSearch && !recursive && !hasFilters && !wantFlat && !wantCollection && effectiveParent.equals(libraryId)
                && (itemsResponse.getItems() == null || itemsResponse.getItems().isEmpty())
                && currentLibrary != null && !"boxsets".equalsIgnoreCase(currentLibrary.getCollectionType())) {
            recursive = true;
            includeTypes = "Movie,Series";
            itemsResponse = embyApiService.getItems(
                    accessToken, userId, effectiveParent, true, includeTypes,
                    tags, excludeTags, isFavorite, queryPlayStatus, startIndex, effectiveLimit,
                    effectiveSortBy, effectiveSortOrder, null, mediaFilter);
        }

        String folderParentId = null;
        if (folderView && !folderNameSearch && !effectiveParent.equals(libraryId)) {
            try {
                EmbyMediaItem currentFolder = embyApiService.getItem(accessToken, userId, effectiveParent);
                if (currentFolder != null && currentFolder.getParentId() != null
                        && !currentFolder.getParentId().isEmpty()) {
                    folderParentId = currentFolder.getParentId();
                }
            } catch (Exception e) {
                log.warn("获取上级文件夹失败 parent={}", effectiveParent, e);
            }
        }

        List<EmbyTag> tagList = embyApiService.getTags(accessToken, userId);
        List<EmbyLibrary> libraries = embyApiService.getLibraries(accessToken, userId);

        model.addAttribute("mediaItems", itemsResponse.getItems());
        model.addAttribute("totalRecordCount", itemsResponse.getTotalRecordCount());
        model.addAttribute("tags", tagList);
        model.addAttribute("libraries", libraries);
        model.addAttribute("selectedLibraryId", libraryId);
        model.addAttribute("parentId", effectiveParent);
        model.addAttribute("selectedTags", tags);
        model.addAttribute("selectedExcludeTags", excludeTags);
        model.addAttribute("isFavorite", isFavorite);
        model.addAttribute("playStatus", effectivePlayStatus != null ? effectivePlayStatus : "");
        model.addAttribute("startIndex", startIndex);
        model.addAttribute("limit", effectiveLimit);
        model.addAttribute("browseRecursive", recursive);
        model.addAttribute("wantFlat", wantFlat);
        model.addAttribute("wantCollection", wantCollection);
        model.addAttribute("viewMode", viewMode);
        model.addAttribute("searchScope", libraryId);
        model.addAttribute("searchQuery", "");
        model.addAttribute("folderQuery", folderQuery != null ? folderQuery : "");
        model.addAttribute("folderParentId", folderParentId);
        model.addAttribute("sortBy", effectiveSortBy);
        model.addAttribute("sortOrder", effectiveSortOrder);
        model.addAttribute("selectedContainer", selectedContainer != null ? selectedContainer : "");
        model.addAttribute("selectedVideoCodec", selectedVideoCodec != null ? selectedVideoCodec : "");
        model.addAttribute("selectedResolution", selectedResolution != null ? selectedResolution : "");
        model.addAttribute("selectedSubtitle", selectedSubtitle != null ? selectedSubtitle : "");
        model.addAttribute("hasMediaFilter", !mediaFilter.isEmpty()
                || selectedContainer != null
                || selectedVideoCodec != null
                || selectedResolution != null
                || selectedSubtitle != null);
        boolean hasBasicFilter = (tags != null && !tags.isEmpty())
                || (excludeTags != null && !excludeTags.isEmpty())
                || Boolean.TRUE.equals(isFavorite)
                || (effectivePlayStatus != null && !effectivePlayStatus.isEmpty());
        model.addAttribute("hasAnyFilter", hasBasicFilter
                || Boolean.TRUE.equals(model.getAttribute("hasMediaFilter")));
        model.addAttribute("pageSizes", PAGE_SIZES);
        model.addAttribute("currentLibrary", currentLibrary);
        model.addAttribute("embyServerUrl", embyProperties.getUrl());
        model.addAttribute("userName", session.getAttribute("embyUserName"));
        if (libraryId != null && !libraryId.isEmpty()) {
            session.setAttribute("embyBrowseLibraryId", libraryId);
        }
    }

    public void fillDetail(String itemId, String libraryId, String parentId, String flat,
                           String tags, String excludeTags, Boolean isFavorite, String playStatus,
                           Integer startIndex, Integer limit, String sortBy, String sortOrder,
                           String container, String videoCodec, String resolution, String subtitle,
                           HttpSession session, Model model) {
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        EmbyMediaItem item = embyApiService.getItem(accessToken, userId, itemId);
        List<EmbyTag> tagList = embyApiService.getTags(accessToken, userId);
        model.addAttribute("item", item);
        model.addAttribute("tags", tagList);
        model.addAttribute("libraryId", libraryId);
        model.addAttribute("parentId", parentId);
        model.addAttribute("flat", flat);
        model.addAttribute("selectedTags", tags);
        model.addAttribute("selectedExcludeTags", excludeTags);
        model.addAttribute("isFavorite", isFavorite);
        model.addAttribute("playStatus", playStatus != null ? playStatus : "");
        model.addAttribute("startIndex", startIndex);
        model.addAttribute("limit", limit);
        model.addAttribute("sortBy", sortBy);
        model.addAttribute("sortOrder", sortOrder);
        model.addAttribute("selectedContainer", container != null ? container : "");
        model.addAttribute("selectedVideoCodec", videoCodec != null ? videoCodec : "");
        model.addAttribute("selectedResolution", resolution != null ? resolution : "");
        model.addAttribute("selectedSubtitle", subtitle != null ? subtitle : "");
        model.addAttribute("embyServerUrl", embyProperties.getUrl());
        model.addAttribute("userName", session.getAttribute("embyUserName"));
        model.addAttribute("libraries", embyApiService.getLibraries(accessToken, userId));
        model.addAttribute("searchScope", "all");
        model.addAttribute("searchQuery", "");
        if (libraryId != null && !libraryId.isEmpty()) {
            session.setAttribute("embyBrowseLibraryId", libraryId);
        }
    }

    public void fillSearch(String q, String scope, int startIndex, int limit,
                           HttpSession session, Model model) {
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        String query = q != null ? q.trim() : "";
        String effectiveScope = (scope != null && !scope.isEmpty()) ? scope : "all";
        int effectiveLimit = normalizeLimit(limit);
        List<EmbyLibrary> libraries = embyApiService.getLibraries(accessToken, userId);
        EmbyItemsResponse itemsResponse;
        if (query.isEmpty()) {
            itemsResponse = new EmbyItemsResponse();
            itemsResponse.setItems(Collections.emptyList());
            itemsResponse.setTotalRecordCount(0);
        } else {
            String parentId = "all".equalsIgnoreCase(effectiveScope) ? null : effectiveScope;
            itemsResponse = embyApiService.searchItems(
                    accessToken, userId, query, parentId, startIndex, effectiveLimit);
        }
        model.addAttribute("mediaItems", itemsResponse.getItems());
        model.addAttribute("totalRecordCount", itemsResponse.getTotalRecordCount());
        model.addAttribute("libraries", libraries);
        model.addAttribute("searchQuery", query);
        model.addAttribute("searchScope", effectiveScope);
        model.addAttribute("startIndex", startIndex);
        model.addAttribute("limit", effectiveLimit);
        model.addAttribute("userName", session.getAttribute("embyUserName"));
    }

    public int normalizeLimit(int limit) {
        return PAGE_SIZE_SET.contains(limit) ? limit : DEFAULT_LIMIT;
    }

    public String resolveView(String view, String flat) {
        if (view != null && !view.isEmpty()) {
            String v = view.trim().toLowerCase();
            if ("collection".equals(v) || "boxset".equals(v) || "boxsets".equals(v)) {
                return "collection";
            }
            if ("folder".equals(v) || "0".equals(v)) {
                return "folder";
            }
            if ("movie".equals(v) || "1".equals(v)) {
                return "movie";
            }
        }
        return isMovieView(flat) ? "movie" : "folder";
    }

    public boolean isMovieView(String flat) {
        if (flat == null || flat.isEmpty()) {
            return true;
        }
        if ("0".equals(flat) || "false".equalsIgnoreCase(flat) || "folder".equalsIgnoreCase(flat)) {
            return false;
        }
        return true;
    }

    public String resolvePlayStatus(String playStatus) {
        if (playStatus == null) {
            return DEFAULT_PLAY_STATUS;
        }
        return EmbyApiService.normalizePlayStatus(playStatus);
    }

    private String blankToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeResolution(String resolution) {
        String value = blankToNull(resolution);
        if (value == null) {
            return null;
        }
        String r = value.toLowerCase();
        if ("4k".equals(r) || "2160p".equals(r) || "uhd".equals(r)) {
            return "4k";
        }
        if ("1080p".equals(r) || "1080".equals(r)) {
            return "1080p";
        }
        if ("720p".equals(r) || "720".equals(r)) {
            return "720p";
        }
        if ("480p".equals(r) || "480".equals(r) || "sd".equals(r)) {
            return "480p";
        }
        return null;
    }

    private EmbyLibrary findLibrary(String token, String userId, String libraryId) {
        try {
            List<EmbyLibrary> libraries = embyApiService.getLibraries(token, userId);
            if (libraries == null || libraryId == null) {
                return null;
            }
            return libraries.stream()
                    .filter(l -> libraryId.equals(l.getId()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("查找媒体库失败: {}", e.getMessage());
            return null;
        }
    }
}
