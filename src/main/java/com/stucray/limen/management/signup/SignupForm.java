package com.stucray.limen.management.signup;

public record SignupForm(
    String organizationName,
    String slug,
    String email,
    String password
) {}
