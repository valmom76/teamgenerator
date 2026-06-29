package com.boraver.teamgenerator.service;

import com.boraver.teamgenerator.dto.game.*;
import com.boraver.teamgenerator.dto.match.FriendlyMatchRequest;
import com.boraver.teamgenerator.dto.match.FriendlySessionDTO;
import com.boraver.teamgenerator.dto.match.FriendlySessionSummary;
import com.boraver.teamgenerator.entity.*;
import com.boraver.teamgenerator.repository.*;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FriendlySessionService {

  private final TeamGenerationSessionRepository sessionRepository;
  private final GeneratedTeamRepository teamRepository;
  private final GeneratedTeamPlayerRepository teamPlayerRepository;
  private final PlayerRepository playerRepository;
  private final FriendlyMatchRepository friendlyMatchRepository;
  private final ObjectMapper mapper;

  public List<FriendlySessionSummary> listFriendlySessions(UUID tenantId) {
    List<TeamGenerationSession> sessions = sessionRepository
            .findByTenantIdOrderByCreatedAtDesc(tenantId);

    return sessions.stream()
            .filter(this::isFriendlyMode)
            .map(s -> {
              int teamCount = teamRepository.countBySessionId(s.getId());
              String date = s.getCreatedAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
              return new FriendlySessionSummary(s.getId(), date, teamCount);
            })
            .collect(Collectors.toList());
  }

  public FriendlySessionDTO getFriendlySessionDetails(UUID tenantId, UUID sessionId) {
    TeamGenerationSession session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
    if (!session.getTenantId().equals(tenantId)) {
      throw new SecurityException("Access denied");
    }

    JsonNode rules = parseRules(session.getRulesJson());

    String date = session.getCreatedAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

    String sessionDate = null;
    String sessionTime = null;

    if (session.getPlayDate() != null) {
      java.time.ZoneId zoneId = java.time.ZoneId.of("America/Fortaleza");
      java.time.ZonedDateTime localDateTime = session.getPlayDate().atZoneSameInstant(zoneId);
      sessionDate = localDateTime.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
      sessionTime = localDateTime.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    // Busca TODAS as partidas da sessão
    List<FriendlyMatch> allMatches = friendlyMatchRepository.findBySessionIdOrderByCreatedAt(sessionId);

    // Agrupa partidas por quadra
    Map<String, List<FriendlyMatch>> matchesByCourt = allMatches.stream()
            .collect(Collectors.groupingBy(FriendlyMatch::getCourtName));

    // Agora passa o terceiro argumento
    List<FriendlySessionDTO.CourtDTO> courts = buildCourtList(session.getId(), rules, matchesByCourt);

    int pointsPerSet = rules.has("pointsPerSet") ? rules.get("pointsPerSet").asInt() : 12;
    int setsToWin = rules.has("setsToWin") ? rules.get("setsToWin").asInt() : 1;

    return new FriendlySessionDTO(sessionId, date, courts, pointsPerSet, setsToWin, sessionDate, sessionTime);
  }

  @Transactional
  public FriendlyMatch registerMatch(UUID tenantId, UUID sessionId, FriendlyMatchRequest request) {
    TeamGenerationSession session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
    if (!session.getTenantId().equals(tenantId)) {
      throw new SecurityException("Access denied");
    }

    FriendlyMatch match = new FriendlyMatch();
    match.setSessionId(sessionId);
    match.setCourtName(request.courtName());
    match.setHomeTeamIndex(request.homeTeamIndex());
    match.setAwayTeamIndex(request.awayTeamIndex());
    match.setHomeScore(request.homeScore());
    match.setAwayScore(request.awayScore());
    match.setWalkover(Boolean.TRUE.equals(request.walkover()));
    match.setWinnerTeamIndex(request.winnerTeamIndex());
    match.setPlayed(true);
    match.setStatus("finished");
    return friendlyMatchRepository.save(match);
  }

  public Optional<FriendlySessionDTO> getCurrentSession(UUID tenantId) {
    List<TeamGenerationSession> sessions = sessionRepository
            .findByTenantIdOrderByCreatedAtDesc(tenantId);

    return sessions.stream()
            .filter(this::isFriendlyMode)
            .findFirst()
            .map(s -> getFriendlySessionDetails(tenantId, s.getId()));
  }

  // --- Métodos auxiliares privados ---

  private boolean isFriendlyMode(TeamGenerationSession session) {
    try {
      JsonNode rules = mapper.readTree(session.getRulesJson());
      return "avulso".equals(rules.get("mode").asText());
    } catch (Exception e) {
      return false;
    }
  }

  private JsonNode parseRules(String rulesJson) {
    if (rulesJson == null || rulesJson.isBlank()) {
      throw new IllegalStateException("Rules not found");
    }
    try {
      return mapper.readTree(rulesJson);
    } catch (Exception e) {
      throw new IllegalStateException("Error parsing session rules", e);
    }
  }

  private List<FriendlySessionDTO.CourtDTO> buildCourtList(
          UUID sessionId,
          JsonNode rules,
          Map<String, List<FriendlyMatch>> matchesByCourt) {

    List<FriendlySessionDTO.CourtDTO> courts = new ArrayList<>();
    JsonNode courtsArray = rules.get("courts");

    if (courtsArray != null) {
      for (JsonNode courtNode : courtsArray) {
        String name = courtNode.get("name").asText();
        List<Integer> indices = new ArrayList<>();
        courtNode.get("teamIndices").forEach(t -> indices.add(t.asInt()));

        // 1. Busca todos os times
        List<GeneratedTeam> teams = teamRepository.findAllBySessionIdAndTeamIndexIn(sessionId, indices);

        // 2. Coleta todos os IDs dos times
        List<UUID> teamIds = teams.stream().map(GeneratedTeam::getId).collect(Collectors.toList());

        // 3. Busca TODOS os jogadores de TODOS os times em UMA query
        List<GeneratedTeamPlayer> allPlayers = teamPlayerRepository.findByTeamIdIn(teamIds);

        // 4. Coleta todos os playerIds únicos
        Set<UUID> playerIds = allPlayers.stream()
                .map(GeneratedTeamPlayer::getPlayerId)
                .collect(Collectors.toSet());

        // 5. Busca TODOS os jogadores em UMA query
        Map<UUID, Player> playerMap = playerRepository.findAllById(playerIds).stream()
                .collect(Collectors.toMap(Player::getId, p -> p));

        // 6. Agrupa jogadores por time
        Map<UUID, List<GeneratedTeamPlayer>> playersByTeam = allPlayers.stream()
                .collect(Collectors.groupingBy(GeneratedTeamPlayer::getTeamId));

        // 7. Monta os TeamInfos
        List<FriendlySessionDTO.TeamInfo> teamInfos = teams.stream().map(team -> {
          List<GeneratedTeamPlayer> players = playersByTeam.getOrDefault(team.getId(), Collections.emptyList());

          double avg = players.stream()
                  .mapToDouble(p -> p.getScoreAtGeneration().doubleValue())
                  .average().orElse(0.0);

          int women = (int) players.stream()
                  .filter(p -> "F".equals(p.getSexAtGeneration())).count();

          List<String> names = players.stream()
                  .map(p -> {
                    Player player = playerMap.get(p.getPlayerId());
                    return player != null ? player.getName() : "Desconhecido";
                  })
                  .collect(Collectors.toList());

          String teamName = team.getName() != null ? team.getName() : "Team " + team.getTeamIndex();
          return new FriendlySessionDTO.TeamInfo(team.getTeamIndex(), teamName, avg, women, names);
        }).collect(Collectors.toList());

        // 8. Partidas desta quadra
        List<FriendlyMatch> courtMatches = matchesByCourt.getOrDefault(name, Collections.emptyList());
        List<FriendlySessionDTO.MatchInfo> matchInfos = courtMatches.stream().map(match -> {
          String homeName = teamInfos.stream()
                  .filter(t -> t.teamIndex() == match.getHomeTeamIndex())
                  .map(FriendlySessionDTO.TeamInfo::teamName)
                  .findFirst().orElse("Time " + match.getHomeTeamIndex());
          String awayName = teamInfos.stream()
                  .filter(t -> t.teamIndex() == match.getAwayTeamIndex())
                  .map(FriendlySessionDTO.TeamInfo::teamName)
                  .findFirst().orElse("Time " + match.getAwayTeamIndex());

          return new FriendlySessionDTO.MatchInfo(
                  homeName,
                  awayName,
                  match.getHomeTeamIndex(),
                  match.getAwayTeamIndex(),
                  match.getHomeScore() != null ? match.getHomeScore() : 0,
                  match.getAwayScore() != null ? match.getAwayScore() : 0,
                  match.getWinnerTeamIndex(),
                  match.isWalkover()
          );
        }).collect(Collectors.toList());

        courts.add(new FriendlySessionDTO.CourtDTO(name, teamInfos, matchInfos));
      }
    }
    return courts;
  }
}