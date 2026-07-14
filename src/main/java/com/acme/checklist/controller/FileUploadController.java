package com.acme.checklist.controller;

import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.file.FileUploadDTO;
import com.acme.checklist.service.FileStorageService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/files")
@AllArgsConstructor
public class FileUploadController {

    private final FileStorageService fileStorageService;

    // ─── Upload single ────────────────────────────────────────────────────────

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ApiResponse<FileUploadDTO>> uploadFile(
            @RequestPart("file") Part filePart,
            @RequestParam(value = "uploadedBy", required = false) String uploadedBy
    ) {
        if (!(filePart instanceof FilePart file)) {
            String partType = filePart instanceof FormFieldPart
                    ? "form field (text)"
                    : filePart.getClass().getSimpleName();
            log.warn("Upload rejected: received {} instead of file binary. " +
                    "Frontend sent a non-file part named 'file'.", partType);
            return Mono.just(ApiResponse.error("FILE004",
                    "Invalid upload: expected a file but received a form field. " +
                            "Please select an actual file to upload."));
        }

        log.info("Uploading file: {}", file.filename());

        return fileStorageService.uploadFile(file, uploadedBy)
                .map(fileDto -> ApiResponse.success("File uploaded successfully", fileDto))
                .onErrorResume(e -> {
                    log.error("File upload failed: {}", e.getMessage());
                    return Mono.just(ApiResponse.error("FILE001", e.getMessage()));
                });
    }

    // ─── Upload multiple ──────────────────────────────────────────────────────

    @PostMapping(value = "/upload/multiple", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ApiResponse<List<FileUploadDTO>>> uploadMultipleFiles(
            @RequestPart("files") Flux<FilePart> files,
            @RequestParam(value = "uploadedBy", required = false) String uploadedBy
    ) {
        log.info("Uploading multiple files");
        return files
                .flatMap(file -> fileStorageService.uploadFile(file, uploadedBy))
                .collectList()
                .map(fileDtos -> ApiResponse.success("Files uploaded successfully", fileDtos))
                .onErrorResume(e -> {
                    log.error("Multiple file upload failed: {}", e.getMessage());
                    return Mono.just(ApiResponse.error("FILE002", e.getMessage()));
                });
    }

    // ─── Download / view ──────────────────────────────────────────────────────

    @GetMapping("/download/{fileName:.+}")
    public Mono<ResponseEntity<Resource>> downloadFile(@PathVariable String fileName) {
        if (fileName == null || fileName.isBlank()
                || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            log.warn("Rejected suspicious fileName in download: {}", fileName);
            return Mono.just(ResponseEntity.<Resource>badRequest().build());
        }

        return Mono.<ResponseEntity<Resource>>fromCallable(() -> {
                    Path uploadDir = Paths.get("uploads").toAbsolutePath().normalize();
                    Path filePath  = uploadDir.resolve(fileName).normalize();

                    if (!filePath.startsWith(uploadDir)) {
                        log.warn("Path traversal attempt detected for fileName: {}", fileName);
                        return ResponseEntity.<Resource>badRequest().build();
                    }

                    Resource resource = new UrlResource(filePath.toUri());
                    if (!resource.exists() || !resource.isReadable()) {
                        log.warn("File not found or not readable: {}", filePath);
                        return ResponseEntity.<Resource>notFound().build();
                    }

                    String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                            .replace("+", "%20");
                    String contentDisposition = isImage(fileName)
                            ? "inline; filename*=UTF-8''" + encodedName
                            : "attachment; filename*=UTF-8''" + encodedName;

                    return ResponseEntity.<Resource>ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                            .header("X-Content-Type-Options", "nosniff")
                            .contentType(MediaType.parseMediaType(detectContentType(fileName)))
                            .body(resource);

                }).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("File download failed for '{}': {}", fileName, e.getMessage());
                    return Mono.just(ResponseEntity.<Resource>notFound().build());
                });
    }

    // ─── Delete ───────────────────────────────────────────────────────────────

    @DeleteMapping("/delete/{fileName:.+}")
    public Mono<ApiResponse<Void>> deleteFile(@PathVariable String fileName) {
        log.info("Deleting file: {}", fileName);
        return fileStorageService.deleteFile(fileName)
                .then(Mono.just(ApiResponse.<Void>success("File deleted successfully")))
                .onErrorResume(e -> {
                    log.error("File deletion failed for '{}': {}", fileName, e.getMessage());
                    return Mono.just(ApiResponse.error("FILE003", e.getMessage()));
                });
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private boolean isImage(String fileName) {
        return List.of("png", "jpg", "jpeg", "gif", "webp").contains(getExtension(fileName));
    }

    private String detectContentType(String fileName) {
        return switch (getExtension(fileName)) {
            case "png"  -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif"  -> "image/gif";
            case "webp" -> "image/webp";
            case "pdf"  -> "application/pdf";
            case "doc"  -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls"  -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default     -> "application/octet-stream";
        };
    }

    private String getExtension(String fileName) {
        if (fileName == null) return "";
        int idx = fileName.lastIndexOf('.');
        return idx == -1 ? "" : fileName.substring(idx + 1).toLowerCase();
    }
}