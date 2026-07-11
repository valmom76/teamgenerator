package com.boraver.teamgenerator.controller;

import com.boraver.teamgenerator.common.TenantContext;
import com.boraver.teamgenerator.dto.player.*;
import com.boraver.teamgenerator.service.PlayerService;
import com.boraver.teamgenerator.service.RatingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.naming.LimitExceededException;
import java.util.*;

@RestController
@RequestMapping("/players")
@Tag(name = "Jogadores", description = "Endpoint para gestão de jogadores")
@AllArgsConstructor
public class PlayerController {

  private final PlayerService playerService;
  private final RatingService ratingService;

  @Operation(summary = "Cadastrar novo jogador")
  @PostMapping
  public PlayerResponse create(@Valid @RequestBody CreatePlayerRequest req) throws LimitExceededException {
    return playerService.create(req);
  }

  @Operation(summary = "Listar jogadores ativos")
  @GetMapping
  public List<PlayerResponse> listActive() {
    return playerService.listActive();
  }

  @Operation(summary = "Obter jogador pelo ID")
  @GetMapping("/{id}")
  public PlayerResponse get(@PathVariable UUID id) {
    return playerService.get(id);
  }

  @Operation(summary = "Atualizar jogador")
  @PutMapping("/{id}")
  public PlayerResponse update(@PathVariable UUID id, @RequestBody UpdatePlayerRequest req) {
    return playerService.update(id, req);
  }

  @Operation(summary = "Excluir jogador")
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    playerService.delete(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/performance")
  public List<PlayerPerformanceDTO> getPerformance() {
    UUID tenantId = UUID.fromString(TenantContext.getTenantId());
    return playerService.getPerformanceData(tenantId);
  }

  @GetMapping("/{playerId}/evolution")
  public List<PlayerEvolutionDTO> getEvolution(@PathVariable UUID playerId) {
    return ratingService.getEvolution(playerId);
  }

  @GetMapping("/with-ratings")
  public List<PlayerResponse> listActiveWithRatings() {
    return playerService.listActiveWithRatings();
  }
}
