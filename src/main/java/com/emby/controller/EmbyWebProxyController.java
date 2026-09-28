package com.emby.controller;

import com.emby.config.EmbyProperties;
import com.emby.service.EmbyApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

/**
 * 代理 Emby web 客户端，使其与本项目同源，从而可注入登录态并自动跳转到指定媒体。
 *  - GET /web/index.html?playItemId=xxx  注入登录脚本 + 自动导航
 *  - ALL /web/**                          转发到 Emby /web/**
 *  - ALL /emby/**                          转发到 Emby /emby/**
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class EmbyWebProxyController {

    private final EmbyProperties embyProperties;
    private final EmbyApiService embyApiService;

    private final java.util.concurrent.ConcurrentHashMap<String, String> serverIdCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Set<String> HOP_HEADERS = new HashSet<>();
    static {
        String[] h = {"host", "connection", "content-length", "transfer-encoding", "keep-alive",
                "proxy-connection", "proxy-authenticate", "proxy-authorization", "te", "trailer",
                "upgrade"};
        for (String s : h) HOP_HEADERS.add(s);
    }

    @GetMapping(value = "/web/index.html", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> webIndex(@RequestParam(required = false) String playItemId,
                                           HttpServletRequest request,
                                           HttpSession session) {
        String token = (String) session.getAttribute("embyAccessToken");
        String userId = (String) session.getAttribute("embyUserId");
        if (token == null || userId == null) {
            return ResponseEntity.status(302).header("Location", "/login").build();
        }
        String serverUrl = serverUrl(session);
        String serverId = resolveServerId(session, serverUrl, token);
        if (serverId == null || serverId.isEmpty()) {
            log.error("无法获取 Emby ServerId，播放代理将无法自动登录");
            return ResponseEntity.status(502).body("无法获取 Emby 服务器 ID，请重新登录后再试");
        }
        String origin = proxyOrigin(request);
        try {
            String html = fetchText(serverUrl + "/web/index.html");
            String script = buildInjectScript(token, userId, serverId, playItemId, origin);
            String injected = html.replaceFirst("<head>", "<head>" + script);
            return ResponseEntity.ok().body(injected);
        } catch (Exception e) {
            log.error("代理 Emby index.html 失败", e);
            return ResponseEntity.status(502).body("代理 Emby 失败: " + e.getMessage());
        }
    }

    @RequestMapping("/web/**")
    public void webProxy(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        proxy(req, resp, serverUrl(req) + req.getRequestURI() + qs(req));
    }

    @RequestMapping("/emby/**")
    public void embyProxy(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        proxy(req, resp, serverUrl(req) + req.getRequestURI() + qs(req));
    }

    private void proxy(HttpServletRequest req, HttpServletResponse resp, String targetUrl) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(targetUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(req.getMethod());
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(0); // 流式视频可能很久
            // 请求头
            for (Enumeration<String> e = req.getHeaderNames(); e.hasMoreElements(); ) {
                String name = e.nextElement();
                if (HOP_HEADERS.contains(name.toLowerCase())) continue;
                for (Enumeration<String> v = req.getHeaders(name); v.hasMoreElements(); ) {
                    conn.addRequestProperty(name, v.nextElement());
                }
            }
            boolean hasBody = isBodyMethod(req.getMethod());
            conn.setDoOutput(hasBody);
            if (hasBody) {
                try (OutputStream out = conn.getOutputStream()) {
                    InputStream in = req.getInputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.flush();
                }
            }
            int status = conn.getResponseCode();
            resp.setStatus(status);
            for (String k : conn.getHeaderFields().keySet()) {
                if (k == null) continue;
                if (HOP_HEADERS.contains(k.toLowerCase()) || "content-length".equalsIgnoreCase(k)) continue;
                String v = conn.getHeaderField(k);
                if (v != null) resp.addHeader(k, v);
            }
            InputStream body = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (body == null) return;
            try (InputStream in = body; OutputStream out = resp.getOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            }
        } catch (Exception e) {
            if (isClientAbort(e)) {
                // 客户端中途断开（导航离开/取消视频流），良性现象，仅 DEBUG 单行
                log.debug("客户端断开连接: {} {}", req.getMethod(), targetUrl);
            } else {
                log.error("代理请求失败 {}", targetUrl, e);
                if (!resp.isCommitted()) {
                    resp.setStatus(502);
                    try {
                        resp.getWriter().write("代理失败: " + e.getMessage());
                    } catch (IOException ignore) { /* response 已关闭 */ }
                }
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 沿异常链判断是否为客户端中途断开（ClientAbortException / Broken pipe / Connection reset） */
    private boolean isClientAbort(Throwable t) {
        while (t != null) {
            String name = t.getClass().getName();
            if (name.equals("org.apache.catalina.connector.ClientAbortException")) return true;
            String msg = t.getMessage();
            if (msg != null && (msg.contains("Broken pipe") || msg.contains("Connection reset"))) return true;
            t = t.getCause();
        }
        return false;
    }

    private String fetchText(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        try (InputStream in = conn.getInputStream()) {
            return readAll(in);
        } finally {
            conn.disconnect();
        }
    }

    private String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }

    private String resolveServerId(HttpSession session, String serverUrl, String token) {
        String fromSession = (String) session.getAttribute("embyServerId");
        if (fromSession != null && !fromSession.isEmpty()) return fromSession;
        String cached = serverIdCache.get(serverUrl);
        if (cached != null && !cached.isEmpty()) return cached;
        String id = embyApiService.getServerId(token);
        if (id != null && !id.isEmpty()) {
            session.setAttribute("embyServerId", id);
            serverIdCache.put(serverUrl, id);
        }
        return id;
    }

    /** 当前请求的代理 origin，供 Emby web 客户端回调本地 /emby API */
    private String proxyOrigin(HttpServletRequest req) {
        String scheme = req.getScheme();
        String host = req.getServerName();
        int port = req.getServerPort();
        boolean standard = ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        return scheme + "://" + host + (standard ? "" : ":" + port);
    }

    /** 从 session 取用户登录时指定的服务器地址，回退到配置默认值 */
    private String serverUrl(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session != null) {
            String url = (String) session.getAttribute("embyServerUrl");
            if (url != null && !url.isEmpty()) return url;
        }
        return embyProperties.getUrl();
    }

    private String serverUrl(HttpSession session) {
        if (session != null) {
            String url = (String) session.getAttribute("embyServerUrl");
            if (url != null && !url.isEmpty()) return url;
        }
        return embyProperties.getUrl();
    }

    private String qs(HttpServletRequest req) {
        return req.getQueryString() != null ? "?" + req.getQueryString() : "";
    }

    private boolean isBodyMethod(String m) {
        return "POST".equalsIgnoreCase(m) || "PUT".equalsIgnoreCase(m)
                || "PATCH".equalsIgnoreCase(m) || "DELETE".equalsIgnoreCase(m);
    }

    private String buildInjectScript(String token, String userId, String serverId,
                                      String playItemId, String origin) {
        return "<script>(function(){"
                + "var T=" + jsStr(token) + ",U=" + jsStr(userId) + ",S=" + jsStr(serverId)
                + ",P=" + jsStr(playItemId) + ",O=" + jsStr(origin) + ";"
                + "try{localStorage.setItem('enableAutoLogin','true');"
                + "var cred={Servers:[{ManualAddress:O,LocalAddress:O,ManualAddressOnly:true,IsLocalServer:true,"
                + "AccessToken:T,UserId:U,Id:S,Name:'Emby',DateLastAccessed:new Date().toISOString()}]};"
                + "localStorage.setItem('servercredentials3',JSON.stringify(cred));}catch(e){}"
                + "if(P){"
                // 提前设置 hash，让 Emby SPA 路由器初始化时直接导航到详情页，减少"先首页再跳"的闪烁
                + "try{location.replace('#/item?id='+P);}catch(e){}"
                // 保留 showItem 轮询作为后备，缩短延迟和轮询间隔
                + "var tries=0;function go(){tries++;"
                + "if(location.hash.indexOf('/item?id=')>=0&&tries>3){return;}"
                + "if(window.Emby&&window.Emby.Page&&typeof window.Emby.Page.showItem==='function'){"
                + "try{window.Emby.Page.showItem(P,S).then(function(){"
                + "if(location.hash.indexOf('/item?id=')<0&&tries<20){setTimeout(go,500);}"
                + "}).catch(function(){if(tries<20){setTimeout(go,500);}});"
                + "}catch(e){if(tries<20){setTimeout(go,500);}}}"
                + "else if(tries<20){setTimeout(go,500);}}"
                + "setTimeout(go,500);}"
                + "})();</script>";
    }

    private String jsStr(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'";
    }
}
