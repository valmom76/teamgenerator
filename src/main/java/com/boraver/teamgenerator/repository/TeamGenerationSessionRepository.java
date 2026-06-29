package com.boraver.teamgenerator.repository;

import com.boraver.teamgenerator.entity.GameSession;
import com.boraver.teamgenerator.entity.TeamGenerationSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TeamGenerationSessionRepository extends JpaRepository<TeamGenerationSession, UUID> {

  List<TeamGenerationSession> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

  Optional<TeamGenerationSession> findTopByTenantIdOrderByCreatedAtDesc(UUID tenantId);

  Optional<TeamGenerationSession> findByGameSession(GameSession gameSession);

  @Query("SELECT s FROM TeamGenerationSession s WHERE s.gameSession.id = :gameSessionId")
  Optional<TeamGenerationSession> findByGameSessionId(@Param("gameSessionId") UUID gameSessionId);
}