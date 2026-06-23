package com.smitgate.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long expiration;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration}") long expiration) {
        this.key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret));
        this.expiration = expiration;
    }

    public String generateToken(Authentication authentication, Long tenantId) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        String role = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(auth -> auth != null && auth.startsWith("ROLE_"))
            .map(auth -> auth.substring(5))
            .findFirst()
            .orElse("USER");
        Date now = new Date();
        return Jwts.builder()
                .subject(userDetails.getUsername())
                .claim("tenantId", tenantId)
            .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration))
                .signWith(key)
                .compact();
    }

    public String getUsernameFromToken(String token) {
        return getClaims(token).getPayload().getSubject();
    }

    public Long getTenantIdFromToken(String token) {
        return getClaims(token).getPayload().get("tenantId", Long.class);
    }

    public String getRoleFromToken(String token) {
        try {
            String role = getClaims(token).getPayload().get("role", String.class);
            return (role == null || role.isBlank()) ? null : role;
        } catch (Exception ignored) {
            return null;
        }
    }

    public boolean validateToken(String token) {
        try {
            getClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Jws<Claims> getClaims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
    }
}
