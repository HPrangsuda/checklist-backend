package com.acme.checklist.secutrity;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JwtSecurityContextRepository implements ServerSecurityContextRepository {

    private final JwtAuthenticationManager authManager;

    public JwtSecurityContextRepository(JwtAuthenticationManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public Mono<Void> save(ServerWebExchange exchange, SecurityContext context) {
        return Mono.empty();
    }

    @Override
    public Mono<SecurityContext> load(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return Mono.empty();

        String token = header.substring(7).trim();
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(token, token);

        return authManager.authenticate(auth)
                .<SecurityContext>map(SecurityContextImpl::new)
                .onErrorResume(e -> Mono.empty());
    }
}