package com.boraver.teamgenerator.dto.auth;

public record ResetPasswordRequest(String token, String password) {}
