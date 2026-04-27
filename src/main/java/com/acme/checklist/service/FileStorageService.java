package com.acme.checklist.service;

import com.acme.checklist.payload.file.FileUploadDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class FileStorageService {

    @Value("${file.upload-dir:uploads}")
    private String uploadDir;

    @Value("${file.max-size:10485760}") // 10MB default
    private long maxFileSize;

    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(
            "pdf", "doc", "docx", "xls", "xlsx", "jpg", "jpeg", "png", "gif", "zip", "rar"
    );

    public Mono<FileUploadDTO> uploadFile(FilePart filePart, String uploadedBy) {
        return Mono.fromCallable(() -> {
                    // Validate file
                    String originalFilename = filePart.filename();
                    String extension = getFileExtension(originalFilename);

                    if (!ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
                        throw new RuntimeException("File type not allowed: " + extension);
                    }

                    // Create upload directory if not exists
                    Path uploadPath = Paths.get(uploadDir);
                    if (!Files.exists(uploadPath)) {
                        Files.createDirectories(uploadPath);
                    }

                    // Generate unique filename
                    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                    String uuid = UUID.randomUUID().toString().substring(0, 8);
                    String newFilename = String.format("%s_%s_%s", timestamp, uuid, originalFilename);
                    Path filePath = uploadPath.resolve(newFilename);

                    return filePath;
                })
                .flatMap(filePath -> {
                    // Delete existing file if it exists (to simulate REPLACE_EXISTING behavior)
                    return Mono.fromCallable(() -> {
                                Files.deleteIfExists(filePath);
                                return filePath;
                            })
                            .then(DataBufferUtils.write(filePart.content(), filePath))
                            .then(Mono.fromCallable(() -> {
                                long fileSize = Files.size(filePath);

                                if (fileSize > maxFileSize) {
                                    Files.delete(filePath);
                                    throw new RuntimeException("File size exceeds maximum allowed size");
                                }

                                return FileUploadDTO.builder()
                                        .fileName(filePath.getFileName().toString())
                                        .fileUrl("/uploads/" + filePath.getFileName().toString())
                                        .fileType(getFileExtension(filePart.filename()))
                                        .fileSize(fileSize)
                                        .uploadedBy(uploadedBy)
                                        .build();
                            }));
                })
                .doOnError(e -> log.error("Error uploading file: {}", e.getMessage()));
    }

    public Mono<Void> deleteFile(String fileName) {
        return Mono.fromRunnable(() -> {
            try {
                Path filePath = Paths.get(uploadDir).resolve(fileName);
                Files.deleteIfExists(filePath);
                log.info("File deleted: {}", fileName);
            } catch (IOException e) {
                log.error("Error deleting file: {}", e.getMessage());
                throw new RuntimeException("Failed to delete file: " + fileName);
            }
        });
    }

    private String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }
        return filename.substring(lastDotIndex + 1);
    }
}