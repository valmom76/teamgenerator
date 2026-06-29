package com.boraver.teamgenerator.service;

import com.boraver.teamgenerator.common.TenantContext;
import com.boraver.teamgenerator.entity.*;
import com.boraver.teamgenerator.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SessionHistoryService {

  private final TeamGenerationSessionRepository sessionRepo;
  private final GeneratedTeamRepository generatedTeamRepo;

  public List<SessionSummaryDTO> getSessions() {
    UUID tenantId = UUID.fromString(TenantContext.getTenantId());
    List<TeamGenerationSession> sessions = sessionRepo.findByTenantIdOrderByCreatedAtDesc(tenantId);

    return sessions.stream()
            .filter(s -> "DB".equals(s.getMode()) || "POTS".equals(s.getMode()))  // ← filtro
            .map(session -> new SessionSummaryDTO(
                    session.getId().toString(),
                    session.getCreatedAt().toString(),
                    session.getMode(),
                    session.getTeamCount(),
                    session.getPlayersPerTeam(),
                    session.getPlayersCount(),
                    session.getPlayDate() != null ? session.getPlayDate().toString() : null
            ))
            .collect(Collectors.toList());
  }

  public SessionDetailDTO getSessionDetail(UUID sessionId) {
    TeamGenerationSession session = sessionRepo.findById(sessionId)
            .orElseThrow(() -> new RuntimeException("Sessão não encontrada"));

    List<GeneratedTeam> teams = generatedTeamRepo.findBySessionIdOrderByTeamIndex(sessionId);

    return new SessionDetailDTO(
            session.getId().toString(),
            session.getCreatedAt().toString(),
            session.getMode(),
            session.getTeamCount(),
            session.getPlayersPerTeam(),
            teams.stream().map(team -> new TeamDTO(
                    team.getTeamIndex(),
                    team.getName(),
                    team.getId().toString()
            )).collect(Collectors.toList())
    );
  }

  // DTOs
  public record SessionSummaryDTO(
          String sessionId,
          String createdAt,
          String mode,
          int teamCount,
          int playersPerTeam,
          int playersCount,
          String playDate
  ) {}

  public record SessionDetailDTO(
          String sessionId,
          String createdAt,
          String mode,
          int teamCount,
          int playersPerTeam,
          List<TeamDTO> teams
  ) {}

  public record TeamDTO(
          int teamIndex,
          String name,
          String teamId
  ) {}
}