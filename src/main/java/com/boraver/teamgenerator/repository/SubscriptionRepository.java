package com.boraver.teamgenerator.repository;

import com.boraver.teamgenerator.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

  @Query("SELECT s FROM Subscription s JOIN FETCH s.plan WHERE s.tenantId = :tenantId AND s.status = :status")
  Optional<Subscription> findByTenantIdAndStatus(@Param("tenantId") UUID tenantId, @Param("status") String status);

  List<Subscription> findAllByTenantId(UUID tenantId);
  Optional<Subscription> findByTenantIdAndStatusNot(UUID id, Subscription.SubscriptionStatus tenantId);

  @Query("SELECT s FROM Subscription s JOIN FETCH s.plan WHERE s.tenantId = :tenantId AND s.status = 'ACTIVE'")
  Optional<Subscription> findActiveByTenantId(@Param("tenantId") UUID tenantId);

  boolean existsByTenantIdAndStatus(UUID tenantId, Subscription.SubscriptionStatus status);

  @Query("SELECT COUNT(s) FROM Subscription s WHERE s.status = 'ACTIVE' AND s.endDate IS NOT NULL AND s.endDate < :date")
  long countExpiredSubscriptions(@Param("date") LocalDate date);

  Optional<Subscription> findFirstByTenantIdAndStatusNotOrderByStartDateDesc(
    UUID tenantId, Subscription.SubscriptionStatus subscriptionStatus);

  Optional<Subscription> findByTenantIdAndStatus(UUID tenantId, Subscription.SubscriptionStatus status);
  Subscription findByAsaasSubscriptionId(String asaasSubscriptionId);
}