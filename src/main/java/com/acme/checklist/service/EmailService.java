package com.acme.checklist.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    public Mono<Void> sendReminder(String to, String subject, String htmlContent) {
        return Mono.fromCallable(() -> {
                    try {
                        MimeMessage message = mailSender.createMimeMessage();
                        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

                        helper.setTo(to);
                        helper.setSubject(subject);
                        helper.setText(htmlContent, true);
                        helper.setFrom("noreply@acme.com");

                        mailSender.send(message);
                        log.info("Email sent successfully to {}", to);
                        return null;
                    } catch (MessagingException e) {
                        log.error("Failed to send email to {}", to, e);
                        throw new RuntimeException("Failed to send email", e);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}