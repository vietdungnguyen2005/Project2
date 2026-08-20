package dev.vmarket.shared;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, BffAuthenticationFilter bffAuthenticationFilter)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/catalog/**", "/actuator/health/**")
                        .permitAll()
                        .anyRequest()
                        .permitAll())
                .httpBasic(basic -> basic.disable())
                .formLogin(login -> login.disable())
                .addFilterBefore(bffAuthenticationFilter, AnonymousAuthenticationFilter.class)
                .build();
    }
}
