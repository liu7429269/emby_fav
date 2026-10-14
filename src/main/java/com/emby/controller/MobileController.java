package com.emby.controller;

import com.emby.service.BrowsePageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpSession;

@Slf4j
@Controller
@RequiredArgsConstructor
public class MobileController {

    private final BrowsePageService browsePageService;

    @GetMapping({"/m", "/m/login"})
    public String login(HttpSession session, Model model) {
        if (browsePageService.isLoggedIn(session)) {
            return "redirect:/m/libraries";
        }
        browsePageService.fillLogin(session, model);
        return "m/login";
    }

    @GetMapping("/m/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/m/login";
    }

    @GetMapping("/m/libraries")
    public String libraries(HttpSession session, Model model) {
        if (!browsePageService.isLoggedIn(session)) {
            return "redirect:/m/login";
        }
        try {
            browsePageService.fillLibraries(session, model);
            return "m/libraries";
        } catch (Exception e) {
            log.error("获取媒体库失败", e);
            model.addAttribute("error", "获取媒体库失败: " + e.getMessage());
            return "m/error";
        }
    }

    @GetMapping("/m/media")
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
            @RequestParam(required = false) String container,
            @RequestParam(required = false) String videoCodec,
            @RequestParam(required = false) String resolution,
            @RequestParam(required = false) String subtitle,
            HttpSession session,
            Model model) {
        if (!browsePageService.isLoggedIn(session)) {
            return "redirect:/m/login";
        }
        try {
            browsePageService.fillMedia(libraryId, parentId, tags, excludeTags, isFavorite, playStatus,
                    startIndex, limit, flat, view, sortBy, sortOrder,
                    container, videoCodec, resolution, subtitle, session, model);
            return "m/media";
        } catch (Exception e) {
            log.error("获取媒体项失败", e);
            model.addAttribute("error", "获取媒体项失败: " + e.getMessage());
            return "m/error";
        }
    }

    @GetMapping("/m/media/item/{itemId}")
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
                              @RequestParam(required = false) String container,
                              @RequestParam(required = false) String videoCodec,
                              @RequestParam(required = false) String resolution,
                              @RequestParam(required = false) String subtitle,
                              HttpSession session,
                              Model model) {
        if (!browsePageService.isLoggedIn(session)) {
            return "redirect:/m/login";
        }
        try {
            browsePageService.fillDetail(itemId, libraryId, parentId, flat, tags, excludeTags, isFavorite,
                    playStatus, startIndex, limit, sortBy, sortOrder,
                    container, videoCodec, resolution, subtitle, session, model);
            return "m/detail";
        } catch (Exception e) {
            log.error("获取详情失败", e);
            model.addAttribute("error", "获取详情失败: " + e.getMessage());
            return "m/error";
        }
    }

    @GetMapping("/m/search")
    public String search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false, defaultValue = "0") int startIndex,
            @RequestParam(required = false, defaultValue = "200") int limit,
            HttpSession session,
            Model model) {
        if (!browsePageService.isLoggedIn(session)) {
            return "redirect:/m/login";
        }
        try {
            browsePageService.fillSearch(q, scope, startIndex, limit, session, model);
            return "m/search";
        } catch (Exception e) {
            log.error("搜索失败", e);
            model.addAttribute("error", "搜索失败: " + e.getMessage());
            return "m/error";
        }
    }
}
