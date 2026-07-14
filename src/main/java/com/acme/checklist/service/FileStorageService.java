package com.acme.checklist.service;

import com.acme.checklist.payload.file.FileUploadDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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

    @Value("${file.max-size:10485760}") // 10 MB default
    private long maxFileSize;

    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(
            "pdf", "doc", "docx", "xls", "xlsx",
            "jpg", "jpeg", "png", "gif", "webp",
            "zip", "rar"
    );

    // ─── Upload ───────────────────────────────────────────────────────────────

    public Mono<FileUploadDTO> uploadFile(FilePart filePart, String uploadedBy) {

        // 1. Validate extension synchronously (no I/O needed)
        String originalFilename = filePart.filename();
        if (originalFilename.isBlank()) {
            return Mono.error(new RuntimeException("File name must not be empty"));
        }

        String extension = getFileExtension(originalFilename);
        if (!ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
            return Mono.error(new RuntimeException("File type not allowed: " + extension));
        }

        // 2. Build target path (non-blocking portion)
        String timestamp   = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String uuid        = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        // FIX: sanitise original filename to strip path separators before concatenating
        String safeName    = Paths.get(originalFilename).getFileName().toString();
        String newFilename = timestamp + "_" + uuid + "_" + safeName;

        // 3. Prepare upload directory — blocking I/O → boundedElastic
        Mono<Path> mkdirMono = Mono.fromCallable(() -> {
            Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            return dir;
        }).subscribeOn(Schedulers.boundedElastic());

        return mkdirMono.flatMap(dir -> {
                    Path filePath = dir.resolve(newFilename);

                    // 4. Write file content (DataBufferUtils.write is non-blocking for the pipe;
                    //    the actual disk write happens on the Netty I/O thread via NIO, which is fine)
                    return DataBufferUtils.write(filePart.content(), filePath)
                            .then(
                                    // 5. Post-write checks — blocking stat → boundedElastic
                                    Mono.fromCallable(() -> {
                                        long fileSize = Files.size(filePath);
                                        if (fileSize > maxFileSize) {
                                            // FIX: delete oversized file before throwing
                                            try { Files.deleteIfExists(filePath); } catch (IOException ignored) {}
                                            throw new RuntimeException(
                                                    "File size (" + fileSize + " bytes) exceeds maximum allowed size ("
                                                            + maxFileSize + " bytes)");
                                        }
                                        return FileUploadDTO.builder()
                                                .fileName(newFilename)
                                                // FIX: fileUrl contains only the generated name, not the original,
                                                //      so the download endpoint can always resolve it by name alone.
                                                .fileUrl("/api/files/download/" + newFilename)
                                                .fileType(extension.toLowerCase())
                                                .fileSize(fileSize)
                                                .uploadedBy(uploadedBy)
                                                .build();
                                    }).subscribeOn(Schedulers.boundedElastic())
                            );
                })
                .doOnError(e -> log.error("Error uploading file '{}': {}", originalFilename, e.getMessage()));
    }

    // ─── Delete ───────────────────────────────────────────────────────────────

    public Mono<Void> deleteFile(String fileName) {
        // FIX: validate fileName to prevent path traversal before touching the filesystem
        if (fileName == null || fileName.isBlank()
                || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            return Mono.error(new RuntimeException("Invalid file name: " + fileName));
        }

        // FIX: all blocking I/O on boundedElastic
        return Mono.<Void>fromRunnable(() -> {
            try {
                Path uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
                Path filePath   = uploadPath.resolve(fileName).normalize();

                // Extra safety: ensure resolved path is still inside uploadDir
                if (!filePath.startsWith(uploadPath)) {
                    throw new RuntimeException("Path traversal detected for: " + fileName);
                }

                boolean deleted = Files.deleteIfExists(filePath);
                if (deleted) {
                    log.info("File deleted: {}", fileName);
                } else {
                    log.warn("File not found (already deleted?): {}", fileName);
                }
            } catch (IOException e) {
                log.error("Error deleting file '{}': {}", fileName, e.getMessage());
                throw new RuntimeException("Failed to delete file: " + fileName, e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns the lowercase extension without the dot, or empty string if none.
     * FIX: handles null, blank, and filenames with no extension.
     */
    private String getFileExtension(String filename) {
        if (filename == null || filename.isBlank()) return "";
        // Use only the last path component in case the client sent a full path
        String name = Paths.get(filename).getFileName().toString();
        int idx = name.lastIndexOf('.');
        return idx == -1 || idx == name.length() - 1 ? "" : name.substring(idx + 1).toLowerCase();
    }
}