package com.acme.checklist.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("refresh_token")
public class RefreshToken {

    @Id
    private Long id;

    @Column("member_id")
    private Long memberId;

    private String token;

    @Column("expires_at")
    private LocalDateTime expiresAt;

    @Column("created_at")
    private LocalDateTime createdAt;
}