package com.stucray.limen.signup;

public record SignupForm(
    String organizationName,
    String slug,
    String email,
    String password
) {}
