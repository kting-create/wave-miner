package com.waveminer.waveminer.engine;

import com.waveminer.waveminer.config.AppProperties;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class EngineHttpClient {

    public record EngineHttpResponse(int statusCode, String body) {}

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final AppProperties props;
    long backoffBaseMillis = 1000;

    public EngineHttpClient(AppProperties props) { this.props = props; }

    public EngineHttpResponse send(HttpRequest request) {
        int attempts = Math.max(1, props.getEngineRetryAttempts());
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code != 429 && code < 500) {
                    return new EngineHttpResponse(code, resp.body());
                }
                last = new EngineException("转写服务返回 HTTP " + code + ": " + snippet(resp.body()));
            } catch (IOException e) {
                last = new EngineException("请求转写服务失败: " + e.getMessage(), e, null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EngineException("请求转写服务被中断", e, null);
            }
            if (attempt < attempts) {
                try {
                    Thread.sleep(backoffBaseMillis * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new EngineException("重试等待被中断", ie, null);
                }
            }
        }
        throw last;
    }

    private static String snippet(String s) {
        if (s == null) return "";
        return s.length() <= 500 ? s : s.substring(0, 500) + "…";
    }
}
