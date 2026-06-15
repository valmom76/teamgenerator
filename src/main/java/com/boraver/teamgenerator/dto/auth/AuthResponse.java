package com.boraver.teamgenerator.dto.auth;

import java.util.List;

public record AuthResponse(
        String token,
        String tenantId,
        String userId,
        String role,
        String userName,
        String logoUrl,
        String primaryColor,
        String secondaryColor,
        String planName,
        List<String> features,
        boolean emailVerified,
        String groupName
) {}