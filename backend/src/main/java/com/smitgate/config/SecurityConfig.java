package com.smitgate.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 401 = missing/expired token (frontend logs out), 403 = authenticated but not permitted
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new org.springframework.security.web.authentication.HttpStatusEntryPoint(
                                org.springframework.http.HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/ops/health").permitAll()
                        .requestMatchers("/error").permitAll()
                        // OAuth callbacks are browser redirects FROM external providers — no JWT present
                        .requestMatchers("/api/integrations/meta/oauth/callback").permitAll()
                        .requestMatchers("/api/integrations/google/oauth/callback").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Staff only get the ad manager page; per-account checks live in AdAccountAccessService
                        .requestMatchers("/api/me").authenticated()
                        .requestMatchers(HttpMethod.GET,
                                "/api/integrations/meta/ad-accounts/selected",
                                "/api/integrations/meta/ad-accounts/billing",
                                "/api/integrations/meta/campaigns",
                                "/api/integrations/meta/adsets",
                                "/api/integrations/meta/ads",
                                "/api/integrations/meta/ads/performance",
                                "/api/integrations/meta/ads/*/daily",
                                "/api/reports/campaigns",
                                "/api/reports/campaigns/*/funnel",
                                "/api/orders/by-ad/*",
                                "/api/ad-notes",
                                "/api/ad-notes/counts",
                                "/api/ad-saved-views").authenticated()
                        .requestMatchers(HttpMethod.POST,
                                "/api/integrations/meta/toggle-status",
                                "/api/integrations/meta/update-budget",
                                "/api/orders/*/review",
                                "/api/ad-notes",
                                "/api/ad-saved-views").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/ad-saved-views/*").authenticated()
                        .anyRequest().hasAnyRole("ADMIN", "ANALYST")
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toList());
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
