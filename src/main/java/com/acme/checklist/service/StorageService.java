package com.acme.checklist.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Slf4j
@Service
public class StorageService {

    @Value("${storage.upload-dir:uploads}")
    private String uploadDir;

    public Mono<String> saveFile(FilePart file) {
        if (file == null) return Mono.just("");

        String filename = UUID.randomUUID() + "_" + file.filename();
        Path uploadPath = Paths.get(uploadDir, filename);

        return Mono.fromCallable(() -> {
                    Files.createDirectories(uploadPath.getParent());
                    return uploadPath;
                })
                .flatMap(path -> file.transferTo(path).thenReturn(filename))
                .doOnSuccess(name -> log.info("File saved: {}", name))
                .doOnError(e -> log.error("Failed to save file: {}", e.getMessage()));
    }

    public void deleteFile(String filename) {
        if (filename == null || filename.isBlank()) return;
        try {
            Files.deleteIfExists(Paths.get(uploadDir, filename));
        } catch (Exception e) {
            log.error("Failed to delete file: {}", e.getMessage());
        }
    }
}