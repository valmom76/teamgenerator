package com.boraver.teamgenerator.dto.championship;

import java.time.LocalDateTime;
import java.util.UUID;

public record ChampionshipResponse(
    UUID id,
    String name,
    LocalDateTime createdAt,
    LocalDateTime startedAt,
    LocalDateTime endedAt,
    int teamCount,
    String format,
    Integer groupsCount,
    Integer teamsPerGroup,
    Integer qualifiedPerGroup,
    String matchesType, // se aplicável
    String status,
    UUID generationSessionId,
    int defaultSetsToWin
) {}