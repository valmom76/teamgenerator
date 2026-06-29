package com.boraver.teamgenerator.dto.match;

import java.util.List;
import java.util.UUID;

public record FriendlySessionDTO(
        UUID sessionId,
        String dateFormatted,
        List<CourtDTO> courts,
        int pointsPerSet,
        int setsToWin,
        String sessionDate,
        String sessionTime
) {
  public record CourtDTO(
          String name,
          List<TeamInfo> teams,
          List<MatchInfo> matches    // ← novo
  ) {}

  public record TeamInfo(
          int teamIndex,
          String teamName,
          double avgScore,
          int women,
          List<String> playerNames
  ) {}

  public record MatchInfo(
         String homeTeamName,
         String awayTeamName,
         int homeTeamIndex,
         int awayTeamIndex,
         int homeScore,
         int awayScore,
         Integer winnerTeamIndex,
         boolean walkover
  ) {}
}