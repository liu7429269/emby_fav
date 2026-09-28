package com.emby.controller;

import com.emby.config.EmbyProperties;
import com.emby.dto.EmbyGenre;
import com.emby.dto.EmbyItemsResponse;
import com.emby.dto.EmbyLibrary;
import com.emby.dto.EmbyMediaItem;
import com.emby.dto.EmbyTag;
import com.emby.service.EmbyApiService;
import com.emby.service.FileMoveService;
import com.emby.service.BatchTagService;
import com.emby.service.BatchFavoriteService;
import com.emby.service.BatchDeleteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Controller
@RequiredArgsConstructor
public class EmbyController {

    private static final List<Integer> PAGE_SIZES = Arrays.asList(200, 500, 1000, 2000, 5000);
    private static final Set<Integer> PAGE_SIZE_SET = new HashSet<>(PAGE_SIZES);
    private static final int DEFAULT_LIMIT = 200;
    private static final String DEFAULT_PLAY_STATUS = "unplayed";

    private final EmbyApiService embyApiService;
    private final EmbyProperties embyProperties;
    private final FileMoveService fileMoveService;
    private final BatchTagService batchTagService;
    private final BatchFavoriteService batchFavoriteService;
    private final BatchDeleteService batchDeleteService;

    @GetMapping("/")
    public String index(HttpSession session) {
        String accessToken = (String) session.getAttribute("embyAccessToken");
        if (accessToken == null || accessToken.isEmpty()) {
            return "redirect:/login";
        }
        return "redirect:/libraries";
    }

    @GetMapping("/login")
    public String login(HttpSession session, Model model) {
        String accessToken = (String) session.getAttribute("embyAccessToken");
        if (accessToken != null && !accessToken.isEmpty()) {
            return "redirect:/libraries";
        }
        String sessionUrl = (String) session.getAttribute("embyServerUrl");
        model.addAttribute("embyServerUrl", sessionUrl != null ? sessionUrl : embyProperties.getUrl());
        model.addAttribute("defaultUsername", embyProperties.getUsername() != null ? embyProperties.getUsername() : "");
        model.addAttribute("defaultPassword", embyProperties.getPassword() != null ? embyProperties.getPassword() : "");
        return "login";
    }

    @PostMapping("/login")
    public String doLogin(
            @RequestParam String username,
            @RequestParam String password,
            HttpSession session,
            RedirectAttributes redirectAttributes,
            Model model) {
        try {
            com.emby.dto.EmbyAuthResponse authResponse = embyApiService.authenticate(username, password);
            if (authResponse != null && authResponse.getAccessToken() != null) {
                session.setAttribute("embyAccessToken", authResponse.getAccessToken());
                if (authResponse.getUser() != null) {
                    session.setAttribute("embyUserId", authResponse.getUser().getId());
                    session.setAttribute("embyUserName", authResponse.getUser().getName());
                }
                return "redirect:/libraries";
            }
            model.addAttribute("error", "登录失败：未获取到访问令牌");
            model.addAttribute("embyServerUrl", embyProperties.getUrl());
            return "login";
        } catch (Exception e) {
            log.error("登录失败", e);
            model.addAttribute("error", "登录失败: " + e.getMessage());
            model.addAttribute("embyServerUrl", embyProperties.getUrl());
            return "login";
        }
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }

