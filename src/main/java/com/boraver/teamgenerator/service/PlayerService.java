package com.boraver.teamgenerator.service;

import com.boraver.teamgenerator.common.TenantContext;
import com.boraver.teamgenerator.dto.player.*;
import com.boraver.teamgenerator.dto.position.PositionResponse;
import com.boraver.teamgenerator.entity.Player;
import com.boraver.teamgenerator.entity.PlayerSkillRating;
import com.boraver.teamgenerator.entity.Skill;
import com.boraver.teamgenerator.entity.PlayerPosition;
import com.boraver.teamgenerator.repository.PlayerPositionRepository;
import com.boraver.teamgenerator.repository.PlayerRepository;
import com.boraver.teamgenerator.repository.RatingRepository;
import com.boraver.teamgenerator.repository.SkillRepository;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.naming.LimitExceededException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class PlayerService {
  private final PlayerRepository playerRepository;
  private final RatingRepository ratingRepository;
  private final SkillService skillService;;
  private final RatingService ratingService;
  private final PlayerPositionRepository playerPositionRepository;
  private final PositionService positionService;
  private final SkillRepository skillRepository;
  private final PlanLimitService planLimitService;

  private UUID tenantId() {
    return UUID.fromString(Objects.requireNonNull(TenantContext.getTenantId(), "tenant missing"));
  }

  @Transactional
  public PlayerResponse create(CreatePlayerRequest req) {
    UUID tenant = tenantId();
    String name = req.name().trim();
    planLimitService.checkPlayerLimit(tenant);

    if (playerRepository.findByTenantIdAndNameIgnoreCase(tenant, name).isPresent()) {
      throw new IllegalArgumentException("Já existe um jogador com o nome '" + name + "'");
    }

    Player p = new Player();
    p.setTenantId(tenant);
    p.setName(name);
    p.setSex(req.sex().charAt(0));
    p.setEmail(req.email());
    p.setPhone(req.phone());
    p.setBirthDate(req.birthDate());
    p = playerRepository.save(p);

    if (req.positions() != null && !req.positions().isEmpty()) {
      savePlayerPositions(p.getId(), tenant, req.positions());
    }

    return toResponse(p);
  }

  @Transactional
  public PlayerResponse update(UUID id, UpdatePlayerRequest req) {
    UUID tenant = tenantId();
    Player player = playerRepository.findByIdAndTenantId(id, tenant)
            .orElseThrow(() -> new RuntimeException("Jogador não encontrado"));

    if (req.name() != null) player.setName(req.name());
    if (req.sex() != null) player.setSex(req.sex().charAt(0));
    if (req.active() != null) player.setActive(req.active());
    if (req.email() != null) player.setEmail(req.email());
    if (req.phone() != null) player.setPhone(req.phone());
    if (req.birthDate() != null) player.setBirthDate(req.birthDate());

    playerRepository.save(player);

    if (req.positions() != null) {
      playerPositionRepository.deleteAllByPlayerId(id);
      if (!req.positions().isEmpty()) {
        updatePlayerPositions(id, tenant, req.positions());
      }
    }

    return toResponse(player);
  }

  private void savePlayerPositions(UUID playerId, UUID tenant, List<CreatePlayerRequest.PositionAssignment> assignments) {
    List<PlayerPosition> pps = assignments.stream().map(ass -> {
      PlayerPosition pp = new PlayerPosition();
      pp.setPlayerId(playerId);
      pp.setPositionId(ass.positionId());
      pp.setPriority(ass.priority());
      pp.setTenantId(tenant);
      return pp;
    }).collect(Collectors.toList());
    playerPositionRepository.saveAll(pps);
  }

  private void updatePlayerPositions(UUID playerId, UUID tenant, List<UpdatePlayerRequest.PositionAssignment> assignments) {
    List<PlayerPosition> pps = assignments.stream().map(ass -> {
      PlayerPosition pp = new PlayerPosition();
      pp.setPlayerId(playerId);
      pp.setPositionId(ass.positionId());
      pp.setPriority(ass.priority());
      pp.setTenantId(tenant);
      return pp;
    }).collect(Collectors.toList());
    playerPositionRepository.saveAll(pps);
  }

  private List<PlayerResponse.PositionInfo> getPlayerPositions(UUID playerId) {
    List<PlayerPosition> pps = playerPositionRepository.findAllByPlayerIdOrderByPriorityAsc(playerId);
    Map<UUID, String> positionNames = positionService.listActive().stream()
            .collect(Collectors.toMap(PositionResponse::id, PositionResponse::name));
    return pps.stream()
            .map(pp -> new PlayerResponse.PositionInfo(
                    pp.getPositionId(),
                    positionNames.getOrDefault(pp.getPositionId(), "?"),
                    pp.getPriority()))
            .collect(Collectors.toList());
  }

  public List<PlayerResponse> listActive() {
    return listActiveWithRatings();
  }

  public PlayerResponse get(UUID id) {
    Player p = playerRepository.findByIdAndTenantId(id, tenantId()).orElseThrow(() -> new IllegalArgumentException("Player not found"));
    return toResponse(p);
  }

  @Transactional
  public void delete(UUID id) {
    playerRepository.softDelete(id);
  }

  private PlayerResponse toResponse(Player p) {
    Map<UUID, Integer> currentRatings = ratingService.currentRatingsMap(p.getId());

    double overall = currentRatings.values().stream()
      .mapToInt(Integer::intValue)
      .average()
      .orElse(0.0);
    overall = Math.round(overall * 10.0) / 10.0;

    Map<String, Integer> ratingsMap = new LinkedHashMap<>();
    Map<UUID, String> skillNames = skillService.getActiveSkills().stream()
      .collect(Collectors.toMap(Skill::getId, Skill::getName));
    for (Map.Entry<UUID, Integer> entry : currentRatings.entrySet()) {
      String skillName = skillNames.get(entry.getKey());
      if (skillName != null) {
        ratingsMap.put(skillName, entry.getValue());
      }
    }

    return new PlayerResponse(
      p.getId(),
      p.getName(),
      String.valueOf(p.getSex()),
      p.isActive(),
      p.getEmail(),
      p.getPhone(),
      p.getBirthDate(),
      getPlayerPositions(p.getId()),
      ratingsMap,
      overall
    );
  }

  public long countActive(UUID tenantId) {
    return playerRepository.countByTenantIdAndActiveTrue(tenantId);
  }

  public List<InactivePlayerDTO> getInactivePlayers(UUID tenantId) {
    List<Object[]> rows = playerRepository.findInactivePlayersRaw(tenantId.toString());
    return rows.stream().map(row -> {
      String name = (String) row[0];
      LocalDateTime lastParticipation = (row[1] != null) ? (LocalDateTime) row[1] : null;
      return new InactivePlayerDTO(name, lastParticipation);
    }).collect(Collectors.toList());
  }

  public List<PlayerPerformanceDTO> getPerformanceData(UUID tenantId) {
    // 1. Jogadores ativos ordenados
    List<Player> players = playerRepository.findAllByTenantIdAndActiveTrue(tenantId, Sort.by("name"));
    if (players.isEmpty()) return Collections.emptyList();

    List<UUID> playerIds = players.stream().map(Player::getId).collect(Collectors.toList());

    // 2. Skills ativas (cacheável)
    List<Skill> skills = skillService.getActiveSkills();
    Map<UUID, String> skillNames = skills.stream()
            .collect(Collectors.toMap(Skill::getId, Skill::getName));

    // 3. Ratings atuais de todos os jogadores (1 consulta)
    List<PlayerSkillRating> allRatings = ratingRepository.findCurrentRatingsForPlayers(tenantId, playerIds);
    Map<UUID, List<PlayerSkillRating>> ratingsByPlayer = allRatings.stream()
            .collect(Collectors.groupingBy(PlayerSkillRating::getPlayerId));

    // 4. Última data de atualização de cada jogador (1 consulta)
    List<Object[]> lastDates = ratingRepository.findLastRatingDatesForPlayers(tenantId, playerIds);
    Map<UUID, LocalDateTime> lastDateByPlayer = new HashMap<>();
    for (Object[] row : lastDates) {
      UUID pid = (UUID) row[0];
      java.time.OffsetDateTime odt = (java.time.OffsetDateTime) row[1];
      LocalDateTime date = odt != null ? odt.toLocalDateTime() : null;
      lastDateByPlayer.put(pid, date);
    }

    // 5. Processamento em memória
    List<PlayerPerformanceDTO> result = new ArrayList<>();
    for (Player player : players) {
      List<PlayerSkillRating> playerRatings = ratingsByPlayer.getOrDefault(player.getId(), Collections.emptyList());

      double avg = playerRatings.stream()
              .mapToInt(psr -> (int) psr.getRating())
              .average()
              .orElse(0.0);

      String nivel;
      if (avg >= 4.5) nivel = "Elite";
      else if (avg >= 4.0) nivel = "Muito alto";
      else if (avg >= 3.5) nivel = "Alto";
      else if (avg >= 3.0) nivel = "Médio";
      else if (avg >= 2.0) nivel = "A desenvolver";
      else nivel = "Iniciante";

      String bestSkill = null, worstSkill = null;
      int max = Integer.MIN_VALUE, min = Integer.MAX_VALUE;
      Map<String, Integer> skillsMap = new LinkedHashMap<>();

      for (PlayerSkillRating psr : playerRatings) {
        String skillName = skillNames.get(psr.getSkillId());
        if (skillName != null) {
          int rating = (int) psr.getRating();
          skillsMap.put(skillName, rating);
          if (rating > max) { max = rating; bestSkill = skillName; }
          if (rating < min) { min = rating; worstSkill = skillName; }
        }
      }

      LocalDateTime lastUpdate = lastDateByPlayer.get(player.getId());
      String lastUpdateStr = lastUpdate != null
              ? lastUpdate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
              : "—";

      result.add(new PlayerPerformanceDTO(
              player.getId(),
              player.getName(),
              String.valueOf(player.getSex()),
              Math.round(avg * 10.0) / 10.0,
              nivel,
              bestSkill != null ? bestSkill : "N/A",
              worstSkill != null ? worstSkill : "N/A",
              skillsMap,
              lastUpdateStr
      ));
    }
    return result;
  }

  public List<PlayerResponse> listActiveWithRatings() {
    UUID tenant = tenantId();

    // 1. Todos os jogadores ativos com suas posições
    List<Player> players = playerRepository.findAllActiveWithPositions(tenant);
    List<UUID> playerIds = players.stream().map(Player::getId).collect(Collectors.toList());

    // 2. Ratings atuais de todos esses jogadores (uma única consulta)
    List<PlayerSkillRating> allRatings = ratingRepository.findCurrentRatingsForPlayers(tenant, playerIds);

    // 3. Skills ativas (pode ser cacheado se desejar)
    List<Skill> skills = skillRepository.findAllByTenantIdAndActiveTrue(tenant);
    Map<UUID, String> skillNames = skills.stream()
      .collect(Collectors.toMap(Skill::getId, Skill::getName));

    // 4. Agrupa ratings por jogador
    Map<UUID, List<PlayerSkillRating>> ratingsByPlayer = allRatings.stream()
      .collect(Collectors.groupingBy(PlayerSkillRating::getPlayerId));

    // 5. Nomes das posições
    Map<UUID, String> positionNames = positionService.listActive().stream()
      .collect(Collectors.toMap(PositionResponse::id, PositionResponse::name));

    // 6. Monta os DTOs
    return players.stream().map(player -> {
      List<PlayerSkillRating> playerRatings = ratingsByPlayer.getOrDefault(player.getId(), Collections.emptyList());

      // Média geral
      double avg = playerRatings.stream()
        .mapToInt(psr -> (int) psr.getRating())
        .average()
        .orElse(0.0);
      avg = Math.round(avg * 10.0) / 10.0;

      // Mapa de skill -> rating
      Map<String, Integer> ratingsMap = new LinkedHashMap<>();
      for (PlayerSkillRating psr : playerRatings) {
        String skillName = skillNames.get(psr.getSkillId());
        if (skillName != null) {
          ratingsMap.put(skillName, (int) psr.getRating());
        }
      }

      List<PlayerResponse.PositionInfo> positions = player.getPositions().stream()
        .sorted(Comparator.comparingInt(PlayerPosition::getPriority))
        .map(pp -> new PlayerResponse.PositionInfo(
          pp.getPositionId(),
          positionNames.getOrDefault(pp.getPositionId(), "?"),
          pp.getPriority()))
        .collect(Collectors.toList());

      return new PlayerResponse(
        player.getId(),
        player.getName(),
        String.valueOf(player.getSex()),
        player.isActive(),
        player.getEmail(),
        player.getPhone(),
        player.getBirthDate(),
        positions,
        ratingsMap,
        avg
      );
    }).collect(Collectors.toList());
  }
}
