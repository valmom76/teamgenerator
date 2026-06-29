package com.boraver.teamgenerator.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name="team_generation_session")
@Getter
@Setter
public class TeamGenerationSession {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 36)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  private UUID tenantId;

  @Column(nullable=false, length=10)
  private String mode;

  @Column(name="created_by")
  @JdbcTypeCode(SqlTypes.CHAR)
  private UUID createdBy;

  @Column(name="created_at", nullable=false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  @Column(name="team_count", nullable=false)
  private int teamCount;

  @Column(name="players_per_team", nullable=false)
  private int playersPerTeam;

  @Column(name="players_count", nullable=false)
  private int playersCount;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name="rules_json", nullable=false, columnDefinition="json")
  private String rulesJson;

  @Column(name = "play_date")
  private OffsetDateTime playDate;

  @ManyToOne
  @JoinColumn(name = "session_id")
  private GameSession gameSession;
}