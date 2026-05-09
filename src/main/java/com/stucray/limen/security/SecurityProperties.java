package com.stucray.limen.security;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Base64;

@ConfigurationProperties("limen.security")
@Validated
public record SecurityProperties(@NotBlank String kek) {

    private static final int KEK_BYTES = 32;

    @AssertTrue(message = "limen.security.kek must be base64 decoding to exactly 32 bytes (256 bits)")
    boolean isKekValid() {
        if (kek == null || kek.isBlank()) return true;
        try {
            return Base64.getDecoder().decode(kek).length == KEK_BYTES;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
