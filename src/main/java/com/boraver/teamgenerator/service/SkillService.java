package com.boraver.teamgenerator.service;

import com.boraver.teamgenerator.common.TenantContext;
import com.boraver.teamgenerator.dto.skill.*;
import com.boraver.teamgenerator.entity.Skill;
import com.boraver.teamgenerator.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class SkillService {

  private final SkillRepository skillRepository;

  private UUID tenantId() {
    return UUID.fromString(Objects.requireNonNull(TenantContext.getTenantId(), "tenant missing"));
  }

  @Transactional
  public SkillResponse create(CreateSkillRequest req) {
    UUID tenant = tenantId();
    String name = req.name().trim();

    if (skillRepository.findByTenantIdAndNameIgnoreCase(tenant, name).isPresent()) {
      throw new IllegalArgumentException("Já existe uma skill com o nome '" + name + "'");
    }

    Skill s = new Skill();
    s.setTenantId(tenant);
    s.setName(name);
    s = skillRepository.save(s);
    return toResponse(s);
  }

  public List<SkillResponse> listActive() {
    return skillRepository.findAllByTenantIdAndActiveTrue(tenantId()).stream().map(this::toResponse).toList();
  }

  public SkillResponse get(UUID id) {
    Skill s = skillRepository.findByIdAndTenantId(id, tenantId())
            .orElseThrow(() -> new IllegalArgumentException("Skill not found"));
    return toResponse(s);
  }

  @Transactional
  public SkillResponse update(UUID id, UpdateSkillRequest req) {
    Skill s = skillRepository.findByIdAndTenantId(id, tenantId())
            .orElseThrow(() -> new IllegalArgumentException("Skill not found"));

    if (req.name() != null && !req.name().isBlank()) {
      s.setName(req.name().trim());
    }
    if (req.active() != null) {
      s.setActive(req.active());
    }

    return toResponse(skillRepository.save(s));
  }

  @Transactional
  public void delete(UUID id) {
    skillRepository.softDelete(id);
  }

  public long countActive(UUID tenantId) {
    return skillRepository.countByTenantIdAndActiveTrue(tenantId);
  }

  public List<Skill> getActiveSkills() {
    UUID tenant = tenantId();
    return skillRepository.findAllByTenantIdAndActiveTrue(tenant);
  }

  private SkillResponse toResponse(Skill s) {
    return new SkillResponse(s.getId(), s.getName(), s.isActive());
  }
}