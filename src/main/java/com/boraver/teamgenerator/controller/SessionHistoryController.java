package com.boraver.teamgenerator.controller;

import com.boraver.teamgenerator.service.SessionHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/session-history")
@RequiredArgsConstructor
public class SessionHistoryController {

  private final SessionHistoryService sessionHistoryService;

  @GetMapping
  public ResponseEntity<List<SessionHistoryService.SessionSummaryDTO>> listSessions() {
    return ResponseEntity.ok(sessionHistoryService.getSessions());
  }

  @GetMapping("/{sessionId}")
  public ResponseEntity<SessionHistoryService.SessionDetailDTO> getSessionDetail(@PathVariable UUID sessionId) {
    return ResponseEntity.ok(sessionHistoryService.getSessionDetail(sessionId));
  }
}