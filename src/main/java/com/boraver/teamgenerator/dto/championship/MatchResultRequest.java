package com.boraver.teamgenerator.dto.championship;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record MatchResultRequest(
        @NotNull UUID matchId,
        Integer homeScore,
        Integer awayScore,
        Boolean walkover,
        Integer winnerTeamIndex,
        Integer woWinnerPoints,
        List<SetResult> sets
) {
  public record SetResult(int setNumber, int homeScore, int awayScore) {}
}