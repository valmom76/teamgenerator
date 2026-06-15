package com.boraver.teamgenerator.controller;

import com.boraver.teamgenerator.dto.auth.*;
import com.boraver.teamgenerator.dto.email.VerifyEmailRequest;
import com.boraver.teamgenerator.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Autenticação", description = "Endpoints para login, registro e verificação de e‑mail")
public class AuthController {

  private final AuthService authService;

  @PostMapping("/register-tenant")
  @Operation(summary = "Registrar novo grupo com administrador")
  @ApiResponses(value = {
          @ApiResponse(responseCode = "200", description = "Registro bem sucedido"),
          @ApiResponse(responseCode = "400", description = "Dados inválidos ou slug já existe")
  })
  public AuthResponse registerTenant(@Valid @RequestBody RegisterTenantRequest req) {
    var created = authService.registerTenantWithAdmin(
            req.tenantName(),
            req.tenantSlug(),
            req.adminName(),
            req.email(),
            req.password()
    );

    return buildAuthResponse(created);
  }

  @PostMapping("/login")
  @Operation(summary = "Login de usuário")
  @ApiResponses(value = {
          @ApiResponse(responseCode = "200", description = "Login bem-sucedido"),
          @ApiResponse(responseCode = "401", description = "Credenciais inválidas ou e‑mail não verificado")
  })
  public AuthResponse login(@Valid @RequestBody LoginRequest req) {
    var result = authService.login(
            UUID.fromString(req.tenantId()),
            req.email(),
            req.password()
    );

    return buildAuthResponse(result);
  }

  @PostMapping("/login-by-slug")
  @Operation(summary = "Login de usuário com slug do grupo")
  @ApiResponses(value = {
          @ApiResponse(responseCode = "200", description = "Login bem-sucedido"),
          @ApiResponse(responseCode = "401", description = "Credenciais inválidas")
  })
  public AuthResponse loginBySlug(@Valid @RequestBody LoginBySlugRequest req) {
    var result = authService.loginBySlug(
            req.tenantSlug(),
            req.email(),
            req.password()
    );

    return buildAuthResponse(result);
  }

  @PostMapping("/verify-email")
  @Operation(summary = "Verificar e‑mail do usuário")
  @ApiResponses(value = {
          @ApiResponse(responseCode = "200", description = "E‑mail verificado com sucesso"),
          @ApiResponse(responseCode = "400", description = "Token inválido ou expirado")
  })
  public ResponseEntity<String> verifyEmailAndSetPassword(@Valid @RequestBody VerifyEmailRequest req) {
    authService.verifyEmailAndSetPassword(req.token(), req.password());
    return ResponseEntity.ok("E‑mail verificado com sucesso! Você já pode fechar esta página e fazer login.");
  }

  // ==================== Método auxiliar ====================

  private AuthResponse buildAuthResponse(AuthService.LoginResult result) {
    return new AuthResponse(
            result.token(),
            result.tenantId().toString(),
            result.userId().toString(),
            result.role(),
            result.userName(),
            result.logoUrl(),
            result.primaryColor(),
            result.secondaryColor(),
            result.planName(),
            result.features(),
            result.emailVerified(),
            result.groupName()
    );
  }
}