    @GetMapping("/libraries")
    public String libraries(HttpSession session, Model model) {
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            return "redirect:/login";
        }
        try {
            List<EmbyLibrary> libraries = embyApiService.getLibraries(accessToken, userId);
            model.addAttribute("libraries", libraries);
            model.addAttribute("userName", session.getAttribute("embyUserName"));
            model.addAttribute("searchScope", "all");
            model.addAttribute("searchQuery", "");
            return "libraries";
        } catch (Exception e) {
            log.error("获取媒体库失败", e);
            model.addAttribute("error", "获取媒体库失败: " + e.getMessage());
            return "error";
        }
    }

    @GetMapping("/media")
    public String media(
            @RequestParam String libraryId,
            @RequestParam(required = false) String parentId,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false) String excludeTags,
            @RequestParam(required = false) Boolean isFavorite,
            @RequestParam(required = false) String playStatus,
            @RequestParam(required = false, defaultValue = "0") int startIndex,
            @RequestParam(required = false, defaultValue = "200") int limit,
            @RequestParam(required = false) String flat,
            @RequestParam(required = false) String view,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder,
            HttpSession session,
            Model model) {
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            return "redirect:/login";
        }
        try {
            int effectiveLimit = normalizeLimit(limit);
            String effectiveSortBy = EmbyApiService.normalizeSortBy(sortBy);
            String effectiveSortOrder = EmbyApiService.normalizeSortOrder(sortOrder);
            String viewMode = resolveView(view, flat);
            boolean wantCollection = "collection".equals(viewMode);
            boolean wantFlat = "movie".equals(viewMode);
            String effectivePlayStatus = wantCollection ? null : resolvePlayStatus(playStatus);

            String effectiveParent = (parentId != null && !parentId.isEmpty()) ? parentId : libraryId;
            boolean hasFilters = (tags != null && !tags.isEmpty())
                    || (excludeTags != null && !excludeTags.isEmpty())
                    || Boolean.TRUE.equals(isFavorite)
                    || effectivePlayStatus != null;
            boolean recursive;
            String includeTypes;

            EmbyLibrary currentLibrary = findLibrary(accessToken, userId, libraryId);
            if (wantCollection && effectiveParent.equals(libraryId)) {
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
                // 文件夹内：非递归展示直接子项（子文件夹 + 影片）
                recursive = false;
                includeTypes = null;
            }

            EmbyItemsResponse itemsResponse = embyApiService.getItems(
                    accessToken, userId, effectiveParent, recursive, includeTypes,
                    tags, excludeTags, isFavorite, effectivePlayStatus, startIndex, effectiveLimit,
                    effectiveSortBy, effectiveSortOrder);

            // 库根无文件夹时回退到扁平电影墙
            if (!recursive && !hasFilters && !wantFlat && !wantCollection && effectiveParent.equals(libraryId)
                    && (itemsResponse.getItems() == null || itemsResponse.getItems().isEmpty())
                    && currentLibrary != null && !"boxsets".equalsIgnoreCase(currentLibrary.getCollectionType())) {
                recursive = true;
                includeTypes = "Movie,Series";
                itemsResponse = embyApiService.getItems(
                        accessToken, userId, effectiveParent, true, includeTypes,
                        tags, excludeTags, isFavorite, effectivePlayStatus, startIndex, effectiveLimit,
                        effectiveSortBy, effectiveSortOrder);
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
            model.addAttribute("sortBy", effectiveSortBy);
            model.addAttribute("sortOrder", effectiveSortOrder);
            model.addAttribute("pageSizes", PAGE_SIZES);
            model.addAttribute("currentLibrary", currentLibrary);
            model.addAttribute("embyServerUrl", embyProperties.getUrl());
            model.addAttribute("userName", session.getAttribute("embyUserName"));
            return "media";
        } catch (Exception e) {
            log.error("获取媒体项失败", e);
            model.addAttribute("error", "获取媒体项失败: " + e.getMessage());
            return "error";
        }
    }

    @GetMapping("/media/item/{itemId}")
    public String mediaDetail(@PathVariable String itemId,
                              @RequestParam(required = false) String libraryId,
                              @RequestParam(required = false) String parentId,
                              @RequestParam(required = false) String flat,
                              @RequestParam(required = false) String tags,
                              @RequestParam(required = false) String excludeTags,
                              @RequestParam(required = false) Boolean isFavorite,
                              @RequestParam(required = false) String playStatus,
                              @RequestParam(required = false) Integer startIndex,
                              @RequestParam(required = false) Integer limit,
                              @RequestParam(required = false) String sortBy,
                              @RequestParam(required = false) String sortOrder,
                              HttpSession session,
                              Model model) {
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            return "redirect:/login";
        }
        try {
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
            model.addAttribute("embyServerUrl", embyProperties.getUrl());
            model.addAttribute("userName", session.getAttribute("embyUserName"));
            model.addAttribute("libraries", embyApiService.getLibraries(accessToken, userId));
            model.addAttribute("searchScope", "all");
            model.addAttribute("searchQuery", "");
            return "detail";
        } catch (Exception e) {
            log.error("获取详情失败", e);
            model.addAttribute("error", "获取详情失败: " + e.getMessage());
            return "error";
        }
    }

    @GetMapping("/search")
    public String search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false, defaultValue = "0") int startIndex,
            @RequestParam(required = false, defaultValue = "200") int limit,
            HttpSession session,
            Model model) {
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            return "redirect:/login";
        }
        try {
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
            return "search";
        } catch (Exception e) {
            log.error("搜索失败", e);
            model.addAttribute("error", "搜索失败: " + e.getMessage());
            return "error";
        }
    }

    @PostMapping("/select-library")
    public String selectLibrary(@RequestParam String libraryId) {
        return "redirect:/media?libraryId=" + libraryId;
    }

    @PostMapping("/api/login")
    @ResponseBody
    public Map<String, Object> apiLogin(
            @RequestParam(required = false) String server,
            @RequestParam String username,
            @RequestParam String password,
            HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        try {
            String serverUrl = (server != null && !server.isEmpty()) ? server : embyProperties.getUrl();
            // 设置本次会话使用的服务器地址
            embyApiService.setActiveServerUrl(serverUrl);
            session.setAttribute("embyServerUrl", serverUrl);
            com.emby.dto.EmbyAuthResponse authResponse = embyApiService.authenticate(serverUrl, username, password);
            if (authResponse != null && authResponse.getAccessToken() != null) {
                session.setAttribute("embyAccessToken", authResponse.getAccessToken());
                if (authResponse.getUser() != null) {
                    session.setAttribute("embyUserId", authResponse.getUser().getId());
                    session.setAttribute("embyUserName", authResponse.getUser().getName());
                }
                String serverId = embyApiService.getServerId(authResponse.getAccessToken());
                if (serverId != null && !serverId.isEmpty()) {
                    session.setAttribute("embyServerId", serverId);
                }
                result.put("success", true);
                result.put("message", "登录成功");
                result.put("redirect", "/libraries");
            } else {
                result.put("success", false);
                result.put("message", "登录失败：未获取到访问令牌");
            }
        } catch (Exception e) {
            log.error("登录失败", e);
            result.put("success", false);
            result.put("message", "登录失败: " + e.getMessage());
        }
        return result;
    }

    @GetMapping("/api/libraries")
    @ResponseBody
    public Map<String, Object> apiGetLibraries(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            result.put("success", false);
            result.put("message", "未登录，请先登录");
            result.put("redirect", "/login");
            return result;
        }
        try {
            result.put("success", true);
            result.put("libraries", embyApiService.getLibraries(accessToken, userId));
        } catch (Exception e) {
            log.error("获取媒体库失败", e);
            result.put("success", false);
            result.put("message", "获取媒体库失败: " + e.getMessage());
        }
        return result;
    }

    @PostMapping("/api/select-library")
    @ResponseBody
    public Map<String, Object> apiSelectLibrary(@RequestParam String libraryId) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("redirect", "/media?libraryId=" + libraryId);
        return result;
    }

    @GetMapping("/api/media")
    @ResponseBody
    public Map<String, Object> apiGetMedia(
            @RequestParam String libraryId,
            @RequestParam(required = false) String parentId,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false) String excludeTags,
            @RequestParam(required = false) String isFavorite,
            @RequestParam(required = false) String playStatus,
            @RequestParam(required = false, defaultValue = "0") int startIndex,
            @RequestParam(required = false, defaultValue = "200") int limit,
            @RequestParam(required = false) String browse,
            @RequestParam(required = false) String flat,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder,
            HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            result.put("success", false);
            result.put("message", "未登录，请先登录");
            result.put("redirect", "/login");
            return result;
        }
        try {
            int effectiveLimit = normalizeLimit(limit);
            String effectiveSortBy = EmbyApiService.normalizeSortBy(sortBy);
            String effectiveSortOrder = EmbyApiService.normalizeSortOrder(sortOrder);
            String effectivePlayStatus = resolvePlayStatus(playStatus);

            String effectiveParent = (parentId != null && !parentId.isEmpty()) ? parentId : libraryId;
            Boolean fav = null;
            if (isFavorite != null && !isFavorite.isEmpty()) {
                fav = Boolean.parseBoolean(isFavorite);
            }
            boolean wantFlat = isMovieView(flat);
            boolean hasFilters = (tags != null && !tags.isEmpty())
                    || (excludeTags != null && !excludeTags.isEmpty())
                    || Boolean.TRUE.equals(fav)
                    || effectivePlayStatus != null;
            boolean recursive;
            String includeTypes;
            if (wantFlat) {
                recursive = true;
                includeTypes = "Movie,Series";
                effectiveParent = libraryId;
            } else if ("folders".equalsIgnoreCase(browse)) {
                recursive = false;
                includeTypes = null;
            } else if ("items".equalsIgnoreCase(browse) || hasFilters || !effectiveParent.equals(libraryId)) {
                recursive = true;
                includeTypes = "Movie,Series";
            } else {
                recursive = false;
                includeTypes = null;
            }

            EmbyItemsResponse itemsResponse = embyApiService.getItems(
                    accessToken, userId, effectiveParent, recursive, includeTypes,
                    tags, excludeTags, fav, effectivePlayStatus, startIndex, effectiveLimit,
                    effectiveSortBy, effectiveSortOrder);

            if (!recursive && !hasFilters && !wantFlat && effectiveParent.equals(libraryId)
                    && (itemsResponse.getItems() == null || itemsResponse.getItems().isEmpty())) {
                EmbyLibrary lib = findLibrary(accessToken, userId, libraryId);
                if (lib == null || !"boxsets".equalsIgnoreCase(lib.getCollectionType())) {
                    itemsResponse = embyApiService.getItems(
                            accessToken, userId, effectiveParent, true, "Movie,Series",
                            tags, excludeTags, fav, effectivePlayStatus, startIndex, effectiveLimit,
                            effectiveSortBy, effectiveSortOrder);
                    recursive = true;
                }
            }

            List<EmbyTag> tagList = embyApiService.getTags(accessToken, userId);

            result.put("success", true);
            result.put("mediaItems", itemsResponse.getItems());
            result.put("totalRecordCount", itemsResponse.getTotalRecordCount());
            result.put("tags", tagList);
            result.put("selectedLibraryId", libraryId);
            result.put("parentId", effectiveParent);
            result.put("selectedTags", tags);
            result.put("selectedExcludeTags", excludeTags);
            result.put("isFavorite", fav);
            result.put("playStatus", effectivePlayStatus != null ? effectivePlayStatus : "");
            result.put("startIndex", startIndex);
            result.put("limit", effectiveLimit);
            result.put("browseRecursive", recursive);
            result.put("wantFlat", wantFlat);
            result.put("viewMode", wantFlat ? "movie" : "folder");
            result.put("sortBy", effectiveSortBy);
            result.put("sortOrder", effectiveSortOrder);
            result.put("pageSizes", PAGE_SIZES);
        } catch (Exception e) {
            log.error("获取媒体项失败", e);
            result.put("success", false);
            result.put("message", "获取媒体项失败: " + e.getMessage());
        }
        return result;
    }

    @GetMapping("/api/media/{itemId}")
    @ResponseBody
    public Map<String, Object> apiGetMediaItem(@PathVariable String itemId, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        try {
            result.put("success", true);
            result.put("item", embyApiService.getItem(accessToken, userId, itemId));
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    @GetMapping("/api/tags")
    @ResponseBody
    public Map<String, Object> apiGetTags(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        try {
            result.put("success", true);
            result.put("tags", embyApiService.getTags(accessToken, userId));
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    @PostMapping("/api/media/{itemId}/tags")
    @ResponseBody
    public Map<String, Object> apiAddTag(@PathVariable String itemId, @RequestBody Map<String, Object> body, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        try {
            String tag = body.get("tag") != null ? body.get("tag").toString().trim() : "";
            if (tag.isEmpty()) {
                result.put("success", false);
                result.put("message", "标签名不能为空");
                return result;
            }
            boolean added = embyApiService.addTag(accessToken, userId, itemId, tag);
            result.put("success", true);
            result.put("added", added);
            result.put("message", added ? "已添加标签" : "该标签已存在");
        } catch (Exception e) {
            log.error("添加标签失败", e);
            result.put("success", false);
            result.put("message", "添加标签失败: " + e.getMessage());
        }
        return result;
    }

    @PostMapping("/api/media/{itemId}/tags/delete")
    @ResponseBody
    public Map<String, Object> apiDeleteTag(@PathVariable String itemId, @RequestBody Map<String, Object> body, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        try {
            String tag = body.get("tag") != null ? body.get("tag").toString().trim() : "";
            if (tag.isEmpty()) {
                result.put("success", false);
                result.put("message", "标签名不能为空");
                return result;
            }
            boolean removed = embyApiService.removeTag(accessToken, userId, itemId, tag);
            result.put("success", true);
            result.put("removed", removed);
            result.put("message", removed ? "已删除标签" : "该标签不存在");
        } catch (Exception e) {
            log.error("删除标签失败", e);
            result.put("success", false);
            result.put("message", "删除标签失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 切换已播放状态。body: { "played": true|false }
     */
    @PostMapping("/api/media/{itemId}/played")
    @ResponseBody
    public Map<String, Object> apiSetPlayed(@PathVariable String itemId,
                                            @RequestBody Map<String, Object> body,
                                            HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        try {
            boolean played = body.get("played") == null
                    || Boolean.parseBoolean(body.get("played").toString());
            if (played) {
                embyApiService.markPlayed(accessToken, userId, itemId);
            } else {
                embyApiService.markUnplayed(accessToken, userId, itemId);
            }
            result.put("success", true);
            result.put("played", played);
            result.put("message", played ? "已标记为已播放" : "已标记为未播放");
        } catch (Exception e) {
            log.error("设置播放状态失败", e);
            result.put("success", false);
            result.put("message", "设置播放状态失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 切换收藏状态。body: { "favorite": true|false }
     */
    @PostMapping("/api/media/{itemId}/favorite")
    @ResponseBody
    public Map<String, Object> apiSetFavorite(@PathVariable String itemId,
                                              @RequestBody Map<String, Object> body,
                                              HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        try {
            boolean favorite = body.get("favorite") != null
                    && Boolean.parseBoolean(body.get("favorite").toString());
            if (favorite) {
                embyApiService.markFavorite(accessToken, userId, itemId);
            } else {
                embyApiService.unmarkFavorite(accessToken, userId, itemId);
            }
            result.put("success", true);
            result.put("favorite", favorite);
            result.put("message", favorite ? "已收藏" : "已取消收藏");
        } catch (Exception e) {
            log.error("设置收藏状态失败", e);
            result.put("success", false);
            result.put("message", "设置收藏状态失败: " + e.getMessage());
        }
        return result;
    }

    /** 获取 PotPlayer 播放用的 Emby 直传流地址 */
    @GetMapping("/api/media/{itemId}/potplayer-url")
    @ResponseBody
    public Map<String, Object> apiPotPlayerUrl(@PathVariable String itemId, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        try {
            String serverUrl = (String) session.getAttribute("embyServerUrl");
            if (serverUrl == null || serverUrl.isEmpty()) {
                serverUrl = embyProperties.getUrl();
            }
            String streamUrl = embyApiService.buildDirectStreamUrl(serverUrl, accessToken, itemId);
            result.put("success", true);
            result.put("streamUrl", streamUrl);
        } catch (Exception e) {
            log.error("获取 PotPlayer 播放地址失败 itemId={}", itemId, e);
            result.put("success", false);
            result.put("message", "获取播放地址失败: " + e.getMessage());
        }
        return result;
    }

    @GetMapping("/api/image/{itemId}")
    public ResponseEntity<byte[]> getImage(
            @PathVariable String itemId,
            @RequestParam(required = false, defaultValue = "Primary") String type,
            @RequestParam(required = false) Integer maxHeight,
            @RequestParam(required = false) Integer index,
            HttpSession session) {
        try {
            String accessToken = (String) session.getAttribute("embyAccessToken");
            if (accessToken == null || accessToken.isEmpty()) {
                return ResponseEntity.status(401).build();
            }

            boolean chapter = "Chapter".equalsIgnoreCase(type);
            String imageType = chapter ? "Chapter" : ("Backdrop".equalsIgnoreCase(type) ? "Backdrop" : "Primary");
            int height = maxHeight != null ? maxHeight : (chapter ? 180 : ("Backdrop".equals(imageType) ? 720 : 400));
            int chapterIndex = index != null && index >= 0 ? index : 0;

            WebClient webClient = embyApiService.createWebClientForImage(accessToken);
            byte[] imageData = webClient.get()
                    .uri(uriBuilder -> {
                        if (chapter) {
                            return uriBuilder
                                    .path("/emby/Items/{itemId}/Images/Chapter/{index}")
                                    .queryParam("maxHeight", height)
                                    .queryParam("quality", 90)
                                    .build(itemId, chapterIndex);
                        }
                        return uriBuilder
                                .path("/emby/Items/{itemId}/Images/{imageType}")
                                .queryParam("maxHeight", height)
                                .queryParam("quality", 90)
                                .build(itemId, imageType);
                    })
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();

            if (imageData == null || imageData.length == 0) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                    .body(imageData);
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            if (e.getStatusCode() != null && e.getStatusCode().value() == 404) {
                log.debug("媒体无封面图: {} ({})", itemId, e.getMessage());
            } else {
                log.error("获取图片失败: {}", itemId, e);
            }
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("获取图片失败: {}", itemId, e);
            return ResponseEntity.notFound().build();
        }
    }

    private boolean isLoggedIn(String accessToken, String userId) {
        return accessToken != null && !accessToken.isEmpty() && userId != null && !userId.isEmpty();
    }

    /** view 优先；否则 flat 缺省/1 → 电影，flat=0 → 文件夹。 */
    private String resolveView(String view, String flat) {
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

    /** flat 缺省/空/1/true → 电影模式；仅 0/false → 文件夹模式 */
    private boolean isMovieView(String flat) {
        if (flat == null || flat.isEmpty()) {
            return true;
        }
        if ("0".equals(flat) || "false".equalsIgnoreCase(flat) || "folder".equalsIgnoreCase(flat)) {
            return false;
        }
        return true;
    }

    private int normalizeLimit(int limit) {
        return PAGE_SIZE_SET.contains(limit) ? limit : DEFAULT_LIMIT;
    }

    /** 未传 playStatus 默认未播放；空字符串表示「任意」。 */
    private String resolvePlayStatus(String playStatus) {
        if (playStatus == null) {
            return DEFAULT_PLAY_STATUS;
        }
        return EmbyApiService.normalizePlayStatus(playStatus);
    }

    private EmbyLibrary findLibrary(String token, String userId, String libraryId) {
        try {
            List<EmbyLibrary> libraries = embyApiService.getLibraries(token, userId);
            if (libraries == null) {
                return null;
            }
            return libraries.stream()
                    .filter(l -> libraryId.equals(l.getId()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 列出媒体根下子目录：传 itemIds 由后端检测根，或直接传 root。
     */
    @PostMapping("/api/library/subdirs")
    @ResponseBody
    public Map<String, Object> subdirs(@RequestBody Map<String, Object> body, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        try {
            String root = body.get("root") != null ? body.get("root").toString() : null;
            String libraryId = body.get("libraryId") != null ? body.get("libraryId").toString() : null;
            boolean hadRemote = false;
            log.info("subdirs 请求: root={}, libraryId={}, itemIds={}",
                    root, libraryId, body.get("itemIds"));
            if (root == null || root.isEmpty()) {
                // 用库内采样检测库媒体根（而非所选文件当前文件夹），便于在库内子目录间移动
                List<String> paths = new ArrayList<>();
                if (libraryId != null && !libraryId.isEmpty()) {
                    EmbyItemsResponse sample = embyApiService.getItems(
                            accessToken, userId, libraryId, true, "Movie,Series", null, null, 0, 30);
                    if (sample != null && sample.getItems() != null) {
                        for (EmbyMediaItem it : sample.getItems()) {
                            String p = it.resolvePath();
                            if (p == null) {
                                log.debug("subdirs 采样项无 Path: id={}", it.getId());
                                continue;
                            }
                            if (fileMoveService.isRemotePath(p)) {
                                hadRemote = true;
                                log.info("subdirs 采样远程 Path: {}", p);
                            } else {
                                paths.add(p);
                            }
                        }
                    }
                    log.info("subdirs 库采样本地路径数={}", paths.size());
                }
                // 叠加所选媒体路径，保证采样不足时仍可推断
                @SuppressWarnings("unchecked")
                List<String> itemIds = (List<String>) body.get("itemIds");
                if (itemIds != null) {
                    for (String id : itemIds) {
                        EmbyMediaItem item = embyApiService.getItem(accessToken, userId, id);
                        if (item != null) {
                            String p = item.resolvePath();
                            log.info("subdirs 所选媒体 id={} Path={}", id, p);
                            if (p == null) continue;
                            if (fileMoveService.isRemotePath(p)) {
                                hadRemote = true;
                            } else {
                                paths.add(p);
                            }
                        } else {
                            log.warn("subdirs 无法获取媒体项: {}", id);
                        }
                    }
                }
                root = fileMoveService.detectRoot(paths);
                log.info("subdirs detectRoot 结果={}, hadRemote={}", root, hadRemote);
                if (root == null || root.isEmpty()) {
                    result.put("success", false);
                    if (hadRemote && paths.isEmpty()) {
                        result.put("message", "媒体 Path 为远程 URL，无法本地移动；请确认网盘已挂载且 Emby 使用本地挂载路径");
                    } else {
                        String sample = paths.isEmpty() ? "(无本地 Path)" : paths.get(0);
                        result.put("message", "无法访问 Emby 路径对应的本地目录（如 /clouddriver2）。"
                                + "当前进程所在机器上看不到该挂载。示例 Path=" + sample
                                + "。本地 Windows 调试需映射网盘；NAS Docker 需挂载同一路径。详见日志 detectRoot");
                    }
                    return result;
                }
            } else {
                log.info("subdirs 使用手动 root={}", root);
                String manual = root;
                root = fileMoveService.findAccessibleAncestor(root);
                log.info("subdirs 手动 root 可访问祖先={}", root);
                if (root == null || root.isEmpty()) {
                    result.put("success", false);
                    result.put("root", manual);
                    result.put("message", "手动路径在本机不存在或不可达: " + manual
                            + "（请填写本进程可见的绝对路径，例如 /clouddriver2 或 Windows 上的实际盘符路径）");
                    result.put("subdirs", Collections.emptyList());
                    return result;
                }
            }
            String accessError = fileMoveService.checkRootAccess(root);
            if (accessError != null) {
                log.warn("subdirs 根不可访问: {}", accessError);
                result.put("success", false);
                result.put("root", root);
                result.put("message", accessError);
                result.put("subdirs", Collections.emptyList());
                return result;
            }
            List<String> subdirs = fileMoveService.listSubdirs(root);
            String normalizedRoot = fileMoveService.normalizePath(root);
            log.info("subdirs 成功: root={}, 子目录数={}", normalizedRoot, subdirs.size());
            result.put("success", true);
            result.put("root", normalizedRoot != null ? normalizedRoot : root);
            result.put("subdirs", subdirs);
        } catch (Exception e) {
            log.error("列出子目录失败", e);
            result.put("success", false);
            result.put("message", "列出子目录失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 文件浏览器：列出指定目录的直接子目录（单层，快）。
     * 无 path 时用 itemIds 取首项 Path 向上3级作为起点。
     */
    @PostMapping("/api/library/browse")
    @ResponseBody
    public Map<String, Object> browse(@RequestBody Map<String, Object> body, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        try {
            String path = body.get("path") != null ? body.get("path").toString() : null;
            log.info("browse 请求: path={}, itemIds={}", path, body.get("itemIds"));

            if (path == null || path.isEmpty()) {
                // 用首项 Path 向上3级作为默认起点
                @SuppressWarnings("unchecked")
                List<String> itemIds = (List<String>) body.get("itemIds");
                if (itemIds == null || itemIds.isEmpty()) {
                    result.put("success", false);
                    result.put("message", "缺少 path 或 itemIds");
                    return result;
                }
                EmbyMediaItem firstItem = embyApiService.getItem(accessToken, userId, itemIds.get(0));
                if (firstItem == null) {
                    result.put("success", false);
                    result.put("message", "无法获取媒体项");
                    return result;
                }
                String itemPath = firstItem.resolvePath();
                log.info("browse 首项 Path={}", itemPath);
                if (itemPath == null || fileMoveService.isRemotePath(itemPath)) {
                    result.put("success", false);
                    result.put("message", "媒体路径为空或远程 URL，无法本地浏览");
                    return result;
                }
                path = fileMoveService.goUpLevels(itemPath, 3);
                log.info("browse 向上3级 → {}", path);
            }

            // 规范化并验证
            String normalized = fileMoveService.normalizePath(path);
            if (normalized == null) {
                result.put("success", false);
                result.put("message", "路径无效: " + path);
                return result;
            }
            String accessError = fileMoveService.checkRootAccess(normalized);
            if (accessError != null) {
                // 如果起点不可访问，尝试向上找可访问祖先
                String ancestor = fileMoveService.findAccessibleAncestor(normalized);
                if (ancestor != null && !ancestor.isEmpty()) {
                    normalized = ancestor;
                } else {
                    result.put("success", false);
                    result.put("message", accessError);
                    return result;
                }
            }

            List<String> subdirs = fileMoveService.listSubdirsFlat(normalized);
            String parent = fileMoveService.parentDir(normalized);
            log.info("browse 成功: current={}, parent={}, 子目录数={}", normalized, parent, subdirs.size());
            result.put("success", true);
            result.put("current", normalized);
            result.put("parent", parent);
            result.put("subdirs", subdirs);
        } catch (Exception e) {
            log.error("浏览目录失败", e);
            result.put("success", false);
            result.put("message", "浏览目录失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 启动移动任务。
     */
    @PostMapping("/api/move")
    @ResponseBody
    public Map<String, Object> move(@RequestBody Map<String, Object> body, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        try {
            String targetPath = body.get("targetPath") != null ? body.get("targetPath").toString() : null;
            // 向后兼容：旧前端传 root + targetDir
            if (targetPath == null || targetPath.isEmpty()) {
                String root = body.get("root") != null ? body.get("root").toString() : null;
                String targetDir = body.get("targetDir") != null ? body.get("targetDir").toString() : "";
                if (root != null && !root.isEmpty()) {
                    targetPath = fileMoveService.normalizePath(root) + "/"
                            + fileMoveService.normalizePath(targetDir);
                }
            }
            @SuppressWarnings("unchecked")
            List<String> itemIds = (List<String>) body.get("itemIds");
            if (itemIds == null || itemIds.isEmpty()) {
                result.put("success", false);
                result.put("message", "未选择媒体");
                return result;
            }
            if (targetPath == null || targetPath.isEmpty()) {
                result.put("success", false);
                result.put("message", "缺少目标路径");
                return result;
            }

            log.info("move 请求: targetPath={}, itemIds={}", targetPath, itemIds);

            List<FileMoveService.MoveItem> items = new ArrayList<>();
            for (String id : itemIds) {
                EmbyMediaItem item = embyApiService.getItem(accessToken, userId, id);
                if (item != null) {
                    items.add(FileMoveService.MoveItem.from(item));
                }
            }
            String taskId = fileMoveService.startMove(targetPath, items, accessToken, userId);
            log.info("move 启动: taskId={}, itemCount={}", taskId, items.size());
            result.put("success", true);
            result.put("taskId", taskId);
        } catch (Exception e) {
            log.error("启动移动失败", e);
            result.put("success", false);
            result.put("message", "启动移动失败: " + e.getMessage());
        }
        return result;
    }

    @GetMapping("/api/move/{taskId}")
    @ResponseBody
    public Map<String, Object> moveProgress(@PathVariable String taskId) {
        Map<String, Object> result = new HashMap<>();
        FileMoveService.MoveTask task = fileMoveService.getProgress(taskId);
        if (task == null) {
            result.put("success", false);
            result.put("message", "任务不存在");
            return result;
        }
        result.put("success", true);
        result.put("taskId", task.getTaskId());
        result.put("total", task.getTotal());
        result.put("done", task.getDone());
        result.put("failed", task.getFailed());
        result.put("status", task.getStatus());
        result.put("currentName", task.getCurrentName());
        result.put("message", task.getMessage());
        result.put("failedFiles", task.getFailedFiles());
        return result;
    }

    /**
     * 启动批量打标签任务。
     */
    @PostMapping("/api/tags/batch")
    @ResponseBody
    public Map<String, Object> batchAddTags(@RequestBody Map<String, Object> body, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        try {
            @SuppressWarnings("unchecked")
            List<String> itemIds = (List<String>) body.get("itemIds");
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) body.get("tags");
            if (itemIds == null || itemIds.isEmpty()) {
                result.put("success", false);
                result.put("message", "未选择媒体");
                return result;
            }
            if (tags == null || tags.isEmpty()) {
                result.put("success", false);
                result.put("message", "未填写标签");
                return result;
            }
            String taskId = batchTagService.startBatch(itemIds, tags, accessToken, userId);
            result.put("success", true);
            result.put("taskId", taskId);
        } catch (Exception e) {
            log.error("启动批量打标签失败", e);
            result.put("success", false);
            result.put("message", "启动批量打标签失败: " + e.getMessage());
        }
        return result;
    }

    @GetMapping("/api/tags/batch/{taskId}")
    @ResponseBody
    public Map<String, Object> batchAddTagsProgress(@PathVariable String taskId) {
        Map<String, Object> result = new HashMap<>();
        BatchTagService.BatchTagTask task = batchTagService.getTask(taskId);
        if (task == null) {
            result.put("success", false);
            result.put("message", "任务不存在");
            return result;
        }
        result.put("success", true);
        result.put("taskId", task.getTaskId());
        result.put("total", task.getTotal());
        result.put("done", task.getDone());
        result.put("failed", task.getFailed());
        result.put("status", task.getStatus());
        result.put("currentName", task.getCurrentName());
        result.put("message", task.getMessage());
        result.put("failedItems", task.getFailedItems());
        return result;
    }

    @PostMapping("/api/favorites/batch")
    @ResponseBody
    public Map<String, Object> batchFavorite(@RequestBody Map<String, Object> body, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        try {
            @SuppressWarnings("unchecked")
            List<String> itemIds = (List<String>) body.get("itemIds");
            boolean favorite = body.get("favorite") != null
                    && Boolean.parseBoolean(body.get("favorite").toString());
            if (itemIds == null || itemIds.isEmpty()) {
                result.put("success", false);
                result.put("message", "未选择媒体");
                return result;
            }
            String taskId = batchFavoriteService.startBatch(itemIds, favorite, accessToken, userId);
            result.put("success", true);
            result.put("taskId", taskId);
        } catch (Exception e) {
            log.error("启动批量收藏失败", e);
            result.put("success", false);
            result.put("message", "启动批量收藏失败: " + e.getMessage());
        }
        return result;
    }

    @GetMapping("/api/favorites/batch/{taskId}")
    @ResponseBody
    public Map<String, Object> batchFavoriteProgress(@PathVariable String taskId) {
        Map<String, Object> result = new HashMap<>();
        BatchFavoriteService.BatchFavoriteTask task = batchFavoriteService.getTask(taskId);
        if (task == null) {
            result.put("success", false);
            result.put("message", "任务不存在");
            return result;
        }
        result.put("success", true);
        result.put("taskId", task.getTaskId());
        result.put("total", task.getTotal());
        result.put("done", task.getDone());
        result.put("failed", task.getFailed());
        result.put("status", task.getStatus());
        result.put("currentName", task.getCurrentName());
        result.put("message", task.getMessage());
        result.put("failedItems", task.getFailedItems());
        return result;
    }

    @PostMapping("/api/media/{itemId}/delete")
    @ResponseBody
    public Map<String, Object> apiDeleteMedia(@PathVariable String itemId, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        try {
            embyApiService.deleteItem(accessToken, itemId);
            result.put("success", true);
            result.put("message", "已删除");
        } catch (Exception e) {
            log.error("删除媒体失败 {}", itemId, e);
            result.put("success", false);
            result.put("message", "删除失败: " + e.getMessage());
        }
        return result;
    }

    @PostMapping("/api/media/batch-delete")
    @ResponseBody
    public Map<String, Object> batchDelete(@RequestBody Map<String, Object> body, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        try {
            @SuppressWarnings("unchecked")
            List<String> itemIds = (List<String>) body.get("itemIds");
            if (itemIds == null || itemIds.isEmpty()) {
                result.put("success", false);
                result.put("message", "未选择媒体");
                return result;
            }
            String taskId = batchDeleteService.startBatch(itemIds, accessToken);
            result.put("success", true);
            result.put("taskId", taskId);
        } catch (Exception e) {
            log.error("启动批量删除失败", e);
            result.put("success", false);
            result.put("message", "启动批量删除失败: " + e.getMessage());
        }
        return result;
    }

    @GetMapping("/api/media/batch-delete/{taskId}")
    @ResponseBody
    public Map<String, Object> batchDeleteProgress(@PathVariable String taskId) {
        Map<String, Object> result = new HashMap<>();
        BatchDeleteService.BatchDeleteTask task = batchDeleteService.getTask(taskId);
        if (task == null) {
            result.put("success", false);
            result.put("message", "任务不存在");
            return result;
        }
        result.put("success", true);
        result.put("taskId", task.getTaskId());
        result.put("total", task.getTotal());
        result.put("done", task.getDone());
        result.put("failed", task.getFailed());
        result.put("status", task.getStatus());
        result.put("currentName", task.getCurrentName());
        result.put("message", task.getMessage());
        result.put("failedItems", task.getFailedItems());
        return result;
    }

    @GetMapping("/api/collections")
    @ResponseBody
    public Map<String, Object> apiListCollections(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        try {
            result.put("success", true);
            result.put("collections", embyApiService.listCollections(accessToken, userId));
        } catch (Exception e) {
            log.error("获取合集列表失败", e);
            result.put("success", false);
            result.put("message", "获取合集列表失败: " + e.getMessage());
        }
        return result;
    }

    @PostMapping("/api/collections/{collectionId}/items")
    @ResponseBody
    public Map<String, Object> apiAddToCollection(@PathVariable String collectionId,
                                                  @RequestBody Map<String, Object> body,
                                                  HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        String accessToken = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (!isLoggedIn(accessToken, userId)) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        try {
            @SuppressWarnings("unchecked")
            List<String> itemIds = (List<String>) body.get("itemIds");
            embyApiService.addToCollection(accessToken, collectionId, itemIds);
            result.put("success", true);
            result.put("message", "已添加到合集");
        } catch (Exception e) {
            log.error("添加到合集失败", e);
            result.put("success", false);
            result.put("message", "添加到合集失败: " + e.getMessage());
        }
        return result;
    }
}
