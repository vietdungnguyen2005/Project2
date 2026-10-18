package dev.vmarket.shared;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OperatorSessionController {
    @GetMapping("/api/auth/session")
    Map<String, Object> session(HttpServletRequest request, Authentication authentication) {
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        boolean authenticated = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        return Map.of(
                "authenticated",
                authenticated,
                "username",
                authenticated ? authentication.getName() : "",
                "roles",
                authenticated
                        ? authentication.getAuthorities().stream()
                                .map(Object::toString)
                                .toList()
                        : java.util.List.of(),
                "csrfToken",
                csrf.getToken());
    }
}
