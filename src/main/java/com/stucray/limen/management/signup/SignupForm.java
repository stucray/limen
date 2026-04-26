package com.stucray.limen.management.signup;

public record SignupForm(
    String organizationName,
    String slug,
    String username,
    String password
) {}
