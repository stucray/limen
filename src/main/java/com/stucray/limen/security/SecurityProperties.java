package com.stucray.limen.security;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Base64;
import java.util.Optional;

@ConfigurationProperties("limen.security")
@Validated
public record SecurityProperties(@NotBlank String kek, @Nullable String kekPrevious) {

    private static final int KEK_BYTES = 32;

    @AssertTrue(message = "limen.security.kek must be base64 decoding to exactly 32 bytes (256 bits)")
    boolean isKekValid() {
        if (kek == null || kek.isBlank()) return true;
        return isWellFormed(kek);
    }

    @AssertTrue(message = "limen.security.kek-previous, when set, must be base64 decoding to exactly 32 bytes (256 bits)")
    boolean isKekPreviousValid() {
        return kekPrevious == null || kekPrevious.isBlank() || isWellFormed(kekPrevious);
    }

    /**
     * The optional previous-generation KEK, used by {@code JdbcSigningKeys} as a decrypt-only
     * fallback when the active KEK fails to authenticate an at-rest ciphertext (i.e. the
     * deployment KEK has been rotated and existing tenant signing keys are still wrapped
     * with the prior value). Blank strings are normalised to empty so an env var set to
     * the empty string behaves the same as an unset env var.
     */
    public Optional<String> kekPreviousValue() {
        return Optional.ofNullable(kekPrevious).filter(s -> !s.isBlank());
    }

    private static boolean isWellFormed(String value) {
        try {
            return Base64.getDecoder().decode(value).length == KEK_BYTES;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
