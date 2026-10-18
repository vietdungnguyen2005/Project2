package dev.vmarket.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.filter.OncePerRequestFilter;

final class SessionAccountRefreshFilter extends OncePerRequestFilter {
    private final UserDetailsService users;

    SessionAccountRefreshFilter(UserDetailsService users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            try {
                var account = users.loadUserByUsername(authentication.getName());
                if (!account.isEnabled()) throw new UsernameNotFoundException("Account disabled");
                var principal = org.springframework.security.core.userdetails.User.withUsername(account.getUsername())
                        .password("")
                        .authorities(account.getAuthorities())
                        .build();
                SecurityContextHolder.getContext()
                        .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                                principal, null, account.getAuthorities()));
            } catch (UsernameNotFoundException exception) {
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
