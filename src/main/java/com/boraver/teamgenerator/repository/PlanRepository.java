package com.boraver.teamgenerator.repository;

import com.boraver.teamgenerator.entity.Plan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanRepository extends JpaRepository<Plan, UUID> {

  Optional<Plan> findByName(String name);

  List<Plan> findByActiveTrue();

  @Query("SELECT p FROM Plan p WHERE p.active = true AND p.name = :name")
  Optional<Plan> findActiveByName(@Param("name") String name);

  boolean existsByName(String name);
}