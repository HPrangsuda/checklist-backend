package com.acme.checklist.controller;

import com.acme.checklist.constant.MsgCode;
import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.MemberPrincipal;
import com.acme.checklist.payload.SessionDTO;
import com.acme.checklist.payload.SigninRequest;
import com.acme.checklist.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/sign-in")
    public Mono<ResponseEntity<ApiResponse<SessionDTO>>> signIn(
            @Valid @RequestBody SigninRequest body
    ) {
        return authService.signIn(body.getUsername(), body.getPassword())
                .map(session -> ResponseEntity.ok(
                        ApiResponse.success(MsgCode.AUTH_SIGNIN_SUCCESS.name(), session)
                ))
                .onErrorResume(e -> Mono.just(
                        ResponseEntity.status(401).body(
                                ApiResponse.error(MsgCode.AUTH_SIGNIN_FAILED.name(), e.getMessage())
                        )
                ));
    }

    @GetMapping("/me")
    public Mono<ResponseEntity<ApiResponse<SessionDTO>>> me(
            @AuthenticationPrincipal MemberPrincipal principal
    ) {
        if (principal == null) {
            return Mono.just(ResponseEntity.status(401).body(
                    ApiResponse.error(MsgCode.AUTH_UNAUTHORIZED.name())
            ));
        }
        return authService.getMe(principal.memberId())
                .map(session -> ResponseEntity.ok(
                        ApiResponse.success(MsgCode.AUTH_ME_SUCCESS.name(), session)
                ))
                .onErrorResume(e -> Mono.just(
                        ResponseEntity.status(401).body(
                                ApiResponse.error(MsgCode.AUTH_UNAUTHORIZED.name(), e.getMessage())
                        )
                ));
    }

    @PostMapping("/refresh")
    public Mono<ResponseEntity<ApiResponse<SessionDTO>>> refresh(
            @RequestBody Map<String, String> body
    ) {
        String token = body.get("refreshToken");
        if (token == null || token.isBlank()) {
            return Mono.just(ResponseEntity.status(400).body(
                    ApiResponse.error(MsgCode.AUTH_REFRESH_TOKEN_MISSING.name())
            ));
        }
        return authService.refresh(token)
                .map(session -> ResponseEntity.ok(
                        ApiResponse.success(MsgCode.AUTH_REFRESH_SUCCESS.name(), session)
                ))
                .onErrorResume(e -> Mono.just(
                        ResponseEntity.status(401).body(
                                ApiResponse.error(MsgCode.AUTH_REFRESH_FAILED.name(), e.getMessage())
                        )
                ));
    }

    @PostMapping("/sign-out")
    public Mono<ResponseEntity<ApiResponse<Void>>> signOut(
            @AuthenticationPrincipal MemberPrincipal principal
    ) {
        if (principal == null) {
            return Mono.just(ResponseEntity.ok(
                    ApiResponse.success(MsgCode.AUTH_SIGNOUT_SUCCESS.name())
            ));
        }
        return authService.signOut(principal.memberId())
                .then(Mono.just(ResponseEntity.ok(
                        ApiResponse.success(MsgCode.AUTH_SIGNOUT_SUCCESS.name())
                )))
                .onErrorResume(e -> Mono.just(
                        ResponseEntity.ok(
                                ApiResponse.success(MsgCode.AUTH_SIGNOUT_SUCCESS.name())
                        )
                ));
    }

    @GetMapping("/lark/sign-in")
    public Mono<ResponseEntity<ApiResponse<Void>>> larkSignIn() {
        return Mono.just(ResponseEntity.ok(
                ApiResponse.success(MsgCode.LARK_SIGNIN_REQUESTED.name())
        ));
    }
}