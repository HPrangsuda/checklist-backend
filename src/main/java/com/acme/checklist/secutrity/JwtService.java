package com.acme.checklist.secutrity;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    @Getter
    private final long accessExpiry;
    @Getter
    private final long refreshExpiry;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiry}") long accessExpiry,
            @Value("${jwt.refresh-token-expiry}") long refreshExpiry
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
        this.accessExpiry = accessExpiry;
        this.refreshExpiry = refreshExpiry;
    }

    public String generateAccessToken(Long memberId, String username, String role, Long departmentId, String employeeId) {
        return build(memberId, username, role, departmentId, employeeId, accessExpiry, "access");
    }

    public String generateRefreshToken(Long memberId, String username, String role, Long departmentId, String employeeId) {
        return build(memberId, username, role, departmentId, employeeId, refreshExpiry, "refresh");
    }

    private String build(Long memberId, String username, String role, Long departmentId, String employeeId, long expiry, String type) {
        return Jwts.builder()
                .subject(memberId.toString())
                .claim("username", username)
                .claim("role", role)
                .claim("departmentId", departmentId)
                .claim("employeeId", employeeId)  // ✅ เพิ่ม
                .claim("type", type)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiry))
                .signWith(key)
                .compact();
    }

    // ===============================
    // VALIDATE
    // ===============================
    public Claims validate(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            return null;
        }
    }

    public Long getMemberId(String token) {
        Claims claims = validate(token);
        if (claims == null) return null;

        try {
            return Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}