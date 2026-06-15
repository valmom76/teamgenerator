package com.boraver.teamgenerator.dto.subscription;

import jakarta.validation.constraints.NotBlank;

public record UpdateCpfCnpjRequestDTO(@NotBlank String cpfCnpj) {}
