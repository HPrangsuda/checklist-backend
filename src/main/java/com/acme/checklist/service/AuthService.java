package com.acme.checklist.service;

import com.acme.checklist.entity.Department;
import com.acme.checklist.entity.Member;
import com.acme.checklist.entity.RefreshToken;
import com.acme.checklist.payload.SessionDTO;
import com.acme.checklist.payload.department.DepartmentListDTO;
import com.acme.checklist.secutrity.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final R2dbcEntityTemplate template;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public Mono<SessionDTO> signIn(String username, String password) {
        return template.selectOne(
                        Query.query(Criteria.where("user_name").is(username)),
                        Member.class
                )
                .doOnNext(m -> log.info("Found member: {}", m.getUserName()))
                .doOnNext(m -> log.info("Password match: {}", passwordEncoder.matches(password, m.getPassword())))
                .filter(m -> passwordEncoder.matches(password, m.getPassword()))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("AUTH_SIGNIN_FAILED")))
                .flatMap(this::buildSession)
                .doOnSuccess(s -> log.info("Session built: {}", s.getUsername()))
                .doOnError(e -> log.error("SignIn error: {}", e.getMessage()));
    }

    public Mono<SessionDTO> refresh(String refreshToken) {
        return template.selectOne(
                        Query.query(Criteria.where("token").is(refreshToken)),
                        RefreshToken.class
                )
                .filter(t -> t.getExpiresAt().isAfter(LocalDateTime.now()))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("AUTH_REFRESH_FAILED")))
                .flatMap(stored -> template.selectOne(
                                        Query.query(Criteria.where("id").is(stored.getMemberId())),
                                        Member.class
                                )
                                .switchIfEmpty(Mono.error(new IllegalArgumentException("MEMBER_NOT_FOUND")))
                                .flatMap(this::buildSession)
                );
    }

    public Mono<SessionDTO> getMe(Long memberId) {
        return template.selectOne(
                        Query.query(Criteria.where("id").is(memberId)),
                        Member.class
                )
                .switchIfEmpty(Mono.error(new IllegalArgumentException("MEMBER_NOT_FOUND")))
                .flatMap(member -> fetchDepartments(member.getId())
                        .map(depts -> SessionDTO.builder()
                                .memberId(member.getId())
                                .username(member.getUserName())
                                .firstName(member.getFirstName())
                                .lastName(member.getLastName())
                                .email(member.getEmail())
                                .avatarKey(member.getAvatarKey())
                                .roleType(member.getRoleType() != null ? member.getRoleType().name() : null)
                                .language(member.getLanguages())
                                .departments(depts)
                                .build())
                );
    }

    public Mono<Void> signOut(Long memberId) {
        return template.delete(
                Query.query(Criteria.where("member_id").is(memberId)),
                RefreshToken.class
        ).then();
    }

    private Mono<SessionDTO> buildSession(Member member) {

        String role = member.getRoleType() != null
                ? member.getRoleType().name()
                : "USER";

        return fetchDepartments(member.getId())
                .flatMap(depts -> {

                    if (depts.isEmpty()) {
                        return Mono.error(new IllegalStateException("NO_DEPARTMENT_ASSIGNED"));
                    }

                    Long departmentId = depts.get(0).getId(); // ใช้ department แรก

                    String accessToken = jwtService.generateAccessToken(
                            member.getId(),
                            member.getUserName(),
                            role,
                            departmentId,
                            member.getEmployeeId()
                    );

                    String refreshToken = jwtService.generateRefreshToken(
                            member.getId(),
                            member.getUserName(),
                            role,
                            departmentId,
                            member.getEmployeeId()
                    );

                    RefreshToken tokenEntity = new RefreshToken();
                    tokenEntity.setMemberId(member.getId());
                    tokenEntity.setToken(refreshToken);
                    tokenEntity.setExpiresAt(
                            LocalDateTime.now()
                                    .plusSeconds(jwtService.getRefreshExpiry() / 1000)
                    );
                    tokenEntity.setCreatedAt(LocalDateTime.now());

                    return template.delete(
                                    Query.query(Criteria.where("member_id").is(member.getId())),
                                    RefreshToken.class
                            )
                            .doOnSuccess(deleted -> log.info("Deleted refresh tokens: {}", deleted))
                            .then(template.insert(tokenEntity))
                            .doOnSuccess(inserted -> log.info("Inserted token: {}", inserted))
                            .doOnError(e -> log.error("Insert error: {}", e.getMessage()))
                            .thenReturn(
                                    SessionDTO.builder()
                                            .memberId(member.getId())
                                            .employeeId(member.getEmployeeId())
                                            .username(member.getUserName())
                                            .firstName(member.getFirstName())
                                            .lastName(member.getLastName())
                                            .email(member.getEmail())
                                            .avatarKey(member.getAvatarKey())
                                            .roleType(role)
                                            .language(member.getLanguages())
                                            .accessToken(accessToken)
                                            .refreshToken(refreshToken)
                                            .departments(depts)
                                            .build()
                            );
                });
    }

    private Mono<List<DepartmentListDTO>> fetchDepartments(Long memberId) {
        return template.selectOne(
                        Query.query(Criteria.where("id").is(memberId)),
                        Member.class
                )
                .flatMap(member -> {
                    if (member.getDepartmentId() == null) {
                        return Mono.just(List.of());
                    }

                    return template.selectOne(
                                    Query.query(Criteria.where("department_code").is(member.getDepartmentId())),
                                    Department.class
                            )
                            .map(DepartmentListDTO::from)
                            .map(List::of)
                            .defaultIfEmpty(List.of());
                });
    }
}