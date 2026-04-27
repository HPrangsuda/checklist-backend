package com.acme.checklist.entity;

import com.acme.checklist.entity.audit.DataAudit;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table("question")
@EqualsAndHashCode(callSuper = true)
public class Question extends DataAudit {
    @Id
    private Long id;

    @Column("detail")
    private String detail;

    @Column("description")
    private String description;
}
