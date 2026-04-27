package com.acme.checklist.secutrity;

import com.acme.checklist.payload.MemberPrincipal;
import io.jsonwebtoken.Claims;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class JwtAuthenticationManager implements ReactiveAuthenticationManager {

    private final JwtService jwtService;

    public JwtAuthenticationManager(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        String token = authentication.getCredentials().toString();
        Claims claims = jwtService.validate(token);

        if (claims == null) {
            return Mono.error(new BadCredentialsException("Invalid token"));
        }

        if (!"access".equals(claims.get("type"))) {
            return Mono.error(new BadCredentialsException("Invalid token type"));
        }

        Long memberId = jwtService.getMemberId(token);
        if (memberId == null) {
            return Mono.error(new BadCredentialsException("Invalid member"));
        }

        String role = claims.get("role", String.class);
        String username = claims.get("username", String.class);
        if (role == null) role = "USER";

        Long departmentId = claims.get("departmentId", Long.class);
        if (departmentId == null) {
            return Mono.error(new BadCredentialsException("Missing department"));
        }

        String employeeId = claims.get("employeeId", String.class);

        MemberPrincipal principal = new MemberPrincipal(
                memberId,
                username,
                role,
                departmentId,
                employeeId
        );

        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );

        return Mono.just(auth);
    }
}