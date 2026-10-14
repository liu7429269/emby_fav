package com.emby.service;

import com.emby.config.EmbyProperties;
import com.emby.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbyApiService {

    private static final int BUFFER_SIZE = 10 * 1024 * 1024;
    private static final String DEFAULT_FIELDS =
            "BasicSyncInfo,CanDelete,Container,PrimaryImageAspectRatio,ProductionYear,Prefix,"
                    + "Genres,Tags,UserData,Overview,CommunityRating,OfficialRating,ChildCount,ImageTags,"
                    + "Path,MediaSources";

    private static final Set<String> ALLOWED_SORT_BY = new HashSet<>(Arrays.asList(
            "CommunityRating", "Resolution", "DateCreated", "PremiereDate", "Size", "Container",
            "OfficialRating", "ProductionYear", "CriticRating", "PlayCount", "DatePlayed",
            "Runtime", "FileName", "SortName", "TotalBitrate"
    ));

    public static final String DEFAULT_SORT_BY = "SortName";
    public static final String DEFAULT_SORT_ORDER = "Ascending";

    private final EmbyProperties embyProperties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String accessToken;
    private String userId;

    /** 运行时服务器地址（由登录时指定，覆盖配置默认值），单用户场景安全 */
    private volatile String activeServerUrl;

    public void setActiveServerUrl(String url) {
        if (url != null && !url.isEmpty()) {
            this.activeServerUrl = url;
        } else {
            this.activeServerUrl = null;
        }
    }

    /** 当前生效的 Emby 服务器地址 */
    public String effectiveServerUrl() {
        return (activeServerUrl != null && !activeServerUrl.isEmpty()) ? activeServerUrl : embyProperties.getUrl();
    }

    public void authenticate() {
        authenticate(embyProperties.getUsername(), embyProperties.getPassword());
    }

    public EmbyAuthResponse authenticate(String username, String password) {
        return authenticate(effectiveServerUrl(), username, password);
    }

    public EmbyAuthResponse authenticate(String serverUrl, String username, String password) {
        try {
            if (serverUrl == null || serverUrl.isEmpty()) {
                serverUrl = effectiveServerUrl();
            }
            Map<String, String> authRequest = new HashMap<>();
            authRequest.put("Username", username);
            authRequest.put("Pw", password);

            WebClient webClient = webClientBuilder
                    .baseUrl(serverUrl)
                    .exchangeStrategies(largeStrategies())
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .defaultHeader("X-Emby-Client", "EmbyFavManager")
                    .defaultHeader("X-Emby-Client-Version", "1.0.0")
                    .defaultHeader("X-Emby-Device-Name", "EmbyFavManager")
                    .defaultHeader("X-Emby-Device-Id", "emby-fav-manager-001")
                    .build();

            String jsonResponse = webClient.post()
                    .uri("/emby/Users/authenticatebyname")
                    .bodyValue(authRequest)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(20));

            if (jsonResponse == null || jsonResponse.isEmpty()) {
                throw new RuntimeException("认证失败：服务器返回空响应");
            }

            EmbyAuthResponse response = objectMapper.readValue(jsonResponse, EmbyAuthResponse.class);
            if (response != null && response.getAccessToken() != null) {
                this.accessToken = response.getAccessToken();
                if (response.getUser() != null) {
                    this.userId = response.getUser().getId();
                }
                log.info("认证成功，用户ID: {}", userId);
                return response;
            }
            throw new RuntimeException("认证失败：未获取到访问令牌");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Emby认证失败", e);
            throw new RuntimeException("无法连接到Emby服务器或认证失败: " + e.getMessage(), e);
        }
    }

    public WebClient createWebClient(String token) {
        WebClient.Builder builder = webClientBuilder
                .baseUrl(effectiveServerUrl())
                .exchangeStrategies(largeStrategies())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-Emby-Client", "EmbyFavManager")
                .defaultHeader("X-Emby-Client-Version", "1.0.0")
                .defaultHeader("X-Emby-Device-Name", "EmbyFavManager")
                .defaultHeader("X-Emby-Device-Id", "emby-fav-manager-001");

        if (token != null && !token.isEmpty()) {
            builder.defaultHeader("X-Emby-Token", token);
        }
        return builder.build();
    }

    public WebClient createWebClientForImage(String token) {
        WebClient.Builder builder = webClientBuilder
                .baseUrl(effectiveServerUrl())
                .exchangeStrategies(largeStrategies())
                .defaultHeader("X-Emby-Client", "EmbyFavManager")
                .defaultHeader("X-Emby-Client-Version", "1.0.0")
                .defaultHeader("X-Emby-Device-Name", "EmbyFavManager")
                .defaultHeader("X-Emby-Device-Id", "emby-fav-manager-001");

        if (token != null && !token.isEmpty()) {
            builder.defaultHeader("X-Emby-Token", token);
        }
        return builder.build();
    }

    public List<EmbyLibrary> getLibraries(String token, String userId) {
        try {
            WebClient webClient = createWebClient(token);
            String jsonResponse = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/emby/Users/{userId}/Views").build(userId))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (jsonResponse == null || jsonResponse.isEmpty()) {
                throw new RuntimeException("获取媒体库失败：服务器返回空响应");
            }

            JsonNode jsonNode = objectMapper.readTree(jsonResponse);
            List<EmbyLibrary> libraries;
            if (jsonNode.isArray()) {
                libraries = objectMapper.convertValue(jsonNode,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, EmbyLibrary.class));
            } else if (jsonNode.has("Items")) {
                libraries = objectMapper.convertValue(jsonNode.get("Items"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, EmbyLibrary.class));
            } else {
                EmbyLibrary library = objectMapper.readValue(jsonResponse, EmbyLibrary.class);
                libraries = Collections.singletonList(library);
            }
            return libraries != null ? libraries : Collections.emptyList();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取媒体库失败", e);
            throw new RuntimeException("获取媒体库失败: " + e.getMessage(), e);
        }
    }

    /** 规范化 SortBy，非法值回退 SortName。 */
    public static String normalizeSortBy(String sortBy) {
        if (sortBy == null || sortBy.isEmpty()) {
            return DEFAULT_SORT_BY;
        }
        return ALLOWED_SORT_BY.contains(sortBy) ? sortBy : DEFAULT_SORT_BY;
    }

    /** 规范化 SortOrder。 */
    public static String normalizeSortOrder(String sortOrder) {
        if (sortOrder == null || sortOrder.isEmpty()) {
            return DEFAULT_SORT_ORDER;
        }
        String o = sortOrder.trim();
        if ("Descending".equalsIgnoreCase(o) || "Desc".equalsIgnoreCase(o)) {
            return "Descending";
        }
        return "Ascending";
    }

    /** 规范化播放状态：played / unplayed / resumable，非法返回 null。 */
    public static String normalizePlayStatus(String playStatus) {
        if (playStatus == null || playStatus.isEmpty()) {
            return null;
        }
        String s = playStatus.trim().toLowerCase();
        if ("played".equals(s) || "unplayed".equals(s) || "resumable".equals(s)) {
            return s;
        }
        return null;
    }

    /**
     * 浏览媒体项：支持文件夹、扁平电影墙、最爱、Tags、分页。
     */
    public EmbyItemsResponse getItems(String token, String userId, String parentId,
                                      boolean recursive, String includeItemTypes,
                                      String tags, Boolean isFavorite,
                                      int startIndex, int limit) {
        return getItems(token, userId, parentId, recursive, includeItemTypes,
                tags, null, isFavorite, null, startIndex, limit, DEFAULT_SORT_BY, DEFAULT_SORT_ORDER);
    }

    /**
     * 浏览媒体项：支持文件夹、扁平电影墙、最爱、Tags、分页与排序。
     */
    public EmbyItemsResponse getItems(String token, String userId, String parentId,
                                      boolean recursive, String includeItemTypes,
                                      String tags, Boolean isFavorite,
                                      int startIndex, int limit,
                                      String sortBy, String sortOrder) {
        return getItems(token, userId, parentId, recursive, includeItemTypes,
                tags, null, isFavorite, null, startIndex, limit, sortBy, sortOrder);
    }

    /**
     * 浏览媒体项：支持文件夹、扁平电影墙、最爱、Tags、播放状态、分页与排序。
     */
    public EmbyItemsResponse getItems(String token, String userId, String parentId,
                                      boolean recursive, String includeItemTypes,
                                      String tags, String excludeTags, Boolean isFavorite, String playStatus,
                                      int startIndex, int limit,
                                      String sortBy, String sortOrder) {
        return getItems(token, userId, parentId, recursive, includeItemTypes,
                tags, excludeTags, isFavorite, playStatus, startIndex, limit, sortBy, sortOrder, null, null);
    }

    public EmbyItemsResponse getItems(String token, String userId, String parentId,
                                      boolean recursive, String includeItemTypes,
                                      String tags, String excludeTags, Boolean isFavorite, String playStatus,
                                      int startIndex, int limit,
                                      String sortBy, String sortOrder, String searchTerm) {
        return getItems(token, userId, parentId, recursive, includeItemTypes,
                tags, excludeTags, isFavorite, playStatus, startIndex, limit, sortBy, sortOrder, searchTerm, null);
    }

    public EmbyItemsResponse getItems(String token, String userId, String parentId,
                                      boolean recursive, String includeItemTypes,
                                      String tags, String excludeTags, Boolean isFavorite, String playStatus,
                                      int startIndex, int limit,
                                      String sortBy, String sortOrder, String searchTerm, MediaInfoFilter mediaFilter) {
        String effectiveSortBy = normalizeSortBy(sortBy);
        String effectiveSortOrder = normalizeSortOrder(sortOrder);
        String effectivePlayStatus = normalizePlayStatus(playStatus);
        boolean needMemFilter = excludeTags != null && !excludeTags.isEmpty();
        int queryLimit = needMemFilter ? 10000 : (limit > 0 ? limit : 50);
        int queryStart = needMemFilter ? 0 : Math.max(0, startIndex);
        try {
            WebClient webClient = createWebClient(token);
            String jsonResponse = webClient.get()
                    .uri(uriBuilder -> {
                        UriBuilder builder = uriBuilder
                                .path("/emby/Users/{userId}/Items")
                                .queryParam("Recursive", recursive)
                                .queryParam("SortBy", effectiveSortBy)
                                .queryParam("SortOrder", effectiveSortOrder)
                                .queryParam("Fields", DEFAULT_FIELDS)
                                .queryParam("EnableImageTypes", "Primary,Backdrop,Thumb")
                                .queryParam("ImageTypeLimit", "1")
                                .queryParam("StartIndex", queryStart);

                        if (parentId != null && !parentId.isEmpty()) {
                            builder.queryParam("ParentId", parentId);
                        }
                        if (searchTerm != null && !searchTerm.isEmpty()) {
                            builder.queryParam("SearchTerm", searchTerm);
                        }
                        if (includeItemTypes != null && !includeItemTypes.isEmpty()) {
                            builder.queryParam("IncludeItemTypes", includeItemTypes);
                        }
                        if (tags != null && !tags.isEmpty()) {
                            builder.queryParam("Tags", tags);
                        }
                        if (excludeTags != null && !excludeTags.isEmpty()) {
                            builder.queryParam("ExcludeTags", excludeTags);
                        }
                        List<String> filters = new ArrayList<>();
                        if (Boolean.TRUE.equals(isFavorite)) {
                            filters.add("IsFavorite");
                        }
                        if ("played".equals(effectivePlayStatus)) {
                            filters.add("IsPlayed");
                        } else if ("unplayed".equals(effectivePlayStatus)) {
                            filters.add("IsUnplayed");
                        } else if ("resumable".equals(effectivePlayStatus)) {
                            filters.add("IsResumable");
                        }
                        if (!filters.isEmpty()) {
                            builder.queryParam("Filters", String.join(",", filters));
                        }
                        applyMediaInfoFilter(builder, mediaFilter);
                        builder.queryParam("Limit", queryLimit);

                        return builder.build(userId);
                    })
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (jsonResponse == null || jsonResponse.isEmpty()) {
                EmbyItemsResponse empty = new EmbyItemsResponse();
                empty.setItems(Collections.emptyList());
                empty.setTotalRecordCount(0);
                return empty;
            }

            EmbyItemsResponse response = objectMapper.readValue(jsonResponse, EmbyItemsResponse.class);
            if (response.getItems() == null) {
                response.setItems(Collections.emptyList());
            }
            if (response.getTotalRecordCount() == null) {
                response.setTotalRecordCount(response.getItems().size());
            }
            // 应用层排除标签过滤（Emby 旧版本可能不支持 ExcludeTags）
            if (needMemFilter) {
                String[] exArr = excludeTags.split("\\|");
                java.util.Set<String> exSet = new java.util.HashSet<>();
                for (String s : exArr) {
                    if (s != null && !s.isEmpty()) exSet.add(s.trim().toLowerCase());
                }
                List<EmbyMediaItem> filtered = new java.util.ArrayList<>();
                for (EmbyMediaItem it : response.getItems()) {
                    List<String> itTags = it.getTags();
                    boolean hit = false;
                    if (itTags != null) {
                        for (String tg : itTags) {
                            if (tg != null && exSet.contains(tg.trim().toLowerCase())) { hit = true; break; }
                        }
                    }
                    if (!hit) filtered.add(it);
                }
                int before = response.getItems().size();
                int total = filtered.size();
                int from = Math.max(0, startIndex);
                int to = Math.min(from + (limit > 0 ? limit : 50), total);
                int pageLen = from < to ? (to - from) : 0;
                response.setItems(from < to ? filtered.subList(from, to) : Collections.emptyList());
                response.setTotalRecordCount(total);
                log.info("应用层排除标签过滤: excludeTags={}, 过滤前={}, 过滤后={}, 当前页={}",
                        excludeTags, before, total, pageLen);
            }
            return response;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取媒体项失败", e);
            throw new RuntimeException("获取媒体项失败: " + e.getMessage(), e);
        }
    }

    private void applyMediaInfoFilter(UriBuilder builder, MediaInfoFilter mediaFilter) {
        if (mediaFilter == null || mediaFilter.isEmpty()) {
            return;
        }
        if (mediaFilter.getContainers() != null) {
            builder.queryParam("Containers", mediaFilter.getContainers());
        }
        if (mediaFilter.getVideoCodecs() != null) {
            builder.queryParam("VideoCodecs", mediaFilter.getVideoCodecs());
        }
        if (mediaFilter.getSubtitleLanguages() != null) {
            builder.queryParam("SubtitleLanguages", mediaFilter.getSubtitleLanguages());
        }
        if (mediaFilter.getHasSubtitles() != null) {
            builder.queryParam("HasSubtitles", mediaFilter.getHasSubtitles());
        }
        if (Boolean.TRUE.equals(mediaFilter.getIs4K())) {
            builder.queryParam("Is4K", true);
        }
        if (mediaFilter.getMinWidth() != null) {
            builder.queryParam("MinWidth", mediaFilter.getMinWidth());
        }
        if (mediaFilter.getMaxWidth() != null) {
            builder.queryParam("MaxWidth", mediaFilter.getMaxWidth());
        }
    }

    public List<EmbyTag> listFilterOptions(String token, String userId, String parentId, String path) {
        try {
            WebClient webClient = createWebClient(token);
            String jsonResponse = webClient.get()
                    .uri(uriBuilder -> {
                        UriBuilder builder = uriBuilder
                                .path(path)
                                .queryParam("UserId", userId)
                                .queryParam("Recursive", true);
                        if (parentId != null && !parentId.isEmpty()) {
                            builder.queryParam("ParentId", parentId);
                        }
                        return builder.build();
                    })
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return parseTagItems(jsonResponse);
        } catch (Exception e) {
            log.error("获取过滤选项失败 path={}", path, e);
            return Collections.emptyList();
        }
    }

    public Map<String, List<EmbyTag>> getMediaFilterOptions(String token, String userId, String parentId) {
        Map<String, List<EmbyTag>> options = new HashMap<>();
        options.put("containers", listFilterOptions(token, userId, parentId, "/emby/Containers"));
        options.put("videoCodecs", listFilterOptions(token, userId, parentId, "/emby/VideoCodecs"));
        options.put("languages", listFilterOptions(token, userId, parentId, "/emby/StreamLanguages"));
        return options;
    }

    private List<EmbyTag> parseTagItems(String jsonResponse) {
        if (jsonResponse == null || jsonResponse.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            JsonNode jsonNode = objectMapper.readTree(jsonResponse);
            List<EmbyTag> result;
            if (jsonNode.has("Items")) {
                result = objectMapper.convertValue(jsonNode.get("Items"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, EmbyTag.class));
            } else if (jsonNode.isArray()) {
                result = objectMapper.convertValue(jsonNode,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, EmbyTag.class));
            } else {
                return Collections.emptyList();
            }
            if (result == null) {
                return Collections.emptyList();
            }
            result.sort(java.util.Comparator.comparing(
                    t -> t.getName() == null ? "" : t.getName().toLowerCase()));
            return result;
        } catch (Exception e) {
            log.error("解析过滤选项失败", e);
            return Collections.emptyList();
        }
    }

    public List<EmbyMediaItem> listCollections(String token, String userId) {
        EmbyItemsResponse response = getItems(token, userId, null, true, "BoxSet",
                null, null, null, null, 0, 2000, DEFAULT_SORT_BY, DEFAULT_SORT_ORDER);
        return response.getItems() != null ? response.getItems() : Collections.emptyList();
    }

    public void addToCollection(String token, String collectionId, List<String> itemIds) {
        if (collectionId == null || collectionId.isEmpty()) {
            throw new IllegalArgumentException("合集 ID 不能为空");
        }
        if (itemIds == null || itemIds.isEmpty()) {
            throw new IllegalArgumentException("未选择媒体");
        }
        try {
            WebClient webClient = createWebClient(token);
            webClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/emby/Collections/{collectionId}/Items")
                            .queryParam("Ids", String.join(",", itemIds))
                            .build(collectionId))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
        } catch (Exception e) {
            log.error("添加到合集失败 collection={} items={}", collectionId, itemIds, e);
            throw new RuntimeException("添加到合集失败: " + e.getMessage(), e);
        }
    }

    public EmbyItemsResponse searchItems(String token, String userId, String searchTerm, String parentId,
                                         int startIndex, int limit) {
        return getItems(token, userId, parentId, true, "Movie,Series,BoxSet",
                null, null, null, null, startIndex, limit, DEFAULT_SORT_BY, DEFAULT_SORT_ORDER, searchTerm);
    }

    /** 兼容旧调用：库内扁平 Movie 列表 */
    public List<EmbyMediaItem> getMediaItems(String token, String userId, String libraryId,
                                             String tags, Boolean isFavorite) {
        EmbyItemsResponse response = getItems(token, userId, libraryId, true, "Movie,Series",
                tags, null, isFavorite, null, 0, 50, DEFAULT_SORT_BY, DEFAULT_SORT_ORDER);
        return response.getItems();
    }

    public List<EmbyGenre> getGenres(String token, String userId, String parentId) {
        try {
            WebClient webClient = createWebClient(token);
            String jsonResponse = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/emby/Genres")
                            .queryParam("ParentId", parentId)
                            .queryParam("UserId", userId)
                            .queryParam("SortBy", "SortName")
                            .queryParam("SortOrder", "Ascending")
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (jsonResponse == null || jsonResponse.isEmpty()) {
                return Collections.emptyList();
            }

            JsonNode jsonNode = objectMapper.readTree(jsonResponse);
            if (jsonNode.has("Items")) {
                return objectMapper.convertValue(jsonNode.get("Items"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, EmbyGenre.class));
            }
            if (jsonNode.isArray()) {
                return objectMapper.convertValue(jsonNode,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, EmbyGenre.class));
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("获取标签(Genres)失败", e);
            return Collections.emptyList();
        }
    }

    public EmbyMediaItem getItem(String token, String userId, String itemId) {
        try {
            WebClient webClient = createWebClient(token);
            String jsonResponse = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/emby/Users/{userId}/Items/{itemId}")
                            .queryParam("Fields",
                                    "Overview,Genres,Tags,People,ProductionYear,CommunityRating,"
                                            + "UserData,ImageTags,PrimaryImageAspectRatio,RunTimeTicks,ChildCount,"
                                            + "Path,MediaSources,Chapters")
                            .build(userId, itemId))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (jsonResponse == null || jsonResponse.isEmpty()) {
                throw new RuntimeException("获取详情失败：空响应");
            }
            return objectMapper.readValue(jsonResponse, EmbyMediaItem.class);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取媒体详情失败: {}", itemId, e);
            throw new RuntimeException("获取媒体详情失败: " + e.getMessage(), e);
        }
    }

    /** 取单个媒体项的文件路径（兜底用，列表已带 Path 时不必调） */
    public String getItemPath(String token, String userId, String itemId) {
        try {
            EmbyMediaItem item = getItem(token, userId, itemId);
            return item != null ? item.resolvePath() : null;
        } catch (Exception e) {
            log.warn("取路径失败 {}: {}", itemId, e.getMessage());
            return null;
        }
    }

    /** 获取 Emby 服务器 ID（System/Info），需带 token */
    public String getServerId(String token) {
        try {
            WebClient webClient = createWebClient(token);
            String json = webClient.get()
                    .uri("/emby/System/Info")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));
            if (json == null || json.isEmpty()) return null;
            JsonNode node = objectMapper.readTree(json);
            return node.has("Id") ? node.get("Id").asText() : null;
        } catch (Exception e) {
            log.warn("获取 ServerId 失败: {}", e.getMessage());
            return null;
        }
    }

    /** 获取所有 Tags（标签） */
    public List<EmbyTag> getTags(String token, String userId) {
        try {
            WebClient webClient = createWebClient(token);
            String jsonResponse = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/emby/Tags")
                            .queryParam("UserId", userId)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (jsonResponse == null || jsonResponse.isEmpty()) {
                return Collections.emptyList();
            }
            JsonNode jsonNode = objectMapper.readTree(jsonResponse);
            List<EmbyTag> result;
            if (jsonNode.has("Items")) {
                result = objectMapper.convertValue(jsonNode.get("Items"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, EmbyTag.class));
            } else if (jsonNode.isArray()) {
                result = objectMapper.convertValue(jsonNode,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, EmbyTag.class));
            } else {
                return Collections.emptyList();
            }
            result.sort(java.util.Comparator.comparing(
                    t -> t.getName() == null ? "" : t.getName().toLowerCase()));
            return result;
        } catch (Exception e) {
            log.error("获取Tags失败", e);
            return Collections.emptyList();
        }
    }

    /** 给媒体项新增标签（不存在则创建并打上），返回是否新增 */
    public boolean addTag(String token, String userId, String itemId, String tagName) {
        try {
            WebClient webClient = createWebClient(token);
            // 先检查是否已有该标签，避免重复
            List<EmbyTag> existing = getTags(token, userId);
            boolean already = existing != null && existing.stream().anyMatch(t -> tagName.equals(t.getName()));

            // 专用端点 POST /emby/Items/{id}/Tags/Add，body {"Tags":[{"Name":"..."}]}
            String body = String.format("{\"Tags\":[{\"Name\":\"%s\"}]}", escapeJson(tagName));
            webClient.post()
                    .uri(uriBuilder -> uriBuilder.path("/emby/Items/{itemId}/Tags/Add").build(itemId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return !already;
        } catch (Exception e) {
            log.error("添加标签失败 {} {}", itemId, tagName, e);
            throw new RuntimeException("添加标签失败: " + e.getMessage(), e);
        }
    }

    /** 删除媒体项上的指定标签，返回是否移除 */
    public boolean removeTag(String token, String userId, String itemId, String tagName) {
        try {
            WebClient webClient = createWebClient(token);
            // 取完整原始 JSON
            String json = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/emby/Users/{userId}/Items/{itemId}")
                            .queryParam("Fields",
                                    "Overview,Genres,Tags,People,ProductionYear,CommunityRating,"
                                            + "UserData,ImageTags,PrimaryImageAspectRatio,RunTimeTicks,ChildCount,"
                                            + "Path,MediaSources,Studios,ProviderIds,LockData,LockedFields,"
                                            + "Taglines,DateCreated,PremiereDate,OfficialRating,SortName,ForcedSortName")
                            .build(userId, itemId))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (json == null || json.isEmpty()) {
                throw new RuntimeException("取详情失败：空响应");
            }
            com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(json);

            // 当前标签：优先 TagItems（此版本实际返回），其次 Tags
            java.util.List<String> current = new java.util.ArrayList<>();
            if (root.has("TagItems") && root.get("TagItems").isArray()) {
                for (JsonNode t : root.get("TagItems")) {
                    if (t != null && t.has("Name") && t.get("Name").asText() != null) {
                        current.add(t.get("Name").asText());
                    }
                }
            } else if (root.has("Tags") && root.get("Tags").isArray()) {
                for (JsonNode t : root.get("Tags")) {
                    if (t != null) current.add(t.asText());
                }
            }
            boolean removed = current.remove(tagName);
            if (!removed) {
                return false;
            }

            // 关键：移除 TagItems / GenreItems 等运行期字段，避免回写时覆盖 Tags
            for (String f : new String[]{"TagItems", "GenreItems", "UserData", "MediaStreams",
                    "Chapters", "BackdropImageTags", "MediaSources", "ImageInfos",
                    "RemoteTrailers", "ProductionLocations", "ExternalUrls",
                    "LocalTrailerCount", "DisplayPreferencesId"}) {
                root.remove(f);
            }
            // 设置 Tags 为剩余名称数组
            com.fasterxml.jackson.databind.node.ArrayNode tagsNode = root.putArray("Tags");
            for (String n : current) {
                tagsNode.add(n);
            }

            webClient.post()
                    .uri(uriBuilder -> uriBuilder.path("/emby/Items/{itemId}").build(itemId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(objectMapper.writeValueAsString(root))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return true;
        } catch (Exception e) {
            log.error("删除标签失败 {} {}", itemId, tagName, e);
            throw new RuntimeException("删除标签失败: " + e.getMessage(), e);
        }
    }

    /** 标记为已播放 */
    public void markPlayed(String token, String userId, String itemId) {
        try {
            WebClient webClient = createWebClient(token);
            webClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/emby/Users/{userId}/PlayedItems/{itemId}")
                            .build(userId, itemId))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
        } catch (Exception e) {
            log.error("标记已播放失败 {}", itemId, e);
            throw new RuntimeException("标记已播放失败: " + e.getMessage(), e);
        }
    }

    /** 取消已播放 */
    public void markUnplayed(String token, String userId, String itemId) {
        try {
            WebClient webClient = createWebClient(token);
            webClient.delete()
                    .uri(uriBuilder -> uriBuilder
                            .path("/emby/Users/{userId}/PlayedItems/{itemId}")
                            .build(userId, itemId))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
        } catch (Exception e) {
            log.error("取消已播放失败 {}", itemId, e);
            throw new RuntimeException("取消已播放失败: " + e.getMessage(), e);
        }
    }

    /** 标记为收藏 */
    public void markFavorite(String token, String userId, String itemId) {
        try {
            WebClient webClient = createWebClient(token);
            webClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/emby/Users/{userId}/FavoriteItems/{itemId}")
                            .build(userId, itemId))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
        } catch (Exception e) {
            log.error("标记收藏失败 {}", itemId, e);
            throw new RuntimeException("标记收藏失败: " + e.getMessage(), e);
        }
    }

    /** 删除媒体项（Emby 会删文件、配套文件，独立文件夹整夹删除） */
    public void deleteItem(String token, String itemId) {
        try {
            WebClient webClient = createWebClient(token);
            webClient.delete()
                    .uri(uriBuilder -> uriBuilder
                            .path("/emby/Items/{itemId}")
                            .build(itemId))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
        } catch (Exception e) {
            log.error("删除媒体失败 {}", itemId, e);
            throw new RuntimeException("删除媒体失败: " + e.getMessage(), e);
        }
    }

    /** 取消收藏 */
    public void unmarkFavorite(String token, String userId, String itemId) {
        try {
            WebClient webClient = createWebClient(token);
            webClient.delete()
                    .uri(uriBuilder -> uriBuilder
                            .path("/emby/Users/{userId}/FavoriteItems/{itemId}")
                            .build(userId, itemId))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
        } catch (Exception e) {
            log.error("取消收藏失败 {}", itemId, e);
            throw new RuntimeException("取消收藏失败: " + e.getMessage(), e);
        }
    }

    /** 构建 Emby 直传流地址，供 PotPlayer 等本地播放器使用（Static=true，本机解码） */
    public String buildDirectStreamUrl(String serverUrl, String accessToken, String itemId) {
        if (serverUrl == null || serverUrl.isEmpty()) {
            throw new IllegalArgumentException("Emby 服务器地址未配置");
        }
        if (accessToken == null || accessToken.isEmpty()) {
            throw new IllegalArgumentException("未登录");
        }
        String base = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        return UriComponentsBuilder.fromHttpUrl(base)
                .path("/emby/Videos/{itemId}/stream")
                .queryParam("Static", true)
                .queryParam("api_key", accessToken)
                .buildAndExpand(itemId)
                .toUriString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private ExchangeStrategies largeStrategies() {
        return ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(BUFFER_SIZE))
                .build();
    }
}
