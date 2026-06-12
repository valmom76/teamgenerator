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

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "plan_id", nullable = false)
  private Plan plan;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private SubscriptionStatus status = SubscriptionStatus.PENDING;

  @Column(name = "start_date", nullable = false)
  private LocalDate startDate;

  @Column(name = "end_date")
  private LocalDate endDate;

  @Column(name = "asaas_subscription_id", length = 255)
  private String asaasSubscriptionId;

  @Column(name = "asaas_customer_id", length = 255)
  private String asaasCustomerId;

  @Column(name = "stripe_subscription_id", length = 255)
  private String stripeSubscriptionId;

  public enum SubscriptionStatus {
    PENDING, ACTIVE, SUSPENDED, CANCELLED
  }
}