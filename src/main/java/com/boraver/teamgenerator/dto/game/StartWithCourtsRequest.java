package com.boraver.teamgenerator.dto.game;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record StartWithCourtsRequest(
        @NotNull UUID generationSessionId,
        @NotNull List<CourtAssignment> courts,
        int pointsPerSet,
        int setsToWin,
        String sessionDate,
        String sessionTime
) {
  public record CourtAssignment(
          @NotNull String name,
          @NotNull List<Integer> teamIndices
  ) {}
}