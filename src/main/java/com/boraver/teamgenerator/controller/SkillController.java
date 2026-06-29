package com.boraver.teamgenerator.controller;

import com.boraver.teamgenerator.dto.skill.*;
import com.boraver.teamgenerator.entity.Skill;
import com.boraver.teamgenerator.service.SkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/skills")
@RequiredArgsConstructor
public class SkillController {

  private final SkillService skillService;

  @PostMapping
  public ResponseEntity<SkillResponse> create(@Valid @RequestBody CreateSkillRequest request) {
    return ResponseEntity.ok(skillService.create(request));
  }

  @GetMapping
  public ResponseEntity<List<SkillResponse>> listActive() {
    return ResponseEntity.ok(skillService.listActive());
  }

  @GetMapping("/{id}")
  public ResponseEntity<SkillResponse> get(@PathVariable UUID id) {
    return ResponseEntity.ok(skillService.get(id));
  }

  @PutMapping("/{id}")
  public ResponseEntity<SkillResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateSkillRequest request) {
    return ResponseEntity.ok(skillService.update(id, request));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    skillService.delete(id);
    return ResponseEntity.noContent().build();
  }
}