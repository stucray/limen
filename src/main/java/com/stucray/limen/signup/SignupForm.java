package com.stucray.limen.signup;

import org.jspecify.annotations.Nullable;

public record SignupForm(
    String organizationName,
    String slug,
    String email,
    @Nullable String fullName,
    String password
) {}
