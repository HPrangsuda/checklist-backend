package com.acme.checklist.entity.audit;

import com.acme.checklist.annotation.CreatedDepartment;
import com.acme.checklist.annotation.UpdatedDepartment;
import lombok.Data;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;

import java.time.Instant;

@Data
public class DataAudit {
    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    @CreatedBy
    @Column("created_by")
    private Long createdBy;

    @LastModifiedBy
    @Column("updated_by")
    private Long updatedBy;
}