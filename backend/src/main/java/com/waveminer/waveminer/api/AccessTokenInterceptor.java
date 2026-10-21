package com.waveminer.waveminer.api;

import com.waveminer.waveminer.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.servlet.HandlerInterceptor;

public class AccessTokenInterceptor implements HandlerInterceptor {

    private final AppProperties props;

    public AccessTokenInterceptor(AppProperties props) { this.props = props; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String token = props.getAccessToken();
        if (token == null || token.isBlank()) return true;
        String given = request.getHeader("X-Access-Token");
        if (given == null || given.isBlank()) {
            given = request.getParameter("token"); // 媒体标签无法带自定义 header
        }
        if (MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        response.setStatus(401);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"缺少或错误的访问令牌\"}");
        return false;
    }
}
