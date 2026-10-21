package com.waveminer.waveminer.api;

import com.waveminer.waveminer.config.AppProperties;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenInterceptorTest {

    private AccessTokenInterceptor interceptor(String token) {
        AppProperties props = new AppProperties();
        props.setAccessToken(token);
        return new AccessTokenInterceptor(props);
    }

    @Test
    void missingBothHeaderAndParamReturns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean pass = interceptor("secret").preHandle(request, response, new Object());

        assertThat(pass).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("UNAUTHORIZED").contains("缺少或错误的访问令牌");
    }

    @Test
    void correctHeaderPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tasks");
        request.addHeader("X-Access-Token", "secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean pass = interceptor("secret").preHandle(request, response, new Object());

        assertThat(pass).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void wrongHeaderReturns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tasks");
        request.addHeader("X-Access-Token", "wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean pass = interceptor("secret").preHandle(request, response, new Object());

        assertThat(pass).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("UNAUTHORIZED");
    }

    @Test
    void correctQueryParamPassesWhenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tasks");
        request.setParameter("token", "secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean pass = interceptor("secret").preHandle(request, response, new Object());

        assertThat(pass).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void emptyAccessTokenDisablesAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean pass = interceptor("").preHandle(request, response, new Object());

        assertThat(pass).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
