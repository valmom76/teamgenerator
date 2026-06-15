package com.boraver.teamgenerator.repository;

import com.boraver.teamgenerator.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

  Optional<Subscription> findByTenantIdAndStatus(UUID tenantId, Subscription.SubscriptionStatus status);

  boolean existsByTenantIdAndStatus(UUID tenantId, Subscription.SubscriptionStatus status);

  Subscription findByAsaasSubscriptionId(String asaasSubscriptionId);

  Optional<Subscription> findFirstByTenantIdAndStatusNotOrderByStartDateDesc(
          UUID tenantId,
          Subscription.SubscriptionStatus status
  );
}