package com.boraver.teamgenerator.service;

import com.boraver.teamgenerator.dto.championship.*;
import com.boraver.teamgenerator.dto.teams.SaveManualTeamsRequest;
import com.boraver.teamgenerator.entity.*;
import com.boraver.teamgenerator.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChampionshipService {

  private final ChampionshipRepository championshipRepository;
  private final ChampionshipTeamRepository championshipTeamRepository;
  private final ChampionshipMatchRepository championshipMatchRepository;
  private final ChampionshipStandingsRepository standingsRepository;
  private final TeamGenerationSessionRepository sessionRepository;
  private final GeneratedTeamRepository generatedTeamRepository;
  private final GeneratedTeamPlayerRepository teamPlayerRepository;
  private final PlayerRepository playerRepository;
  private final GameSessionRepository gameSessionRepository;
  private final ObjectMapper mapper;

  private static final List<String> KNOCKOUT_STAGE_ORDER = List.of("QUARTER", "SEMI", "FINAL");

  // ========================= CRIAÇÃO DO CAMPEONATO =========================

  @Transactional
  public ChampionshipResponse createChampionship(UUID tenantId, CreateChampionshipRequest request) {
    Optional<GameSession> sessionOpt = gameSessionRepository.findByTenantIdAndActiveTrue(tenantId);
    if (sessionOpt.isEmpty()) {
      throw new IllegalStateException("Não há sessão de jogos ativa. Inicie os jogos primeiro.");
    }
    GameSession gameSession = sessionOpt.get();

    Optional<TeamGenerationSession> teamGenOpt = sessionRepository.findByGameSession(gameSession);
    if (teamGenOpt.isEmpty()) {
      throw new IllegalStateException("Sessão de geração de times não encontrada para a sessão ativa.");
    }
    TeamGenerationSession teamGen = teamGenOpt.get();

    List<GeneratedTeam> generatedTeams = generatedTeamRepository.findAllBySessionIdOrderByTeamIndexAsc(teamGen.getId());
    int teamCount = generatedTeams.size();

    boolean hasPredefinedGroups = false;
    Map<Integer, Integer> teamGroupMap = new HashMap<>();
    int predefinedGroupsCount = request.groupsCount();

    if (teamGen.getRulesJson() != null && !teamGen.getRulesJson().isEmpty()) {
      try {
        ObjectNode rules = mapper.readValue(teamGen.getRulesJson(), ObjectNode.class);
        if (rules.has("teamGroups")) {
          JsonNode groupsNode = rules.get("teamGroups");
          for (Map.Entry<String, JsonNode> entry : groupsNode.properties()) {
            int teamIndex = Integer.parseInt(entry.getKey());
            int groupId = entry.getValue().asInt();
            teamGroupMap.put(teamIndex, groupId);
          }
          hasPredefinedGroups = !teamGroupMap.isEmpty();
          predefinedGroupsCount = rules.has("groupsCount") ? rules.get("groupsCount").asInt() : request.groupsCount();
        }
      } catch (Exception e) {
        hasPredefinedGroups = false;
      }
    }

    Championship championship = new Championship();
    championship.setTenantId(tenantId);
    championship.setName(request.name());
    championship.setFormat(request.format());
    championship.setTeamCount(teamCount);
    championship.setGenerationSession(teamGen);
    championship.setStatus("CREATED");
    championship.setMatchesType(request.matchesType());
    championship.setDefaultSetsToWin(request.setsToWin());
    championship.setDefaultPointsPerSet(request.pointsPerSet());
    championship.setDefaultTieBreakPoints(request.tieBreakPoints());

    Map<Integer, String> teamNames = request.teamNames();

    switch (request.format()) {
      case "GROUPS":
        if (hasPredefinedGroups) {
          championship.setGroupsCount(predefinedGroupsCount);
          createGroupsFromPredefined(championship, generatedTeams, teamGroupMap,
                  predefinedGroupsCount, request.qualifiedPerGroup(), teamNames);
        } else {
          championship.setGroupsCount(request.groupsCount());
          createGroupsFormat(championship, request, generatedTeams, teamNames);
        }
        break;
      case "KNOCKOUT":
        championship.setStatus("IN_PROGRESS"); // ou mantenha "CREATED"
        championship.setGroupsCount(0);        // sem grupos
        championship.setQualifiedPerGroup(0);  // sem classificados por grupo
        createKnockoutFormat(championship, generatedTeams, teamNames);
        break;
      case "LEAGUE":
        createLeagueFormat(championship, generatedTeams, teamNames);
        break;
      default:
        throw new IllegalArgumentException("Formato inválido: " + request.format());
    }

    championship = championshipRepository.save(championship);
    return mapToResponse(championship);
  }

  // ========================= MONTAGEM MANUAL =========================

  public Championship createChampionshipFromManual(UUID tenantId, SaveManualTeamsRequest request,
                                                   TeamGenerationSession session,
                                                   List<GeneratedTeam> generatedTeams,
                                                   Map<Integer, Integer> teamGroupMap,
                                                   Map<Integer, String> teamNames) {
    Championship championship = new Championship();
    championship.setTenantId(tenantId);
    championship.setName(request.name());
    championship.setFormat(request.format() != null ? request.format() : "GROUPS");
    championship.setGenerationSession(session);
    championship.setStatus("CREATED");
    championship.setMatchesType(request.matchesType());
    championship.setQualifiedPerGroup(request.qualifiedPerGroup());
    championship.setGroupsCount(request.groupsCount());
    championship.setTeamCount(generatedTeams.size());
    championship.setDefaultSetsToWin(request.setsToWin());
    championship.setDefaultPointsPerSet(request.pointsPerSet());
    championship.setDefaultTieBreakPoints(request.tieBreakPoints());
    championship = championshipRepository.save(championship);

    if ("KNOCKOUT".equals(championship.getFormat())) {
      createKnockoutFormat(championship, generatedTeams, teamNames);
    } else {
      createGroupsFromPredefined(championship, generatedTeams, teamGroupMap,
              request.groupsCount(), request.qualifiedPerGroup(), teamNames);
    }

    return championship;
  }

  // ========================= GRUPOS (PRÉ-DEFINIDOS E AUTOMÁTICO) =========================

  private void createGroupsFromPredefined(Championship championship, List<GeneratedTeam> generatedTeams,
                                          Map<Integer, Integer> teamGroupMap, int groupsCount,
                                          int qualifiedPerGroup, Map<Integer, String> teamNames) {
    Map<Integer, List<GeneratedTeam>> groups = new HashMap<>();
    for (GeneratedTeam team : generatedTeams) {
      int groupId = teamGroupMap.getOrDefault(team.getTeamIndex(), 1);
      groups.computeIfAbsent(groupId, k -> new ArrayList<>()).add(team);
    }

    for (int g = 1; g <= groupsCount; g++) {
      if (!groups.containsKey(g) || groups.get(g).isEmpty()) {
        throw new IllegalArgumentException("Grupo " + g + " não possui times definidos.");
      }
    }

    int matchRound = 1;
    for (int g = 1; g <= groupsCount; g++) {
      List<GeneratedTeam> groupTeams = groups.get(g);
      for (int pos = 0; pos < groupTeams.size(); pos++) {
        GeneratedTeam gt = groupTeams.get(pos);
        ChampionshipTeam ct = new ChampionshipTeam();
        ct.setChampionshipId(championship.getId());
        ct.setTeamIndex(gt.getTeamIndex());
        ct.setGroupIndex(g);
        ct.setSeed(pos + 1);
        if (!"MANUAL".equals(championship.getGenerationSession().getMode())) {
          ct.setInitialScore(BigDecimal.valueOf(calculateTeamAverageScore(gt)));
        } else {
          ct.setInitialScore(BigDecimal.ZERO);
        }
        String teamName = (teamNames != null && teamNames.containsKey(gt.getTeamIndex()))
                ? teamNames.get(gt.getTeamIndex())
                : "Time " + gt.getTeamIndex();
        ct.setName(teamName);
        championshipTeamRepository.save(ct);
      }

      initializeStandingsForGroup(championship.getId(), g, groupTeams);

      for (int i = 0; i < groupTeams.size(); i++) {
        for (int j = i + 1; j < groupTeams.size(); j++) {
          GeneratedTeam teamA = groupTeams.get(i);
          GeneratedTeam teamB = groupTeams.get(j);
          ChampionshipMatch match1 = new ChampionshipMatch();
          match1.setChampionshipId(championship.getId());
          match1.setGroupIndex(g);
          match1.setRound(matchRound);
          match1.setStage("GROUP");
          match1.setHomeTeamIndex(teamA.getTeamIndex());
          match1.setAwayTeamIndex(teamB.getTeamIndex());
          match1.setSetsToWin(championship.getDefaultSetsToWin());
          match1.setPointsPerSet(championship.getDefaultPointsPerSet());
          match1.setTieBreakPoints(championship.getDefaultTieBreakPoints());
          championshipMatchRepository.save(match1);
          matchRound++;
          if ("HOME_AND_AWAY".equals(championship.getMatchesType())) {
            ChampionshipMatch match2 = new ChampionshipMatch();
            match2.setChampionshipId(championship.getId());
            match2.setGroupIndex(g);
            match2.setRound(matchRound);
            match2.setHomeTeamIndex(teamB.getTeamIndex());
            match2.setAwayTeamIndex(teamA.getTeamIndex());
            match2.setSetsToWin(championship.getDefaultSetsToWin());
            match2.setPointsPerSet(championship.getDefaultPointsPerSet());
            match2.setTieBreakPoints(championship.getDefaultTieBreakPoints());
            championshipMatchRepository.save(match2);
            matchRound++;
          }
        }
      }
    }

    championship.setGroupsCount(groupsCount);
    championship.setTeamsPerGroup(groups.get(1).size());
    championship.setQualifiedPerGroup(qualifiedPerGroup);
  }

  private void createGroupsFormat(Championship championship, CreateChampionshipRequest request,
                                  List<GeneratedTeam> generatedTeams, Map<Integer, String> teamNames) {
    int teamCount = generatedTeams.size();
    int groupsCount = request.groupsCount();
    if (teamCount < groupsCount) {
      throw new IllegalArgumentException("Número de times (" + teamCount + ") não pode ser menor que o número de grupos (" + groupsCount + ")");
    }

    int baseSize = teamCount / groupsCount;
    int remainder = teamCount % groupsCount;
    int[] groupSizes = new int[groupsCount];
    for (int i = 0; i < groupsCount; i++) {
      groupSizes[i] = baseSize + (i < remainder ? 1 : 0);
    }

    int qualifiedPerGroup = request.qualifiedPerGroup();
    int minGroupSize = Arrays.stream(groupSizes).min().orElse(0);
    if (qualifiedPerGroup > minGroupSize) {
      throw new IllegalArgumentException("Número de classificados por grupo (" + qualifiedPerGroup +
              ") não pode ser maior que o menor grupo (" + minGroupSize + ")");
    }

    List<GeneratedTeam> sortedTeams = generatedTeams.stream()
            .sorted(Comparator.comparingDouble(this::calculateTeamAverageScore).reversed())
            .toList();

    List<List<GeneratedTeam>> groups = new ArrayList<>();
    for (int i = 0; i < groupsCount; i++) groups.add(new ArrayList<>());

    int direction = 1;
    int currentGroup = 0;
    for (GeneratedTeam sortedTeam : sortedTeams) {
      groups.get(currentGroup).add(sortedTeam);
      currentGroup += direction;
      if (currentGroup == groupsCount) {
        currentGroup = groupsCount - 1;
        direction = -1;
      } else if (currentGroup == -1) {
        currentGroup = 0;
        direction = 1;
      }
    }

    int matchRound = 1;
    for (int g = 0; g < groupsCount; g++) {
      List<GeneratedTeam> groupTeams = groups.get(g);
      for (int pos = 0; pos < groupTeams.size(); pos++) {
        GeneratedTeam gt = groupTeams.get(pos);
        ChampionshipTeam ct = new ChampionshipTeam();
        ct.setChampionshipId(championship.getId());
        ct.setTeamIndex(gt.getTeamIndex());
        ct.setGroupIndex(g + 1);
        ct.setSeed(pos + 1);
        if (!"MANUAL".equals(championship.getGenerationSession().getMode())) {
          ct.setInitialScore(BigDecimal.valueOf(calculateTeamAverageScore(gt)));
        } else {
          ct.setInitialScore(BigDecimal.ZERO);
        }
        String teamName = (teamNames != null && teamNames.containsKey(gt.getTeamIndex()))
                ? teamNames.get(gt.getTeamIndex())
                : "Time " + gt.getTeamIndex();
        ct.setName(teamName);
        championshipTeamRepository.save(ct);
      }

      initializeStandingsForGroup(championship.getId(), g + 1, groupTeams);

      for (int i = 0; i < groupTeams.size(); i++) {
        for (int j = i + 1; j < groupTeams.size(); j++) {
          GeneratedTeam teamA = groupTeams.get(i);
          GeneratedTeam teamB = groupTeams.get(j);
          ChampionshipMatch match1 = new ChampionshipMatch();
          match1.setChampionshipId(championship.getId());
          match1.setGroupIndex(g + 1);
          match1.setRound(matchRound);
          match1.setStage("GROUP");
          match1.setHomeTeamIndex(teamA.getTeamIndex());
          match1.setAwayTeamIndex(teamB.getTeamIndex());
          match1.setSetsToWin(championship.getDefaultSetsToWin());
          match1.setPointsPerSet(championship.getDefaultPointsPerSet());
          match1.setTieBreakPoints(championship.getDefaultTieBreakPoints());
          championshipMatchRepository.save(match1);
          matchRound++;
          if ("HOME_AND_AWAY".equals(championship.getMatchesType())) {
            ChampionshipMatch match2 = new ChampionshipMatch();
            match2.setChampionshipId(championship.getId());
            match2.setGroupIndex(g + 1);
            match2.setRound(matchRound);
            match2.setHomeTeamIndex(teamB.getTeamIndex());
            match2.setAwayTeamIndex(teamA.getTeamIndex());
            match2.setSetsToWin(championship.getDefaultSetsToWin());
            match2.setPointsPerSet(championship.getDefaultPointsPerSet());
            match2.setTieBreakPoints(championship.getDefaultTieBreakPoints());
            championshipMatchRepository.save(match2);
            matchRound++;
          }
        }
      }
    }

    championship.setGroupsCount(groupsCount);
    championship.setTeamsPerGroup(baseSize);
    championship.setQualifiedPerGroup(qualifiedPerGroup);
    championshipRepository.save(championship);
  }

  private void createKnockoutFormat(Championship championship, List<GeneratedTeam> generatedTeams,
                                    Map<Integer, String> teamNames) {
    int teamCount = generatedTeams.size();
    if (teamCount % 2 != 0) {
      throw new IllegalArgumentException("Número de times deve ser par para eliminatórias");
    }
    if ((teamCount & (teamCount - 1)) != 0) {
      throw new IllegalArgumentException("Número de times deve ser potência de 2 (4, 8, 16...)");
    }

    List<GeneratedTeam> sortedTeams = generatedTeams.stream()
            .sorted(Comparator.comparingDouble(this::calculateTeamAverageScore).reversed())
            .toList();

    // Determina a primeira fase
    int numMatches = teamCount / 2;
    String firstStage = getInitialKnockoutStage(teamCount);

    // Cria os ChampionshipTeam (se ainda não existirem)
    for (GeneratedTeam gt : sortedTeams) {
      ChampionshipTeam ct = new ChampionshipTeam();
      ct.setChampionshipId(championship.getId());
      ct.setTeamIndex(gt.getTeamIndex());
      ct.setName(teamNames != null && teamNames.containsKey(gt.getTeamIndex())
              ? teamNames.get(gt.getTeamIndex())
              : "Time " + gt.getTeamIndex());
      championshipTeamRepository.save(ct);
    }

    // Cria as partidas da primeira rodada
    int round = 1;
    for (int i = 0; i < numMatches; i++) {
      GeneratedTeam teamA = sortedTeams.get(i);
      GeneratedTeam teamB = sortedTeams.get(teamCount - 1 - i);

      ChampionshipMatch match = new ChampionshipMatch();
      match.setChampionshipId(championship.getId());
      match.setStage(firstStage);
      match.setRound(round++);
      match.setHomeTeamIndex(teamA.getTeamIndex());
      match.setAwayTeamIndex(teamB.getTeamIndex());
      match.setStatus("pending");

      // Herda configurações do campeonato
      match.setSetsToWin(championship.getDefaultSetsToWin());
      match.setPointsPerSet(championship.getDefaultPointsPerSet());
      match.setTieBreakPoints(championship.getDefaultTieBreakPoints());

      championshipMatchRepository.save(match);
    }
  }

  private String getInitialKnockoutStage(int teamCount) {
    if (teamCount == 2) return "FINAL";
    if (teamCount == 4) return "SEMI";
    return "QUARTER";
  }

  private void createLeagueFormat(Championship championship, List<GeneratedTeam> generatedTeams,
                                  Map<Integer, String> teamNames) {
    int teamCount = generatedTeams.size();
    boolean homeAndAway = true;
    int round = 1;
    for (int i = 0; i < teamCount; i++) {
      for (int j = i + 1; j < teamCount; j++) {
        GeneratedTeam teamA = generatedTeams.get(i);
        GeneratedTeam teamB = generatedTeams.get(j);
        ChampionshipMatch match1 = new ChampionshipMatch();
        match1.setChampionshipId(championship.getId());
        match1.setRound(round);
        match1.setHomeTeamIndex(teamA.getTeamIndex());
        match1.setAwayTeamIndex(teamB.getTeamIndex());
        championshipMatchRepository.save(match1);
        round++;
        if (homeAndAway) {
          ChampionshipMatch match2 = new ChampionshipMatch();
          match2.setChampionshipId(championship.getId());
          match2.setRound(round);
          match2.setHomeTeamIndex(teamB.getTeamIndex());
          match2.setAwayTeamIndex(teamA.getTeamIndex());
          championshipMatchRepository.save(match2);
          round++;
        }
      }
    }
  }

  // ========================= PARTIDAS E RESULTADOS =========================

  @Transactional
  public MatchStartResponse startMatch(UUID championshipId, UUID matchId) {
    ChampionshipMatch match = championshipMatchRepository.findById(matchId)
            .orElseThrow(() -> new IllegalArgumentException("Partida não encontrada"));

    if (!match.getChampionshipId().equals(championshipId)) {
      throw new SecurityException("Partida não pertence ao campeonato informado");
    }

    if ("finished".equals(match.getStatus())) {
      throw new IllegalStateException("Partida já foi finalizada.");
    }

    Championship championship = championshipRepository.findById(championshipId)
            .orElseThrow(() -> new IllegalArgumentException("Campeonato não encontrado"));

    TeamGenerationSession generationSession = championship.getGenerationSession();
    if (generationSession == null) {
      throw new IllegalStateException("Campeonato não possui sessão de geração associada");
    }

    GameSession gameSession = generationSession.getGameSession();
    if (gameSession == null || !gameSession.isActive()) {
      throw new IllegalStateException("Não há sessão de jogos ativa para este campeonato. Inicie a sessão primeiro.");
    }

    List<GeneratedTeam> teams = generatedTeamRepository.findAllBySessionIdOrderByTeamIndexAsc(generationSession.getId());

    GeneratedTeam homeTeam = teams.stream()
            .filter(t -> t.getTeamIndex() == match.getHomeTeamIndex())
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Time mandante não encontrado"));
    GeneratedTeam awayTeam = teams.stream()
            .filter(t -> t.getTeamIndex() == match.getAwayTeamIndex())
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Time visitante não encontrado"));

    List<GeneratedTeamPlayer> homePlayers = teamPlayerRepository.findByTeamId(homeTeam.getId());
    List<GeneratedTeamPlayer> awayPlayers = teamPlayerRepository.findByTeamId(awayTeam.getId());

    List<UUID> allPlayerIds = new ArrayList<>();
    allPlayerIds.addAll(homePlayers.stream().map(GeneratedTeamPlayer::getPlayerId).toList());
    allPlayerIds.addAll(awayPlayers.stream().map(GeneratedTeamPlayer::getPlayerId).toList());

    Map<UUID, Player> playerMap = playerRepository.findAllById(allPlayerIds).stream()
            .collect(Collectors.toMap(Player::getId, Function.identity()));

    List<MatchStartResponse.PlayerInfo> homePlayerInfo = homePlayers.stream()
            .map(gtp -> {
              Player p = playerMap.get(gtp.getPlayerId());
              return new MatchStartResponse.PlayerInfo(p.getId(), p.getName(), p.getSex());
            })
            .collect(Collectors.toList());

    List<MatchStartResponse.PlayerInfo> awayPlayerInfo = awayPlayers.stream()
            .map(gtp -> {
              Player p = playerMap.get(gtp.getPlayerId());
              return new MatchStartResponse.PlayerInfo(p.getId(), p.getName(), p.getSex());
            })
            .collect(Collectors.toList());

    if ("pending".equals(match.getStatus())) {
      match.setStatus("in_progress");
      championshipMatchRepository.save(match);
    }

    return new MatchStartResponse(match.getId(), match.getHomeTeamIndex(), match.getAwayTeamIndex(),
            homePlayerInfo, awayPlayerInfo);
  }

  @Transactional
  public void registerMatchResult(UUID championshipId, MatchResultRequest request) {
    ChampionshipMatch match = championshipMatchRepository.findById(request.matchId())
            .orElseThrow(() -> new IllegalArgumentException("Partida não encontrada"));

    if (!match.getChampionshipId().equals(championshipId)) {
      throw new IllegalArgumentException("Partida não pertence a este campeonato");
    }

    boolean isWalkover = Boolean.TRUE.equals(request.walkover());

    if (isWalkover) {
      if (request.winnerTeamIndex() == null) {
        throw new IllegalArgumentException("Para WO, é necessário informar o time vencedor.");
      }
      int pointsForWinner = request.woWinnerPoints() != null ? request.woWinnerPoints() : 10;

      if (request.winnerTeamIndex().equals(match.getHomeTeamIndex())) {
        match.setHomeScore(pointsForWinner);
        match.setAwayScore(0);
        match.setHomeSetsWon(match.getSetsToWin());
        match.setAwaySetsWon(0);
      } else {
        match.setHomeScore(0);
        match.setAwayScore(pointsForWinner);
        match.setHomeSetsWon(0);
        match.setAwaySetsWon(match.getSetsToWin());
      }
      match.setWinnerTeamIndex(request.winnerTeamIndex());
    } else {
      // Registro de sets (se fornecidos)
      if (request.sets() != null && !request.sets().isEmpty()) {
        match.getSets().clear();
        int homeSets = 0, awaySets = 0;
        int setsToWin = match.getSetsToWin();

        for (MatchResultRequest.SetResult sr : request.sets()) {
          MatchSet ms = new MatchSet();
          ms.setMatch(match);
          ms.setSetNumber(sr.setNumber());
          ms.setHomeScore(sr.homeScore());
          ms.setAwayScore(sr.awayScore());
          match.getSets().add(ms);

          if (sr.homeScore() > sr.awayScore()) homeSets++;
          else if (sr.awayScore() > sr.homeScore()) awaySets++;
        }

        match.setHomeSetsWon(homeSets);
        match.setAwaySetsWon(awaySets);

        // Define o vencedor baseado nos sets
        if (homeSets >= setsToWin) {
          match.setWinnerTeamIndex(match.getHomeTeamIndex());
        } else if (awaySets >= setsToWin) {
          match.setWinnerTeamIndex(match.getAwayTeamIndex());
        } else {
          match.setWinnerTeamIndex(null);
        }

        // Placar total (soma dos pontos de todos os sets)
        int totalHomeScore = request.sets().stream().mapToInt(MatchResultRequest.SetResult::homeScore).sum();
        int totalAwayScore = request.sets().stream().mapToInt(MatchResultRequest.SetResult::awayScore).sum();
        match.setHomeScore(totalHomeScore);
        match.setAwayScore(totalAwayScore);
      } else {
        // Compatibilidade com versão antiga (sem sets detalhados)
        match.setHomeScore(request.homeScore() != null ? request.homeScore() : 0);
        match.setAwayScore(request.awayScore() != null ? request.awayScore() : 0);

        if (request.homeScore() != null && request.awayScore() != null) {
          if (request.homeScore() > request.awayScore()) {
            match.setWinnerTeamIndex(match.getHomeTeamIndex());
            match.setHomeSetsWon(match.getSetsToWin());
            match.setAwaySetsWon(0);
          } else if (request.homeScore() < request.awayScore()) {
            match.setWinnerTeamIndex(match.getAwayTeamIndex());
            match.setHomeSetsWon(0);
            match.setAwaySetsWon(match.getSetsToWin());
          } else {
            match.setWinnerTeamIndex(null);
            match.setHomeSetsWon(0);
            match.setAwaySetsWon(0);
          }
        } else {
          match.setHomeSetsWon(0);
          match.setAwaySetsWon(0);
        }
      }
    }

    match.setPlayed(true);
    match.setStatus("finished");
    championshipMatchRepository.save(match);

    if ("GROUP".equals(match.getStage())) {
      updateStandings(championshipId, match, isWalkover, request.woWinnerPoints());
    }

    Map<String, Object> update = new HashMap<>();
    update.put("type", "MATCH_RESULT_REGISTERED");
    update.put("matchId", match.getId());
    update.put("homeScore", match.getHomeScore());
    update.put("awayScore", match.getAwayScore());
    update.put("homeSetsWon", match.getHomeSetsWon());
    update.put("awaySetsWon", match.getAwaySetsWon());
    update.put("played", true);
    update.put("winnerTeamIndex", match.getWinnerTeamIndex());
    update.put("stage", match.getStage());
    update.put("walkover", isWalkover);
    if (match.getGroupIndex() != null) {
      update.put("groupIndex", match.getGroupIndex());
    }

    checkAndFinishChampionship(championshipId);
  }

  // ========================= FASE ELIMINATÓRIA (MATA‑MATA) =========================

  @Transactional
  public void generateNextKnockoutStage(UUID championshipId) {
    Championship championship = championshipRepository.findById(championshipId)
            .orElseThrow(() -> new IllegalArgumentException("Campeonato não encontrado"));

    List<ChampionshipMatch> allMatches = championshipMatchRepository.findByChampionshipIdOrderByRoundAsc(championshipId);
    List<ChampionshipMatch> knockoutMatches = allMatches.stream()
            .filter(m -> !"GROUP".equals(m.getStage()))
            .collect(Collectors.toList());

    if (knockoutMatches.isEmpty()) {
      generateFirstKnockoutStage(championshipId);
      return;
    }

    String currentStage = findCurrentKnockoutStage(knockoutMatches);

    boolean stageComplete = knockoutMatches.stream()
            .filter(m -> currentStage.equals(m.getStage()))
            .allMatch(m -> "finished".equals(m.getStatus()));
    if (!stageComplete) {
      throw new IllegalStateException("A fase " + currentStage + " ainda não foi concluída.");
    }

    if ("FINAL".equals(currentStage)) {
      throw new IllegalStateException("A final já foi disputada. O campeonato está encerrado.");
    }

    String nextStage = getNextStage(currentStage);

    boolean nextStageExists = knockoutMatches.stream()
            .anyMatch(m -> nextStage.equals(m.getStage()));
    if (nextStageExists) {
      throw new IllegalStateException("A fase " + nextStage + " já foi gerada.");
    }

    List<ChampionshipMatch> currentMatches = knockoutMatches.stream()
            .filter(m -> currentStage.equals(m.getStage()))
            .sorted(Comparator.comparingInt(ChampionshipMatch::getRound))
            .collect(Collectors.toList());

    List<Integer> winners = new ArrayList<>();
    for (ChampionshipMatch match : currentMatches) {
      if (match.getWinnerTeamIndex() == null) {
        throw new IllegalStateException("Partida #" + match.getRound() + " da fase " + currentStage + " não possui vencedor.");
      }
      winners.add(match.getWinnerTeamIndex());
    }

    List<ChampionshipMatch> nextMatches = new ArrayList<>();
    int nextRound = currentMatches.stream()
            .mapToInt(ChampionshipMatch::getRound)
            .max().orElse(0) + 1;

    for (int i = 0; i < winners.size(); i += 2) {
      if (i + 1 >= winners.size()) {
        throw new IllegalStateException("Número ímpar de vencedores – impossível gerar próxima fase.");
      }
      ChampionshipMatch match = new ChampionshipMatch();
      match.setChampionshipId(championshipId);
      match.setStage(nextStage);
      match.setRound(nextRound++);
      match.setHomeTeamIndex(winners.get(i));
      match.setAwayTeamIndex(winners.get(i + 1));
      match.setStatus("pending");
      match.setSetsToWin(championship.getDefaultSetsToWin());
      match.setPointsPerSet(championship.getDefaultPointsPerSet());
      match.setTieBreakPoints(championship.getDefaultTieBreakPoints());
      nextMatches.add(match);
    }

    championshipMatchRepository.saveAll(nextMatches);
  }

  private void generateFirstKnockoutStage(UUID championshipId) {
    Championship championship = championshipRepository.findById(championshipId)
            .orElseThrow(() -> new IllegalArgumentException("Campeonato não encontrado"));

    List<ChampionshipMatch> allMatches = championshipMatchRepository
            .findByChampionshipIdOrderByRoundAsc(championshipId);
    List<ChampionshipMatch> existingKnockout = allMatches.stream()
            .filter(m -> !"GROUP".equals(m.getStage()))
            .collect(Collectors.toList());
    if (!existingKnockout.isEmpty()) {
      championshipMatchRepository.deleteAll(existingKnockout);
    }

    // Se for KNOCKOUT puro, não gera a primeira fase aqui (já foi criada no createKnockoutFormat)
    if ("KNOCKOUT".equals(championship.getFormat())) {
      return;
    }

    // Para GROUPS e LEAGUE: busca classificação dos grupos
    List<ChampionshipStandings> standings = standingsRepository
            .findByChampionshipIdOrderByGroupIndexAscPointsDescSetsDifferenceDescGoalsDifferenceDescGoalsForAsc(championshipId);
    Map<Integer, List<ChampionshipStandings>> groups = standings.stream()
            .filter(s -> s.getGroupIndex() != null)
            .collect(Collectors.groupingBy(ChampionshipStandings::getGroupIndex));

    int groupsCount = championship.getGroupsCount();
    int qualifiedPerGroup = championship.getQualifiedPerGroup();

    // Coleta os classificados por grupo
    List<List<ChampionshipStandings>> qualifiedByGroup = new ArrayList<>();
    for (int g = 1; g <= groupsCount; g++) {
      List<ChampionshipStandings> groupStandings = groups.get(g);
      if (groupStandings == null) continue;
      List<ChampionshipStandings> qualified = groupStandings.stream()
              .limit(qualifiedPerGroup)
              .collect(Collectors.toList());
      qualifiedByGroup.add(qualified);
    }

    int totalQualified = qualifiedByGroup.stream().mapToInt(List::size).sum();

    // Validação: número de classificados deve ser potência de 2
    if (totalQualified < 2 || (totalQualified & (totalQualified - 1)) != 0) {
      throw new IllegalStateException(
              "Número de classificados (" + totalQualified + ") não é potência de 2. " +
                      "Impossível gerar chaveamento eliminatório padrão.");
    }

    // Define a primeira fase do mata‑mata
    String firstStage;
    if (totalQualified == 2) {
      firstStage = "FINAL";
    } else if (totalQualified == 4) {
      firstStage = "SEMI";
    } else {
      firstStage = "QUARTER";
    }

    List<ChampionshipMatch> knockoutMatches = new ArrayList<>();
    int round = allMatches.stream()
            .mapToInt(ChampionshipMatch::getRound)
            .max().orElse(0) + 1;

    // ========== GERAÇÃO DOS CONFRONTOS ==========
    if (totalQualified == 2) {
      // Final direta (apenas 2 times classificados)
      List<ChampionshipStandings> allQualified = qualifiedByGroup.stream()
              .flatMap(List::stream)
              .sorted(Comparator.comparingInt(ChampionshipStandings::getPoints).reversed()
                      .thenComparingInt(ChampionshipStandings::getSetsDifference).reversed()
                      .thenComparingInt(ChampionshipStandings::getGoalsDifference).reversed())
              .collect(Collectors.toList());

      if (allQualified.size() == 2) {
        ChampionshipStandings home = allQualified.get(0);
        ChampionshipStandings away = allQualified.get(1);

        ChampionshipMatch match = createKnockoutMatch(championship, firstStage, round,
                home.getTeamIndex(), away.getTeamIndex());
        knockoutMatches.add(match);
      }
    } else {
      // Chaveamento cruzado entre grupos adjacentes (4, 8, 16... times)
      for (int i = 0; i < qualifiedByGroup.size(); i += 2) {
        if (i + 1 >= qualifiedByGroup.size()) break;

        List<ChampionshipStandings> groupA = qualifiedByGroup.get(i);
        List<ChampionshipStandings> groupB = qualifiedByGroup.get(i + 1);

        int qualSize = Math.min(groupA.size(), groupB.size());
        for (int j = 0; j < qualSize; j++) {
          ChampionshipStandings home = groupA.get(j);
          ChampionshipStandings away = groupB.get(qualSize - 1 - j);

          ChampionshipMatch match = createKnockoutMatch(championship, firstStage, round,
                  home.getTeamIndex(), away.getTeamIndex());
          knockoutMatches.add(match);
          round++;
        }
      }
    }

    if (knockoutMatches.isEmpty()) {
      throw new IllegalStateException("Não foi possível gerar confrontos para a fase eliminatória.");
    }

    championshipMatchRepository.saveAll(knockoutMatches);
  }

  // Método auxiliar para criar uma partida eliminatória com herança de configurações
  private ChampionshipMatch createKnockoutMatch(Championship championship, String stage, int round,
                                                int homeTeamIndex, int awayTeamIndex) {
    ChampionshipMatch match = new ChampionshipMatch();
    match.setChampionshipId(championship.getId());
    match.setStage(stage);
    match.setRound(round);
    match.setHomeTeamIndex(homeTeamIndex);
    match.setAwayTeamIndex(awayTeamIndex);
    match.setStatus("pending");

    // Herda configurações de sets do campeonato
    match.setSetsToWin(championship.getDefaultSetsToWin());
    match.setPointsPerSet(championship.getDefaultPointsPerSet());
    match.setTieBreakPoints(championship.getDefaultTieBreakPoints());

    return match;
  }

  @Transactional
  public void generateThirdPlaceMatch(UUID championshipId) {
    Championship championship = championshipRepository.findById(championshipId)
            .orElseThrow(() -> new IllegalArgumentException("Campeonato não encontrado"));

    List<ChampionshipMatch> semiMatches = championshipMatchRepository.findByChampionshipIdAndStage(championshipId, "SEMI");
    if (semiMatches.size() != 2) {
      throw new IllegalStateException("Semifinais não estão completas ou não existem.");
    }
    boolean allSemiPlayed = semiMatches.stream().allMatch(ChampionshipMatch::isPlayed);
    if (!allSemiPlayed) {
      throw new IllegalStateException("Semifinais ainda não foram concluídas.");
    }

    List<ChampionshipMatch> thirdPlaceMatches = championshipMatchRepository.findByChampionshipIdAndStage(championshipId, "THIRD_PLACE");
    if (!thirdPlaceMatches.isEmpty()) {
      throw new IllegalStateException("Disputa de 3º lugar já foi gerada.");
    }

    List<Integer> losers = new ArrayList<>();
    for (ChampionshipMatch match : semiMatches) {
      if (match.getWinnerTeamIndex().equals(match.getHomeTeamIndex())) {
        losers.add(match.getAwayTeamIndex());
      } else {
        losers.add(match.getHomeTeamIndex());
      }
    }
    if (losers.size() != 2) throw new IllegalStateException("Não foi possível determinar os perdedores.");

    ChampionshipMatch thirdPlace = new ChampionshipMatch();
    thirdPlace.setChampionshipId(championshipId);
    thirdPlace.setStage("THIRD_PLACE");
    thirdPlace.setRound(semiMatches.stream().mapToInt(ChampionshipMatch::getRound).max().orElse(0) + 1);
    thirdPlace.setHomeTeamIndex(losers.get(0));
    thirdPlace.setAwayTeamIndex(losers.get(1));
    thirdPlace.setStatus("pending");
    thirdPlace.setSetsToWin(championship.getDefaultSetsToWin());
    thirdPlace.setPointsPerSet(championship.getDefaultPointsPerSet());
    thirdPlace.setTieBreakPoints(championship.getDefaultTieBreakPoints());
    championshipMatchRepository.save(thirdPlace);
  }

  // ========================= MÉTODOS AUXILIARES PARA MATA‑MATA =========================

  private String findCurrentKnockoutStage(Collection<ChampionshipMatch> knockoutMatches) {
    Set<String> existingStages = knockoutMatches.stream()
            .map(ChampionshipMatch::getStage)
            .collect(Collectors.toSet());

    for (String stage : KNOCKOUT_STAGE_ORDER) {
      if (existingStages.contains(stage)) {
        boolean allFinished = knockoutMatches.stream()
                .filter(m -> stage.equals(m.getStage()))
                .allMatch(m -> "finished".equals(m.getStatus()));
        if (!allFinished) {
          return stage;
        }
      }
    }
    return KNOCKOUT_STAGE_ORDER.stream()
            .filter(existingStages::contains)
            .reduce((first, second) -> second)
            .orElseThrow(() -> new IllegalStateException("Nenhuma fase de mata‑mata encontrada"));
  }

  private String getNextStage(String current) {
    switch (current) {
      case "QUARTER": return "SEMI";
      case "SEMI":   return "FINAL";
      default: throw new IllegalStateException("Não há próxima fase após " + current);
    }
  }

  private void checkAndFinishChampionship(UUID championshipId) {
    List<ChampionshipMatch> finalMatches = championshipMatchRepository.findByChampionshipIdAndStage(championshipId, "FINAL");
    if (!finalMatches.isEmpty() && finalMatches.stream().allMatch(m -> "finished".equals(m.getStatus()))) {
      Championship champ = championshipRepository.findById(championshipId).orElseThrow();
      champ.setStatus("FINISHED");
      champ.setEndedAt(LocalDateTime.now());
      championshipRepository.save(champ);
    }
  }

  // ========================= AUXILIARES GERAIS =========================

  private double calculateTeamAverageScore(GeneratedTeam team) {
    List<GeneratedTeamPlayer> players = teamPlayerRepository.findByTeamId(team.getId());
    return players.stream().mapToDouble(p -> p.getScoreAtGeneration().doubleValue()).average().orElse(0);
  }

  private Integer getGroupIndexForTeam(UUID championshipId, int teamIndex) {
    return championshipTeamRepository.findByChampionshipId(championshipId).stream()
            .filter(ct -> ct.getTeamIndex() == teamIndex)
            .findFirst()
            .map(ChampionshipTeam::getGroupIndex)
            .orElse(null);
  }

  private void initializeStandingsForGroup(UUID championshipId, int groupIndex, List<GeneratedTeam> groupTeams) {
    List<ChampionshipStandings> standings = groupTeams.stream()
            .map(team -> {
              ChampionshipStandings s = new ChampionshipStandings();
              s.setChampionshipId(championshipId);
              s.setTeamIndex(team.getTeamIndex());
              s.setGroupIndex(groupIndex);
              s.setPoints(0);
              s.setPlayed(0);
              s.setWins(0);
              s.setDraws(0);
              s.setLosses(0);
              s.setGoalsFor(0);
              s.setGoalsAgainst(0);
              s.setGoalsDifference(0);
              return s;
            })
            .collect(Collectors.toList());
    standingsRepository.saveAll(standings);
  }

  // ========================= CLASSIFICAÇÃO (STANDINGS) =========================

  private ChampionshipStandings getOrCreateStanding(UUID championshipId, int teamIndex) {
    return standingsRepository.findByChampionshipIdAndTeamIndex(championshipId, teamIndex)
            .orElseGet(() -> {
              Integer groupIndex = getGroupIndexForTeam(championshipId, teamIndex);
              if (groupIndex == null) {
                throw new IllegalStateException("Time " + teamIndex + " não pertence a nenhum grupo no campeonato " + championshipId);
              }
              ChampionshipStandings s = new ChampionshipStandings();
              s.setChampionshipId(championshipId);
              s.setTeamIndex(teamIndex);
              s.setGroupIndex(groupIndex);
              s.setPoints(0);
              s.setPlayed(0);
              s.setWins(0);
              s.setDraws(0);
              s.setLosses(0);
              s.setGoalsFor(0);
              s.setGoalsAgainst(0);
              s.setGoalsDifference(0);
              return s;
            });
  }

  private void updateStandings(UUID championshipId, ChampionshipMatch match, boolean isWalkover, Integer woWinnerPoints) {
    ChampionshipStandings homeStanding = getOrCreateStanding(championshipId, match.getHomeTeamIndex());
    ChampionshipStandings awayStanding = getOrCreateStanding(championshipId, match.getAwayTeamIndex());

    homeStanding.setPlayed(homeStanding.getPlayed() + 1);
    awayStanding.setPlayed(awayStanding.getPlayed() + 1);

    // Gols (pontos totais)
    homeStanding.setGoalsFor(homeStanding.getGoalsFor() + match.getHomeScore());
    homeStanding.setGoalsAgainst(homeStanding.getGoalsAgainst() + match.getAwayScore());
    awayStanding.setGoalsFor(awayStanding.getGoalsFor() + match.getAwayScore());
    awayStanding.setGoalsAgainst(awayStanding.getGoalsAgainst() + match.getHomeScore());

    // Sets
    homeStanding.setSetsWon(homeStanding.getSetsWon() + match.getHomeSetsWon());
    homeStanding.setSetsLost(homeStanding.getSetsLost() + match.getAwaySetsWon());
    awayStanding.setSetsWon(awayStanding.getSetsWon() + match.getAwaySetsWon());
    awayStanding.setSetsLost(awayStanding.getSetsLost() + match.getHomeSetsWon());

    int homePoints = 0;
    int awayPoints = 0;

    if (isWalkover) {
      if (match.getWinnerTeamIndex().equals(match.getHomeTeamIndex())) {
        homePoints = 3;
        homeStanding.setWins(homeStanding.getWins() + 1);
        awayStanding.setLosses(awayStanding.getLosses() + 1);
      } else {
        awayPoints = 3;
        homeStanding.setLosses(homeStanding.getLosses() + 1);
        awayStanding.setWins(awayStanding.getWins() + 1);
      }
    } else {
      if (match.getHomeScore() > match.getAwayScore()) {
        homePoints = 3;
        awayPoints = 0;
        homeStanding.setWins(homeStanding.getWins() + 1);
        awayStanding.setLosses(awayStanding.getLosses() + 1);
      } else if (match.getHomeScore() < match.getAwayScore()) {
        homePoints = 0;
        awayPoints = 3;
        homeStanding.setLosses(homeStanding.getLosses() + 1);
        awayStanding.setWins(awayStanding.getWins() + 1);
      } else {
        homePoints = 1;
        awayPoints = 1;
        homeStanding.setDraws(homeStanding.getDraws() + 1);
        awayStanding.setDraws(awayStanding.getDraws() + 1);
      }
    }

    homeStanding.setPoints(homeStanding.getPoints() + homePoints);
    awayStanding.setPoints(awayStanding.getPoints() + awayPoints);

    // Saldo de pontos
    homeStanding.setGoalsDifference(homeStanding.getGoalsFor() - homeStanding.getGoalsAgainst());
    awayStanding.setGoalsDifference(awayStanding.getGoalsFor() - awayStanding.getGoalsAgainst());

    // Saldo de sets
    homeStanding.setSetsDifference(homeStanding.getSetsWon() - homeStanding.getSetsLost());
    awayStanding.setSetsDifference(awayStanding.getSetsWon() - awayStanding.getSetsLost());

    homeStanding.setLastUpdate(LocalDateTime.now());
    awayStanding.setLastUpdate(LocalDateTime.now());

    standingsRepository.save(homeStanding);
    standingsRepository.save(awayStanding);
  }

  // ========================= LISTAGEM E DETALHES =========================

  public List<ChampionshipSummary> listChampionships(UUID tenantId) {
    return championshipRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
            .map(this::mapToSummary)
            .collect(Collectors.toList());
  }

  public ChampionshipDetails getChampionshipDetails(UUID championshipId, UUID tenantId) {
    Championship championship = championshipRepository.findById(championshipId)
            .orElseThrow(() -> new IllegalArgumentException("Campeonato não encontrado"));
    if (!championship.getTenantId().equals(tenantId)) {
      throw new SecurityException("Acesso negado a este campeonato");
    }
    UUID generationSessionId = championship.getGenerationSession().getId();

    List<ChampionshipTeam> teams = championshipTeamRepository.findByChampionshipId(championshipId);
    List<ChampionshipStandings> standings = standingsRepository
            .findByChampionshipIdOrderByGroupIndexAscPointsDescGoalsDifferenceDescGoalsForAsc(championshipId);
    List<ChampionshipMatch> allMatches = championshipMatchRepository
            .findByChampionshipIdOrderByRoundAsc(championshipId);

    List<ChampionshipMatch> groupMatches = allMatches.stream()
            .filter(m -> "GROUP".equals(m.getStage()))
            .collect(Collectors.toList());
    List<ChampionshipMatch> knockoutMatches = allMatches.stream()
            .filter(m -> !"GROUP".equals(m.getStage()))
            .collect(Collectors.toList());

    // Mapa de nomes dos times
    Map<Integer, String> nameMap = teams.stream()
            .collect(Collectors.toMap(
                    ChampionshipTeam::getTeamIndex,
                    ct -> ct.getName() != null ? ct.getName() : "Time " + ct.getTeamIndex(),
                    (a, b) -> a));

    Map<Integer, List<StandingEntry>> standingsByGroup = new HashMap<>();
    Map<Integer, List<ChampionshipTeam>> teamsByGroup = teams.stream()
            .filter(ct -> ct.getGroupIndex() != null)
            .collect(Collectors.groupingBy(ChampionshipTeam::getGroupIndex));

    Map<Integer, ChampionshipStandings> standingsMap = standings.stream()
            .collect(Collectors.toMap(
                    ChampionshipStandings::getTeamIndex,
                    Function.identity(),
                    (a, b) -> a));

    for (Map.Entry<Integer, List<ChampionshipTeam>> entry : teamsByGroup.entrySet()) {
      Integer groupIdx = entry.getKey();
      List<StandingEntry> groupEntries = entry.getValue().stream()
              .map(ct -> {
                ChampionshipStandings standing = standingsMap.get(ct.getTeamIndex());
                if (standing != null) {
                  return toStandingEntry(standing, nameMap);
                } else {
                  return new StandingEntry(
                          ct.getTeamIndex(),
                          groupIdx,
                          0, 0, 0, 0, 0, 0, 0, 0,
                          nameMap.getOrDefault(ct.getTeamIndex(), "Time " + ct.getTeamIndex()),
                          0, 0, 0);
                }
              })
              .sorted(Comparator
                      .comparingInt(StandingEntry::points).reversed()
                      .thenComparing(Comparator.comparingInt(StandingEntry::goalsDifference).reversed())
                      .thenComparing(Comparator.comparingInt(StandingEntry::goalsFor).reversed())
                      .thenComparingInt(StandingEntry::teamIndex))
              .collect(Collectors.toList());
      standingsByGroup.put(groupIdx, groupEntries);
    }

    Map<Integer, List<MatchDetails>> matchesByGroup = groupMatches.stream()
            .filter(m -> m.getGroupIndex() != null)
            .collect(Collectors.groupingBy(ChampionshipMatch::getGroupIndex,
                    Collectors.mapping(m -> mapToMatchDetails(m, generationSessionId, nameMap), Collectors.toList())));

    List<MatchDetails> knockoutMatchDetails = knockoutMatches.stream()
            .map(m -> mapToMatchDetails(m, generationSessionId, nameMap))
            .collect(Collectors.toList());

    return new ChampionshipDetails(
            mapToResponse(championship),
            teams.stream().map(this::mapToTeamInfo).collect(Collectors.toList()),
            standingsByGroup,
            matchesByGroup,
            knockoutMatchDetails
    );
  }

  public List<StandingEntry> getGroupStandings(UUID championshipId, int groupIndex) {
    Map<Integer, String> nameMap = championshipTeamRepository
            .findByChampionshipId(championshipId)
            .stream()
            .collect(Collectors.toMap(
                    ChampionshipTeam::getTeamIndex,
                    ct -> ct.getName() != null ? ct.getName() : "Time " + ct.getTeamIndex(),
                    (a, b) -> a));

    List<ChampionshipStandings> standings = standingsRepository
            .findByChampionshipIdOrderByGroupIndexAscPointsDescGoalsDifferenceDescGoalsForAsc(championshipId)
            .stream()
            .filter(s -> s.getGroupIndex() != null && s.getGroupIndex() == groupIndex)
            .collect(Collectors.toList());

    if (standings.isEmpty()) {
      return championshipTeamRepository.findByChampionshipId(championshipId)
              .stream()
              .filter(ct -> ct.getGroupIndex() != null && ct.getGroupIndex() == groupIndex)
              .sorted(Comparator.comparingInt(ChampionshipTeam::getTeamIndex))
              .map(ct -> new StandingEntry(
                      ct.getTeamIndex(),
                      ct.getGroupIndex(),
                      0, 0, 0, 0, 0, 0, 0, 0,
                      nameMap.getOrDefault(ct.getTeamIndex(), "Time " + ct.getTeamIndex()),
                      0, 0, 0))  // ← ADICIONE setsWon, setsLost, setsDifference
              .collect(Collectors.toList());
    }

    standings.sort(Comparator
            .comparingInt(ChampionshipStandings::getPoints).reversed()
            .thenComparingInt(ChampionshipStandings::getSetsDifference).reversed()   // NOVO
            .thenComparingInt(ChampionshipStandings::getGoalsDifference).reversed()
            .thenComparingInt(ChampionshipStandings::getGoalsFor).reversed()
            .thenComparingInt(ChampionshipStandings::getTeamIndex));

    return standings.stream()
            .map(s -> new StandingEntry(
                    s.getTeamIndex(),
                    s.getGroupIndex(),
                    s.getPoints(),
                    s.getPlayed(),
                    s.getWins(),
                    s.getDraws(),
                    s.getLosses(),
                    s.getGoalsFor(),
                    s.getGoalsAgainst(),
                    s.getGoalsDifference(),
                    nameMap.getOrDefault(s.getTeamIndex(), "Time " + s.getTeamIndex()),
                    s.getSetsWon(),
                    s.getSetsLost(),
                    s.getSetsDifference()))
            .collect(Collectors.toList());
  }

  public List<MatchDetails> getGroupMatches(UUID championshipId, int groupIndex) {
    Championship championship = championshipRepository.findById(championshipId)
            .orElseThrow(() -> new IllegalArgumentException("Campeonato não encontrado"));
    UUID generationSessionId = championship.getGenerationSession().getId();
    Map<Integer, String> nameMap = buildTeamNameMap(championshipId);
    return championshipMatchRepository.findByChampionshipIdAndGroupIndexOrderByRoundAsc(championshipId, groupIndex).stream()
            .map(m -> mapToMatchDetails(m, generationSessionId, nameMap))
            .collect(Collectors.toList());
  }

  public List<MatchDetails> getAllMatches(UUID championshipId) {
    Championship championship = championshipRepository.findById(championshipId)
            .orElseThrow(() -> new IllegalArgumentException("Campeonato não encontrado"));
    UUID generationSessionId = championship.getGenerationSession().getId();
    Map<Integer, String> nameMap = buildTeamNameMap(championshipId);
    return championshipMatchRepository.findByChampionshipIdOrderByRoundAsc(championshipId).stream()
            .map(m -> mapToMatchDetails(m, generationSessionId, nameMap))
            .collect(Collectors.toList());
  }

  @Transactional
  public void deleteChampionship(UUID championshipId, UUID tenantId) {
    Championship championship = championshipRepository.findById(championshipId)
            .orElseThrow(() -> new IllegalArgumentException("Campeonato não encontrado"));
    if (!championship.getTenantId().equals(tenantId)) {
      throw new SecurityException("Acesso negado");
    }
    championshipRepository.delete(championship);
  }

  public MatchDetails getMatchDetails(UUID championshipId, UUID matchId) {
    ChampionshipMatch match = championshipMatchRepository.findById(matchId)
            .orElseThrow(() -> new IllegalArgumentException("Partida não encontrada"));
    if (!match.getChampionshipId().equals(championshipId)) {
      throw new IllegalArgumentException("Partida não pertence a este campeonato");
    }
    Championship championship = championshipRepository.findById(championshipId)
            .orElseThrow(() -> new IllegalArgumentException("Campeonato não encontrado"));
    UUID generationSessionId = championship.getGenerationSession().getId();
    Map<Integer, String> nameMap = buildTeamNameMap(championshipId);
    return mapToMatchDetails(match, generationSessionId, nameMap);
  }

  // ========================= MAPEAMENTOS DTO =========================

  private ChampionshipResponse mapToResponse(Championship c) {
    return new ChampionshipResponse(
            c.getId(), c.getName(), c.getCreatedAt(), c.getStartedAt(), c.getEndedAt(),
            c.getTeamCount(), c.getFormat(), c.getGroupsCount(), c.getTeamsPerGroup(),
            c.getQualifiedPerGroup(), c.getMatchesType(), c.getStatus(),
            c.getGenerationSession() != null ? c.getGenerationSession().getId() : null,
            c.getDefaultSetsToWin());
  }

  private ChampionshipSummary mapToSummary(Championship c) {
    return new ChampionshipSummary(c.getId(), c.getName(), c.getCreatedAt(), c.getStatus(),
            c.getTeamCount(), c.getGroupsCount());
  }

  private TeamInfo mapToTeamInfo(ChampionshipTeam ct) {
    return new TeamInfo(ct.getTeamIndex(), ct.getGroupIndex(), ct.getSeed(), ct.getInitialScore());
  }

  private StandingEntry toStandingEntry(ChampionshipStandings s, Map<Integer, String> nameMap) {
    return new StandingEntry(
            s.getTeamIndex(),
            s.getGroupIndex(),
            s.getPoints(),
            s.getPlayed(),
            s.getWins(),
            s.getDraws(),
            s.getLosses(),
            s.getGoalsFor(),
            s.getGoalsAgainst(),
            s.getGoalsDifference(),
            nameMap.getOrDefault(s.getTeamIndex(), "Time " + s.getTeamIndex()),
            s.getSetsWon(),
            s.getSetsLost(),
            s.getSetsDifference()
    );
  }

  private MatchDetails mapToMatchDetails(ChampionshipMatch m, UUID generationSessionId, Map<Integer, String> teamNameMap) {
    return new MatchDetails(
            m.getId(), m.getGroupIndex(), m.getRound(),
            m.getHomeTeamIndex(), m.getAwayTeamIndex(),
            m.getHomeScore(), m.getAwayScore(),
            m.isPlayed(), m.getWinnerTeamIndex(),
            generationSessionId, m.getStage(),
            teamNameMap.getOrDefault(m.getHomeTeamIndex(), "Time " + m.getHomeTeamIndex()),
            teamNameMap.getOrDefault(m.getAwayTeamIndex(), "Time " + m.getAwayTeamIndex()),
            m.getSetsToWin(),
            m.getPointsPerSet(),
            m.getTieBreakPoints(),
            m.getHomeSetsWon(),
            m.getAwaySetsWon()
    );
  }

  private Map<Integer, String> buildTeamNameMap(UUID championshipId) {
    return championshipTeamRepository.findByChampionshipId(championshipId)
            .stream()
            .collect(Collectors.toMap(
                    ChampionshipTeam::getTeamIndex,
                    ct -> ct.getName() != null ? ct.getName() : "Time " + ct.getTeamIndex(),
                    (a, b) -> a));
  }

  public long countByTenant(UUID tenantId) {
    return championshipRepository.countByTenantId(tenantId);
  }
  public long countByStatus(UUID tenantId, String status) {
    return championshipRepository.countByTenantIdAndStatus(tenantId, status);
  }

  public long countMatches(UUID tenantId) {
    return championshipMatchRepository.countByChampionshipTenantId(tenantId);
  }
}