package com.acme.checklist.payload.file;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FileUploadDTO {
    private String fileName;
    private String fileUrl;
    private String fileType;
    private Long fileSize;
    private String uploadedBy;
}