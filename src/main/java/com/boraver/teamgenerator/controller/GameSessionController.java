package com.boraver.teamgenerator.controller;

import com.boraver.teamgenerator.common.TenantContext;
import com.boraver.teamgenerator.dto.game.*;
import com.boraver.teamgenerator.dto.match.FriendlyMatchRequest;
import com.boraver.teamgenerator.dto.match.FriendlySessionDTO;
import com.boraver.teamgenerator.dto.match.FriendlySessionSummary;
import com.boraver.teamgenerator.service.FriendlySessionService;
import com.boraver.teamgenerator.service.GameSessionService;
import com.boraver.teamgenerator.service.TeamGenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/game-sessions")
@RequiredArgsConstructor
@Tag(name = "Controle de Sessão de Jogos", description = "Controla a sessão ativa dos jogos para gestão de placar")
public class GameSessionController {

  private final GameSessionService gameSessionService;
  private final TeamGenerationService teamGenerationService;
  private final FriendlySessionService friendlySessionService;   // novo serviço

  // ==================== ENDPOINTS EXISTENTES (mantidos intactos) ====================

  @Operation(summary = "Inicia a sessão de jogos")
  @PostMapping("/start")
  public ResponseEntity<?> startSession(@Valid @RequestBody StartSessionRequest request, Authentication auth) {
    UUID tenantId = UUID.fromString(TenantContext.getTenantId());
    var session = gameSessionService.startSession(tenantId, request.teamGenerationId());
    return ResponseEntity.ok(session);
  }

  @Operation(summary = "Encerra a sessão de jogos")
  @PostMapping("/end")
  public void endSession(Authentication auth) {
    UUID tenantId = UUID.fromString(TenantContext.getTenantId());
    gameSessionService.endSession(tenantId);
  }

  @Operation(summary = "Verifica se há sessão ativa")
  @GetMapping("/active")
  public boolean isActive(Authentication auth) {
    UUID tenantId = UUID.fromString(TenantContext.getTenantId());
    return gameSessionService.hasActiveSession(tenantId);
  }

  @PostMapping("/start-with-courts")
  public ResponseEntity<Map<String, Object>> startWithCourts(
          @Valid @RequestBody StartWithCourtsRequest request,
          Authentication auth) {
    UUID tenantId = UUID.fromString(TenantContext.getTenantId());
    teamGenerationService.startSessionWithCourts(
            tenantId,
            request.generationSessionId(),
            request.courts(),
            request.pointsPerSet(),
            request.setsToWin(),
            request.sessionDate(),
            request.sessionTime()
    );
    return ResponseEntity.ok(Map.of("sessionId", request.generationSessionId()));
  }

  // ==================== NOVOS ENDPOINTS PARA FRIENDLY GAMES ====================

  @GetMapping("/friendly")
  public ResponseEntity<List<FriendlySessionSummary>> listFriendly(Authentication auth) {
    UUID tenantId = UUID.fromString(TenantContext.getTenantId());
    return ResponseEntity.ok(friendlySessionService.listFriendlySessions(tenantId));
  }

  @GetMapping("/{sessionId}/details")
  public ResponseEntity<FriendlySessionDTO> details(@PathVariable UUID sessionId, Authentication auth) {
    UUID tenantId = UUID.fromString(TenantContext.getTenantId());
    return ResponseEntity.ok(friendlySessionService.getFriendlySessionDetails(tenantId, sessionId));
  }

  @GetMapping("/current")
  public ResponseEntity<FriendlySessionDTO> getCurrentSession() {
    UUID tenantId = UUID.fromString(TenantContext.getTenantId());
    return friendlySessionService.getCurrentSession(tenantId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.noContent().build());
  }

  @PostMapping("/{sessionId}/matches")
  public ResponseEntity<Map<String, String>> registerMatch(
          @PathVariable UUID sessionId,
          @Valid @RequestBody FriendlyMatchRequest request,
          Authentication auth) {
    UUID tenantId = UUID.fromString(TenantContext.getTenantId());
    friendlySessionService.registerMatch(tenantId, sessionId, request);
    return ResponseEntity.ok(Map.of("message", "Match registered"));
  }
}