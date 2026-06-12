package com.boraver.teamgenerator.dto.subscription;

public record CheckoutResponseDTO(java.util.UUID subscriptionId, String status, String bankSlipUrl, String pixUrl) {}