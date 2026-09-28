package com.emby.config;

import com.emby.service.EmbyApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * 每次请求前，从 session 取出登录时保存的 embyServerUrl，设置到 EmbyApiService，
 * 使后续 API 调用使用用户在登录页指定的服务器地址（而非配置默认值）。
 */
@Component
@RequiredArgsConstructor
public class ServerUrlInterceptor implements HandlerInterceptor {

    private final EmbyApiService embyApiService;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        HttpSession session = req.getSession(false);
        if (session != null) {
            String url = (String) session.getAttribute("embyServerUrl");
            embyApiService.setActiveServerUrl(url);
        }
        return true;
    }
}
