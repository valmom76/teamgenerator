package com.boraver.teamgenerator.service;

import com.boraver.teamgenerator.dto.match.ActiveSessionDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.boraver.teamgenerator.common.TenantContext;
import com.boraver.teamgenerator.dto.teams.*;
import com.boraver.teamgenerator.dto.game.*;
import com.boraver.teamgenerator.entity.*;
import com.boraver.teamgenerator.repository.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TeamGenerationService {

  private static final double DEFAULT_RATING = 2.5;
  private static final double BALANCE_THRESHOLD = 1.05;

  private final PlayerRepository playerRepository;
  private final RatingRepository ratingRepository;
  private final TeamGenerationSessionRepository sessionRepository;
  private final GeneratedTeamRepository teamRepository;
  private final GeneratedTeamPlayerRepository teamPlayerRepository;
  private final PlayerPositionRepository playerPositionRepository;
  private final ChampionshipService championshipService;
  private final ObjectMapper mapper;

  private UUID tenantId() {
    return UUID.fromString(Objects.requireNonNull(TenantContext.getTenantId(), "tenant missing"));
  }

  // =========================================================================
  // GERAÇÃO PRINCIPAL (DB)
  // =========================================================================

  @Transactional
  public GenerateTeamsResponse generateFromDb(GenerateTeamsRequest req, UUID createdBy) {
    UUID tenant = tenantId();

    validateRequest(req);

    int needed = req.teamCount() * req.playersPerTeam();
    List<Player> players = fetchActivePlayers(tenant, req.playerIds(), needed);
    List<UUID> playerIds = players.stream().map(Player::getId).toList();
    List<UUID> skillIds = req.selectedSkills().stream().map(GenerateTeamsRequest.SelectedSkill::skillId).toList();

    Map<UUID, Map<UUID, Integer>> ratingMap = buildRatingMap(tenant, playerIds, skillIds);
    Map<UUID, Double> skillAvg = computeSkillAverages(playerIds, skillIds, ratingMap);

    double multM = (req.sexMultiplier() != null && req.sexMultiplier().get("M") != null) ? req.sexMultiplier().get("M") : 1.0;
    double multF = (req.sexMultiplier() != null && req.sexMultiplier().get("F") != null) ? req.sexMultiplier().get("F") : 0.92;

    List<ScoredPlayer> scored = scorePlayers(players, req.selectedSkills(), ratingMap, skillAvg, multM, multF);
    scored.sort(Comparator.comparingDouble(ScoredPlayer::getFinalScore).reversed());

    List<TeamBucket> teams = createBuckets(req.teamCount());
    boolean sexBal = req.sexBalance() != null && req.sexBalance().enabled();
    int maxMaleDiff = req.sexBalance() != null ? Math.max(0, req.sexBalance().maxMaleDiff()) : 1;

    List<ScoredPlayer> selected = new ArrayList<>(scored.subList(0, needed));

    if (req.requiredPositions() != null && !req.requiredPositions().isEmpty()) {
      balancePositions(selected, teams, req.requiredPositions());
    }

    for (ScoredPlayer sp : selected) {
      TeamBucket chosen = chooseTeam(teams, sp, req.playersPerTeam(), sexBal, maxMaleDiff);
      chosen.add(sp);
    }

    TeamGenerationSession session = persistSession(tenant, createdBy, req, needed);
    persistTeams(session, teams);

    return buildResponse(session.getId(), teams);
  }

  // =========================================================================
  // GERAÇÃO POR POTES
  // =========================================================================

  @Transactional
  public SaveGeneratedResponse generateFromPots(UUID tenantId, UUID userId, SaveGeneratedRequest request) {
    if (request.teams() == null || request.teams().isEmpty()) {
      throw new IllegalArgumentException("Nenhum time enviado.");
    }

    int teamCount = request.teams().size();
    int playersPerTeam = request.teams().getFirst().players().size();

    List<UUID> allPlayerIds = request.teams().stream()
            .flatMap(t -> t.players().stream())
            .map(p -> UUID.fromString(p.id()))
            .collect(Collectors.toList());

    List<Player> players = playerRepository.findAllByIdInAndTenantId(allPlayerIds, tenantId);
    if (players.size() != allPlayerIds.size()) {
      throw new IllegalArgumentException("Alguns jogadores não foram encontrados ou não pertencem ao tenant.");
    }

    TeamGenerationSession session = new TeamGenerationSession();
    session.setTenantId(tenantId);
    session.setMode("POTS");
    session.setCreatedBy(userId);
    session.setTeamCount(teamCount);
    session.setPlayersPerTeam(playersPerTeam);
    session.setPlayersCount(teamCount * playersPerTeam);
    session.setRulesJson(toJson(mapper.valueToTree(request)));
    session = sessionRepository.save(session);

    List<GenerateTeamsResponse.Team> teamDtos = new ArrayList<>();
    for (SaveGeneratedRequest.TeamDTO teamDto : request.teams()) {
      GeneratedTeam gt = new GeneratedTeam();
      gt.setSessionId(session.getId());
      gt.setTeamIndex(teamDto.teamIndex());
      gt.setName("Time " + teamDto.teamIndex());
      gt = teamRepository.save(gt);

      double sumScore = 0;
      List<GenerateTeamsResponse.PlayerPick> picks = new ArrayList<>();
      for (SaveGeneratedRequest.PlayerPickDTO playerDto : teamDto.players()) {
        GeneratedTeamPlayer gtp = new GeneratedTeamPlayer();
        gtp.setTeamId(gt.getId());
        gtp.setPlayerId(UUID.fromString(playerDto.id()));
        gtp.setSexAtGeneration(playerDto.sex());
        gtp.setScoreAtGeneration(BigDecimal.valueOf(playerDto.score()));

        ObjectNode snap = mapper.createObjectNode();
        snap.put("method", "pots");
        snap.put("source", "pote_selection");
        gtp.setSnapshotJson(toJson(snap));
        teamPlayerRepository.save(gtp);

        picks.add(new GenerateTeamsResponse.PlayerPick(UUID.fromString(playerDto.id()), playerDto.name(), playerDto.sex(), playerDto.score()));
        sumScore += playerDto.score();
      }
      teamDtos.add(new GenerateTeamsResponse.Team(teamDto.teamIndex(), sumScore, picks));
    }

    return new SaveGeneratedResponse(session.getId(), teamDtos);
  }

  // =========================================================================
  // MONTAGEM MANUAL DE CAMPEONATO
  // =========================================================================

  @Transactional
  public SaveManualTeamsResponse saveManualTeams(UUID tenantId, UUID userId, SaveManualTeamsRequest request) throws JsonProcessingException {
    List<UUID> allPlayerIds = request.teams().stream().flatMap(t -> t.playerIds().stream()).collect(Collectors.toList());
    List<Player> players = playerRepository.findAllByIdInAndTenantId(allPlayerIds, tenantId);
    if (players.size() != allPlayerIds.size()) {
      throw new IllegalArgumentException("Alguns jogadores não foram encontrados ou não pertencem ao tenant");
    }

    TeamGenerationSession session = new TeamGenerationSession();
    session.setTenantId(tenantId);
    session.setMode("MANUAL");
    session.setCreatedBy(userId);
    session.setTeamCount(request.teams().size());
    session.setPlayersPerTeam(request.teams().get(0).playerIds().size());
    session.setPlayersCount(allPlayerIds.size());

    ObjectNode rules = mapper.createObjectNode();
    rules.put("name", request.name());
    rules.put("groupsCount", request.groupsCount());
    ObjectNode groupsNode = mapper.createObjectNode();
    for (SaveManualTeamsRequest.ManualTeamDTO team : request.teams()) {
      groupsNode.put(String.valueOf(team.teamIndex()), team.groupId());
    }
    rules.set("teamGroups", groupsNode);
    session.setRulesJson(mapper.writeValueAsString(rules));
    session = sessionRepository.save(session);

    List<GeneratedTeam> generatedTeams = new ArrayList<>();
    for (SaveManualTeamsRequest.ManualTeamDTO teamDto : request.teams()) {
      GeneratedTeam gt = new GeneratedTeam();
      gt.setSessionId(session.getId());
      gt.setTeamIndex(teamDto.teamIndex());
      gt.setName("Time " + teamDto.teamIndex());
      gt = teamRepository.save(gt);
      generatedTeams.add(gt);

      for (UUID playerId : teamDto.playerIds()) {
        Player player = players.stream().filter(p -> p.getId().equals(playerId)).findFirst().orElseThrow();
        GeneratedTeamPlayer gtp = new GeneratedTeamPlayer();
        gtp.setTeamId(gt.getId());
        gtp.setPlayerId(player.getId());
        gtp.setSexAtGeneration(String.valueOf(player.getSex()));
        gtp.setScoreAtGeneration(BigDecimal.ZERO);
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("method", "manual");
        gtp.setSnapshotJson(mapper.writeValueAsString(snapshot));
        teamPlayerRepository.save(gtp);
      }
    }

    Map<Integer, Integer> teamGroupMap = new HashMap<>();
    for (SaveManualTeamsRequest.ManualTeamDTO team : request.teams()) {
      teamGroupMap.put(team.teamIndex(), team.groupId());
    }

    Championship championship = championshipService.createChampionshipFromManual(tenantId, request, session, generatedTeams, teamGroupMap, request.teamNames());
    return new SaveManualTeamsResponse(session.getId(), championship.getId());
  }

  // =========================================================================
  // CONSULTAS
  // =========================================================================

  public List<GenerateTeamsResponse.Team> getTeamsBySession(UUID sessionId, UUID tenantId) {
    TeamGenerationSession session = sessionRepository.findById(sessionId).orElseThrow(() -> new IllegalArgumentException("Sessão não encontrada"));
    if (!session.getTenantId().equals(tenantId)) throw new SecurityException("Acesso negado");
    return getTeamsBySessionInternal(session).teams();
  }

  public GenerateTeamsResponse getLatestSession(UUID tenantId) {
    TeamGenerationSession latest = sessionRepository.findTopByTenantIdOrderByCreatedAtDesc(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Nenhuma sessão de geração encontrada"));
    return getTeamsBySessionInternal(latest);
  }

  public long countTeamsByTenant(UUID tenantId) {
    return teamRepository.countByTenantId(tenantId);
  }

  public ActiveSessionDTO getActiveSessionDetails(UUID tenantId, UUID sessionId) {
    TeamGenerationSession session = sessionRepository.findById(sessionId).orElseThrow(() -> new IllegalArgumentException("Session not found"));
    if (!session.getTenantId().equals(tenantId)) throw new SecurityException("Access denied");

    JsonNode rules = parseRulesJson(session.getRulesJson());
    List<ActiveSessionDTO.CourtDTO> courts = new ArrayList<>();
    JsonNode courtsArray = rules.get("courts");
    if (courtsArray != null) {
      for (JsonNode courtNode : courtsArray) {
        String courtName = courtNode.get("name").asText();
        List<Integer> teamIndices = new ArrayList<>();
        courtNode.get("teamIndices").forEach(t -> teamIndices.add(t.asInt()));

        List<GeneratedTeam> teams = teamRepository.findAllBySessionIdAndTeamIndexIn(session.getId(), teamIndices);
        List<ActiveSessionDTO.TeamInfo> teamInfos = teams.stream().map(team -> {
          List<GeneratedTeamPlayer> players = teamPlayerRepository.findByTeamId(team.getId());
          double avg = players.stream().mapToDouble(p -> p.getScoreAtGeneration().doubleValue()).average().orElse(0.0);
          int women = (int) players.stream().filter(p -> "F".equals(p.getSexAtGeneration())).count();
          List<String> names = players.stream().map(p -> playerRepository.findById(p.getPlayerId()).orElseThrow().getName()).collect(Collectors.toList());
          String teamName = team.getName() != null ? team.getName() : "Team " + team.getTeamIndex();
          return new ActiveSessionDTO.TeamInfo(team.getTeamIndex(), teamName, avg, women, names);
        }).collect(Collectors.toList());
        courts.add(new ActiveSessionDTO.CourtDTO(courtName, teamInfos));
      }
    }

    String formattedDate = session.getCreatedAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    return new ActiveSessionDTO(session.getId(), formattedDate, courts);
  }

  // =========================================================================
  // DISTRIBUIÇÃO / QUADRAS / MOVER JOGADOR
  // =========================================================================

  public DistributionSuggestion suggestDistribution(UUID sessionId, UUID tenantId, int courtCount, List<String> courtNames) {
    TeamGenerationSession session = sessionRepository.findById(sessionId).orElseThrow(() -> new IllegalArgumentException("Session not found"));
    if (!session.getTenantId().equals(tenantId)) throw new SecurityException("Access denied");

    List<GeneratedTeam> teams = teamRepository.findAllBySessionIdOrderByTeamIndexAsc(sessionId);

    record TeamStats(int teamIndex, String teamName, double avgRating, int womenCount) {}
    List<TeamStats> stats = teams.stream().map(team -> {
      List<GeneratedTeamPlayer> players = teamPlayerRepository.findByTeamId(team.getId());
      double avgRating = players.stream().mapToDouble(p -> p.getScoreAtGeneration().doubleValue()).average().orElse(0.0);
      int womenCount = (int) players.stream().filter(p -> "F".equals(p.getSexAtGeneration())).count();
      String teamName = team.getName() != null ? team.getName() : "Team " + team.getTeamIndex();
      return new TeamStats(team.getTeamIndex(), teamName, avgRating, womenCount);
    }).sorted(Comparator.comparingDouble(TeamStats::avgRating).reversed().thenComparingInt(TeamStats::womenCount).reversed()).toList();

    List<List<TeamStats>> courtBuckets = new ArrayList<>();
    for (int i = 0; i < courtCount; i++) courtBuckets.add(new ArrayList<>());

    int direction = 1, currentCourt = 0;
    for (TeamStats team : stats) {
      courtBuckets.get(currentCourt).add(team);
      currentCourt += direction;
      if (currentCourt == courtCount || currentCourt == -1) {
        direction *= -1;
        currentCourt += direction;
      }
    }

    List<DistributionSuggestion.CourtAllocation> courts = new ArrayList<>();
    for (int i = 0; i < courtCount; i++) {
      List<DistributionSuggestion.TeamInfo> teamInfos = courtBuckets.get(i).stream().map(ts -> new DistributionSuggestion.TeamInfo(ts.teamIndex(), ts.teamName(), ts.avgRating(), ts.womenCount())).collect(Collectors.toList());
      courts.add(new DistributionSuggestion.CourtAllocation(courtNames.get(i) != null ? courtNames.get(i) : "Court " + (i + 1), teamInfos));
    }
    return new DistributionSuggestion(courts);
  }

  @Transactional
  public TeamGenerationSession startSessionWithCourts(UUID tenantId, UUID sessionId, List<StartWithCourtsRequest.CourtAssignment> courts,
                                                      int pointsPerSet, int setsToWin, String sessionDate, String sessionTime) {
    TeamGenerationSession session = sessionRepository.findById(sessionId).orElseThrow(() -> new IllegalArgumentException("Sessão não encontrada"));
    if (!session.getTenantId().equals(tenantId)) throw new SecurityException("Acesso negado");

    ObjectNode rules = mapper.createObjectNode();
    rules.put("mode", "avulso");
    rules.put("pointsPerSet", pointsPerSet);
    rules.put("setsToWin", setsToWin);

    if (sessionDate != null && !sessionDate.isBlank() && sessionTime != null && !sessionTime.isBlank()) {
      try {
        String dateTimeStr = sessionDate + "T" + sessionTime + ":00";
        java.time.LocalDateTime localDateTime = java.time.LocalDateTime.parse(dateTimeStr);
        java.time.ZoneId zoneId = java.time.ZoneId.of("America/Fortaleza");
        OffsetDateTime playDate = localDateTime.atZone(zoneId).withZoneSameInstant(java.time.ZoneOffset.UTC).toOffsetDateTime();
        session.setPlayDate(playDate);
      } catch (Exception e) { /* log */ }
    }

    ArrayNode courtsArray = rules.putArray("courts");
    for (var court : courts) {
      ObjectNode courtNode = courtsArray.addObject();
      courtNode.put("name", court.name());
      ArrayNode teamsNode = courtNode.putArray("teamIndices");
      court.teamIndices().forEach(teamsNode::add);
    }
    session.setRulesJson(toJson(rules));
    return sessionRepository.save(session);
  }

  @Transactional
  public void movePlayer(UUID sessionId, UUID tenantId, MovePlayerRequest request) {
    TeamGenerationSession session = sessionRepository.findById(sessionId).orElseThrow(() -> new IllegalArgumentException("Sessão não encontrada"));
    if (!session.getTenantId().equals(tenantId)) throw new SecurityException("Acesso negado");

    GeneratedTeam fromTeam = teamRepository.findBySessionIdAndTeamIndex(sessionId, request.fromTeamIndex()).orElseThrow(() -> new IllegalArgumentException("Time de origem não encontrado"));
    GeneratedTeam toTeam = teamRepository.findBySessionIdAndTeamIndex(sessionId, request.toTeamIndex()).orElseThrow(() -> new IllegalArgumentException("Time de destino não encontrado"));

    GeneratedTeamPlayer playerToMove = teamPlayerRepository.findByTeamIdAndPlayerId(fromTeam.getId(), request.playerId()).orElseThrow(() -> new IllegalArgumentException("Jogador não está no time de origem"));
    playerToMove.setTeamId(toTeam.getId());
    teamPlayerRepository.save(playerToMove);
  }

  // =========================================================================
  // MÉTODOS PRIVADOS AUXILIARES
  // =========================================================================

  private void validateRequest(GenerateTeamsRequest req) {
    if (req.teamCount() <= 0 || req.playersPerTeam() <= 0) throw new IllegalArgumentException("teamCount and playersPerTeam must be > 0");
    if (req.playerIds() == null || req.playerIds().size() < req.teamCount() * req.playersPerTeam()) throw new IllegalArgumentException("Not enough players");
    if (req.selectedSkills() == null || req.selectedSkills().isEmpty()) throw new IllegalArgumentException("selectedSkills cannot be empty");
  }

  private List<Player> fetchActivePlayers(UUID tenant, List<UUID> playerIds, int needed) {
    List<Player> players = playerRepository.findAllByTenantIdAndIdIn(tenant, playerIds).stream().filter(Player::isActive).toList();
    if (players.size() < needed) throw new IllegalArgumentException("Not enough ACTIVE players. Needed=" + needed);
    return players;
  }

  private Map<UUID, Map<UUID, Integer>> buildRatingMap(UUID tenant, List<UUID> playerIds, List<UUID> skillIds) {
    var ratings = ratingRepository.findCurrentRatings(tenant, playerIds, skillIds);
    Map<UUID, Map<UUID, Integer>> ratingMap = new HashMap<>();
    for (var r : ratings) ratingMap.computeIfAbsent(r.getPlayerId(), k -> new HashMap<>()).put(r.getSkillId(), (int) r.getRating());
    return ratingMap;
  }

  private List<ScoredPlayer> scorePlayers(List<Player> players, List<GenerateTeamsRequest.SelectedSkill> selectedSkills,
                                          Map<UUID, Map<UUID, Integer>> ratingMap, Map<UUID, Double> skillAvg, double multM, double multF) {
    List<ScoredPlayer> scored = new ArrayList<>();
    for (Player p : players) {
      double base = 0.0;
      Map<UUID, Integer> pr = ratingMap.getOrDefault(p.getId(), Collections.emptyMap());
      for (GenerateTeamsRequest.SelectedSkill s : selectedSkills) {
        int r = pr.getOrDefault(s.skillId(), (int) Math.round(skillAvg.getOrDefault(s.skillId(), DEFAULT_RATING)));
        base += r * s.weight();
      }
      double mult = (p.getSex() == 'M') ? multM : multF;
      scored.add(new ScoredPlayer(p, base, base * mult, pr));
    }
    return scored;
  }

  private List<TeamBucket> createBuckets(int count) {
    List<TeamBucket> teams = new ArrayList<>();
    for (int i = 0; i < count; i++) teams.add(new TeamBucket(i + 1));
    return teams;
  }

  private void balancePositions(List<ScoredPlayer> selected, List<TeamBucket> teams, List<UUID> requiredPositions) {
    List<UUID> selectedPlayerIds = selected.stream().map(sp -> sp.player.getId()).toList();
    List<PlayerPosition> allPositions = playerPositionRepository.findAllByPlayerIdIn(selectedPlayerIds);

    for (UUID posId : requiredPositions) {
      List<ScoredPlayer> eligible = selected.stream()
              .filter(sp -> allPositions.stream().anyMatch(pp -> pp.getPlayerId().equals(sp.player.getId()) && pp.getPositionId().equals(posId)))
              .sorted(Comparator.comparingDouble(ScoredPlayer::getFinalScore).reversed()).collect(Collectors.toList());
      if (eligible.isEmpty()) continue;

      int currentTeam = 0, direction = 1;
      for (ScoredPlayer candidate : eligible) {
        while (currentTeam >= 0 && currentTeam < teams.size()) {
          TeamBucket team = teams.get(currentTeam);
          boolean alreadyHas = team.players.stream().anyMatch(sp -> allPositions.stream().anyMatch(pp -> pp.getPlayerId().equals(sp.player.getId()) && pp.getPositionId().equals(posId)));
          if (!alreadyHas) {
            if (selected.remove(candidate)) team.add(candidate);
            currentTeam += direction;
            break;
          }
          currentTeam += direction;
        }
        if (currentTeam >= teams.size() || currentTeam < 0) { direction *= -1; currentTeam += direction; }
      }
    }
  }

  private TeamBucket chooseTeam(List<TeamBucket> teams, ScoredPlayer sp, int playersPerTeam, boolean sexBalanceEnabled, int maxMaleDiff) {
    List<TeamBucket> candidates = teams.stream().filter(t -> t.players.size() < playersPerTeam).collect(Collectors.toList());
    if (candidates.isEmpty()) throw new IllegalStateException("Nenhum time disponível para alocar jogador");

    if (sexBalanceEnabled) {
      int currentMinM = teams.stream().mapToInt(t -> t.males).min().orElse(0);
      int currentMaxM = teams.stream().mapToInt(t -> t.males).max().orElse(0);
      List<TeamBucket> sexBalanced = new ArrayList<>();
      for (TeamBucket t : candidates) {
        int projectedMale = t.males + (sp.player.getSex() == 'M' ? 1 : 0);
        int projectedMin = Math.min(currentMinM, projectedMale);
        int projectedMax = Math.max(currentMaxM, projectedMale);
        if (projectedMax - projectedMin <= maxMaleDiff) sexBalanced.add(t);
      }
      if (!sexBalanced.isEmpty()) candidates = sexBalanced;
    }

    candidates.sort(Comparator.comparingDouble(t -> t.sum));
    double threshold = candidates.get(0).sum * BALANCE_THRESHOLD;
    List<TeamBucket> bestCandidates = candidates.stream().filter(t -> t.sum <= threshold).collect(Collectors.toList());
    return bestCandidates.get(new Random().nextInt(bestCandidates.size()));
  }

  private TeamGenerationSession persistSession(UUID tenant, UUID createdBy, GenerateTeamsRequest req, int needed) {
    TeamGenerationSession session = new TeamGenerationSession();
    session.setTenantId(tenant);
    session.setMode("DB");
    session.setCreatedBy(createdBy);
    session.setTeamCount(req.teamCount());
    session.setPlayersPerTeam(req.playersPerTeam());
    session.setPlayersCount(needed);
    session.setRulesJson(toJson(mapper.valueToTree(req)));
    return sessionRepository.save(session);
  }

  private void persistTeams(TeamGenerationSession session, List<TeamBucket> buckets) {
    for (TeamBucket tb : buckets) {
      GeneratedTeam gt = new GeneratedTeam();
      gt.setSessionId(session.getId());
      gt.setTeamIndex(tb.teamIndex);
      gt.setName("Time " + tb.teamIndex);
      gt = teamRepository.save(gt);

      for (ScoredPlayer sp : tb.players) {
        GeneratedTeamPlayer gtp = new GeneratedTeamPlayer();
        gtp.setTeamId(gt.getId());
        gtp.setPlayerId(sp.player.getId());
        gtp.setSexAtGeneration(String.valueOf(sp.player.getSex()));
        gtp.setScoreAtGeneration(BigDecimal.valueOf(sp.getFinalScore()));

        ObjectNode snap = mapper.createObjectNode();
        snap.put("baseScore", sp.getBaseScore());
        snap.put("finalScore", sp.getFinalScore());
        ObjectNode ratingsSnap = mapper.createObjectNode();
        for (var entry : sp.currentRatings.entrySet()) ratingsSnap.put(entry.getKey().toString(), entry.getValue());
        snap.set("ratingsUsed", ratingsSnap);
        gtp.setSnapshotJson(toJson(snap));
        teamPlayerRepository.save(gtp);
      }
    }
  }

  private GenerateTeamsResponse buildResponse(UUID sessionId, List<TeamBucket> buckets) {
    var respTeams = buckets.stream().map(tb -> new GenerateTeamsResponse.Team(tb.teamIndex, tb.sum,
            tb.players.stream().map(p -> new GenerateTeamsResponse.PlayerPick(p.player.getId(), p.player.getName(), String.valueOf(p.player.getSex()), p.getFinalScore())).toList())).toList();
    return new GenerateTeamsResponse(sessionId, respTeams);
  }

  private GenerateTeamsResponse getTeamsBySessionInternal(TeamGenerationSession session) {
    List<GeneratedTeam> generatedTeams = teamRepository.findAllBySessionIdOrderByTeamIndexAsc(session.getId());
    List<GenerateTeamsResponse.Team> teams = new ArrayList<>();
    for (GeneratedTeam gt : generatedTeams) {
      List<GeneratedTeamPlayer> players = teamPlayerRepository.findByTeamId(gt.getId());
      List<GenerateTeamsResponse.PlayerPick> picks = players.stream().map(p -> {
        Player player = playerRepository.findById(p.getPlayerId()).orElseThrow();
        return new GenerateTeamsResponse.PlayerPick(player.getId(), player.getName(), String.valueOf(player.getSex()), p.getScoreAtGeneration().doubleValue());
      }).collect(Collectors.toList());
      double sum = players.stream().mapToDouble(p -> p.getScoreAtGeneration().doubleValue()).sum();
      teams.add(new GenerateTeamsResponse.Team(gt.getTeamIndex(), sum, picks));
    }
    return new GenerateTeamsResponse(session.getId(), teams);
  }

  private Map<UUID, Double> computeSkillAverages(List<UUID> playerIds, List<UUID> skillIds, Map<UUID, Map<UUID, Integer>> ratingMap) {
    Map<UUID, List<Integer>> buckets = new HashMap<>();
    for (UUID sid : skillIds) buckets.put(sid, new ArrayList<>());
    for (UUID pid : playerIds) {
      Map<UUID, Integer> pr = ratingMap.getOrDefault(pid, Collections.emptyMap());
      for (UUID sid : skillIds) { Integer r = pr.get(sid); if (r != null) buckets.get(sid).add(r); }
    }
    Map<UUID, Double> avg = new HashMap<>();
    for (UUID sid : skillIds) {
      List<Integer> list = buckets.get(sid);
      avg.put(sid, list.isEmpty() ? DEFAULT_RATING : list.stream().mapToInt(i -> i).average().orElse(DEFAULT_RATING));
    }
    return avg;
  }

  private JsonNode parseRulesJson(String rulesJson) {
    if (rulesJson == null || rulesJson.isBlank()) throw new IllegalStateException("Session rules not found");
    try { return mapper.readTree(rulesJson); } catch (JsonProcessingException e) { throw new IllegalStateException("Failed to parse session rules JSON", e); }
  }

  private String toJson(Object obj) {
    try { return mapper.writeValueAsString(obj); } catch (JsonProcessingException e) { throw new IllegalArgumentException("Failed to serialize JSON", e); }
  }

  // =========================================================================
  // CLASSES INTERNAS
  // =========================================================================

  private static class ScoredPlayer {
    final Player player;
    final double baseScore, finalScore;
    final Map<UUID, Integer> currentRatings;
    ScoredPlayer(Player p, double base, double fin, Map<UUID, Integer> currentRatings) { this.player = p; this.baseScore = base; this.finalScore = fin; this.currentRatings = currentRatings; }
    double getBaseScore() { return baseScore; }
    double getFinalScore() { return finalScore; }
  }

  private static class TeamBucket {
    final int teamIndex;
    final List<ScoredPlayer> players = new ArrayList<>();
    double sum = 0.0;
    int males = 0;
    TeamBucket(int idx) { this.teamIndex = idx; }
    void add(ScoredPlayer sp) { players.add(sp); sum += sp.getFinalScore(); if (sp.player.getSex() == 'M') males++; }
  }
}