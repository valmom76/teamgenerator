package com.boraver.teamgenerator.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "plan")
@Getter
@Setter
public class Plan {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 36)
  private UUID id;

  @Column(nullable = false, length = 120)
  private String name;

  @Column(length = 255)
  private String description;

  @Column(nullable = false, precision = 10, scale = 2)
  private BigDecimal price = BigDecimal.ZERO;

  @Column(name = "max_players", nullable = false)
  private int maxPlayers = -1;

  @Column(name = "max_championships", nullable = false)
  private int maxChampionships = -1;

  @Column(name = "features", columnDefinition = "json", nullable = false)
  private String features;

  @Column(nullable = false)
  private boolean active = true;

  private static final ObjectMapper objectMapper = new ObjectMapper();
  /**
  * Retorna a lista de features como List<String>
  */
  public List<String> getFeatureList() {
    if (features == null || features.isBlank()) {
      return new ArrayList<>();
    }
    try {
      return objectMapper.readValue(features, new TypeReference<List<String>>() {});
    } catch (Exception e) {
      return new ArrayList<>();
    }
  }

  /**
   * Define a lista de features a partir de uma List<String>
   */
  public void setFeatureList(List<String> featureList) {
    try {
      this.features = objectMapper.writeValueAsString(featureList);
    } catch (Exception e) {
      this.features = "[]";
    }
  }

  /**
   * Verifica se o plano possui uma determinada feature
   */
  public boolean hasFeature(String feature) {
    return getFeatureList().contains(feature);
  }
}