package com.acme.checklist.config;

import com.acme.checklist.entity.Member;
import com.acme.checklist.entity.enums.RoleType;
import com.acme.checklist.payload.MemberPrincipal;
import com.acme.checklist.secutrity.JwtAuthenticationManager;
import com.acme.checklist.secutrity.JwtSecurityContextRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.domain.ReactiveAuditorAware;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

@Slf4j
@Configuration
@EnableWebFluxSecurity
@EnableR2dbcAuditing
public class SecurityConfig {

    private final JwtAuthenticationManager authManager;
    private final JwtSecurityContextRepository securityContextRepo;
    private final R2dbcEntityTemplate template;

    public SecurityConfig(JwtAuthenticationManager authManager,
                          JwtSecurityContextRepository securityContextRepo,
                          R2dbcEntityTemplate template) {
        this.authManager = authManager;
        this.securityContextRepo = securityContextRepo;
        this.template = template;
    }

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsSource()))
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authenticationManager(authManager)
                .securityContextRepository(securityContextRepo)
                .authorizeExchange(auth -> auth
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/api/auth/**").permitAll()
                        .pathMatchers("/uploads/**").permitAll()
                        .anyExchange().authenticated()
                )
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((exchange, ex) -> {
                            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                            String body = """
                            {
                              "success": false,
                              "code": "AUTH_UNAUTHORIZED"
                            }
                            """;
                            DataBuffer buffer = exchange.getResponse()
                                    .bufferFactory()
                                    .wrap(body.getBytes(StandardCharsets.UTF_8));
                            return exchange.getResponse().writeWith(Mono.just(buffer));
                        })
                )
                .build();
    }

//    @EventListener(ApplicationReadyEvent.class)
//    public void initAdminUser() {
//        template.selectOne(Query.query(Criteria.where("user_name").is("admin")), Member.class)
//                .doOnNext(existing -> log.info("Admin user already exists, skipping creation"))
//                .switchIfEmpty(Mono.defer(() -> {
//                    Member admin = new Member();
//                    admin.setEmployeeId("ADMIN001");
//                    admin.setFirstName("System");
//                    admin.setLastName("Admin");
//                    admin.setUserName("admin");
//                    admin.setPassword(passwordEncoder().encode("12345678"));
//                    admin.setEmail("admin@acme.com");
//                    admin.setRoleType(RoleType.ADMIN);
//                    admin.setLanguages("en");
//                    return template.insert(admin)
//                            .doOnSuccess(saved -> log.info("Admin user created (id={})", saved.getId()))
//                            .doOnError(e -> log.error("Failed to create admin: {}", e.getMessage()));
//                }))
//                .subscribe();
//    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("http://localhost:*", "https://*.acme-inter.com"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public ReactiveAuditorAware<Long> auditorAware() {
        return () -> ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(auth -> auth != null && auth.isAuthenticated())
                .mapNotNull(auth -> {
                    Object principal = auth.getPrincipal();
                    if (principal instanceof MemberPrincipal mp) {
                        return mp.memberId();
                    }
                    return null;
                })
                .filter(Objects::nonNull);
    }

    @Configuration
    public class WebConfig implements WebFluxConfigurer {
        @Value("${storage.upload-dir:uploads}")
        private String uploadDir;

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/uploads/**")
                    .addResourceLocations("file:" + uploadDir + "/");
        }
    }
}