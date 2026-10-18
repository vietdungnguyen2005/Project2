package dev.vmarket.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.filter.OncePerRequestFilter;

final class LoginThrottleFilter extends OncePerRequestFilter {
    private final JdbcClient jdbc;

    LoginThrottleFilter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/api/auth/login".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        jdbc.sql(
                        "DELETE FROM auth_login_window WHERE client_key IN (SELECT client_key FROM auth_login_window WHERE expires_at<=NOW() LIMIT 100)")
                .update();
        int attempts = jdbc.sql("""
                INSERT INTO auth_login_window(client_key,attempts,expires_at)
                VALUES (:client,1,NOW()+INTERVAL '1 minute')
                ON CONFLICT(client_key) DO UPDATE SET
                    attempts=CASE WHEN auth_login_window.expires_at<=NOW() THEN 1 ELSE auth_login_window.attempts+1 END,
                    expires_at=CASE WHEN auth_login_window.expires_at<=NOW() THEN NOW()+INTERVAL '1 minute' ELSE auth_login_window.expires_at END
                RETURNING attempts
                """)
                .param("client", request.getRemoteAddr())
                .query(Integer.class)
                .single();
        if (attempts > 10) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            return;
        }
        chain.doFilter(request, response);
    }
}
