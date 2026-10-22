package com.wolfsnetz.webserver.mods;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** Trust the receiving connector, never Host, Forwarded, or X-Forwarded-* headers. */
@Configuration
public class ModApiSecurity {
    public static final String PREFIX = "/internal/mod-catalogue/v1";
    private static final int MAX_BODY = 1_048_576;
    private final boolean enabled;
    private final int port;
    private final byte[] tokenHash;

    public ModApiSecurity(@Value("${mods.api.enabled:false}") boolean enabled,
                          @Value("${mods.api.port:8081}") int port,
                          @Value("${mods.api.token-sha256:}") String hash,
                          @Value("${server.port:8080}") int websitePort,
                          @Value("${server.forward-headers-strategy:NONE}") String forwardedStrategy) {
        this.enabled = enabled;
        this.port = port;
        if (enabled && (port < 1024 || port > 65535 || port == websitePort
            || !hash.matches("[0-9a-fA-F]{64}") || !forwardedStrategy.equalsIgnoreCase("NONE"))) {
            throw new IllegalStateException("Research API requires a distinct port >=1024, a SHA-256 token hash, and forward-headers-strategy=NONE");
        }
        this.tokenHash = enabled ? HexFormat.of().parseHex(hash) : new byte[0];
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> modResearchConnector() {
        return factory -> {
            if (enabled) {
                Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
                connector.setPort(port);
                connector.setProperty("address", "127.0.0.1");
                connector.setProperty("connectionTimeout", "10000");
                connector.setMaxPostSize(MAX_BODY);
                factory.addAdditionalConnectors(connector);
            }
        };
    }

    @Bean
    @Order(1)
    SecurityFilterChain modResearchSecurity(HttpSecurity http) {
        return http.securityMatcher(request -> request.getServletPath().equals("/internal")
                || request.getServletPath().startsWith("/internal/")
                || (enabled && request.getLocalPort() == port))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .securityContext(context -> context.requireExplicitSave(true))
            .requestCache(cache -> cache.disable())
            .csrf(csrf -> csrf.disable())
            .addFilterBefore(new Gate(), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("MOD_RESEARCH"))
            .build();
    }

    private final class Gate extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
            response.setHeader("Cache-Control", "no-store");
            boolean correctPath = request.getServletPath().equals(PREFIX)
                || request.getServletPath().startsWith(PREFIX + "/");
            if (!enabled || request.getLocalPort() != port || !correctPath
                || !("127.0.0.1".equals(request.getRemoteAddr()) || "::1".equals(request.getRemoteAddr()))) {
                error(response, 404, "Not found");
                return;
            }
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 300
                || !MessageDigest.isEqual(tokenHash, sha256(authorization.substring(7)))) {
                response.setHeader("WWW-Authenticate", "Bearer");
                error(response, 401, "Invalid research credentials");
                return;
            }
            if (request.getContentLengthLong() > MAX_BODY) {
                error(response, 413, "Request body exceeds 1 MiB");
                return;
            }
            byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                error(response, 413, "Request body exceeds 1 MiB");
                return;
            }
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("catalogue-research", null,
                List.of(new SimpleGrantedAuthority("ROLE_MOD_RESEARCH"))));
            SecurityContextHolder.setContext(context);
            try {
                chain.doFilter(new BodyRequest(request, body), response);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void error(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":" + status + ",\"message\":\"" + message + "\"}");
    }

    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BodyRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream stream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return stream.read(); }
                @Override public int read(byte[] buffer, int offset, int length) { return stream.read(buffer, offset, length); }
                @Override public boolean isFinished() { return stream.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
