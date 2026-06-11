package com.boraver.teamgenerator.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "subscription")
@Getter
@Setter
public class Subscription {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 36)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  private UUID tenantId;

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "plan_id", nullable = false)
  private Plan plan;

  @Column(nullable = false, length = 20)
  private String status = "ACTIVE"; // ACTIVE, CANCELLED, EXPIRED

  @Column(name = "start_date", nullable = false)
  private LocalDate startDate;

  @Column(name = "end_date")
  private LocalDate endDate;

  @Column(name = "stripe_subscription_id", length = 255)
  private String stripeSubscriptionId;

  /**
   * Verifica se a assinatura está ativa
   */
  public boolean isActive() {
    return "ACTIVE".equals(status) &&
            (endDate == null || !endDate.isBefore(LocalDate.now()));
  }

  /**
   * Cancela a assinatura
   */
  public void cancel() {
    this.status = "CANCELLED";
    this.endDate = LocalDate.now();
  }

  /**
   * Expira a assinatura (chamado por jobs agendados)
   */
  public void expire() {
    this.status = "EXPIRED";
  }
}