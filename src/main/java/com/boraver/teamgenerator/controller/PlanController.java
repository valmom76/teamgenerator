package com.boraver.teamgenerator.controller;

import com.boraver.teamgenerator.entity.Plan;
import com.boraver.teamgenerator.repository.PlanRepository;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/plans")
@AllArgsConstructor
public class PlanController {

  private final PlanRepository planRepository;


  @GetMapping
  public ResponseEntity<List<Plan>> listPlans() {
    return ResponseEntity.ok(planRepository.findByActiveTrue());
  }
}
