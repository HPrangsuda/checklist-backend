package com.acme.checklist.payload;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SigninRequest {

    @NotBlank(message = "username required")
    private String username;

    @NotBlank(message = "password required")
    private String password;
}