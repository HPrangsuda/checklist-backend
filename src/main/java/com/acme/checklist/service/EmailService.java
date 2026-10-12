package com.acme.checklist.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from:noreply@acme.com}")
    private String from;

    /**
     * ส่งอีเมล HTML
     * - lazy: ทำงานตอน subscribe → retry แล้วส่งใหม่จริง
     * - error ถูก throw เป็น EmailSendException พร้อมบอกว่าควร retry หรือไม่
     */
    public Mono<Void> sendReminder(String to, String subject, String htmlContent) {
        return Mono.<Void>fromRunnable(() -> send(to, subject, htmlContent))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void send(String to, String subject, String htmlContent) {
        if (to == null || to.isBlank()) {
            throw new EmailSendException("Recipient email is blank", null, false);
        }
        String recipient = to.trim();

        // 1) สร้างข้อความ — พังตรงนี้คือข้อมูลผิด retry ไปก็ไม่ผ่าน
        MimeMessage message;
        try {
            message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
        } catch (AddressException e) {
            throw new EmailSendException("Invalid email address '" + recipient + "': " + e.getMessage(), e, false);
        } catch (MessagingException e) {
            throw new EmailSendException("Cannot build email to " + recipient + ": " + e.getMessage(), e, false);
        }

        // 2) ส่ง — ปัญหาเครือข่าย/SMTP ชั่วคราว retry ได้, ปัญหา config ไม่ต้อง retry
        try {
            mailSender.send(message);
            log.debug("[EMAIL] Sent to {}", recipient);
        } catch (MailAuthenticationException | MailParseException | MailPreparationException e) {
            throw new EmailSendException("Email to " + recipient + " rejected (not retryable): " + e.getMessage(), e, false);
        } catch (MailException e) {
            throw new EmailSendException("Email to " + recipient + " failed: " + e.getMessage(), e, true);
        }
    }

    /** error จากการส่งอีเมล พร้อมบอกว่าควร retry หรือไม่ */
    public static class EmailSendException extends RuntimeException {
        private final boolean retryable;

        public EmailSendException(String message, Throwable cause, boolean retryable) {
            super(message, cause);
            this.retryable = retryable;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }
}