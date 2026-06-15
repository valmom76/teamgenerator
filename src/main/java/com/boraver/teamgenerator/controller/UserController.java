package com.boraver.teamgenerator.controller;

import com.boraver.teamgenerator.common.TenantContext;
import com.boraver.teamgenerator.dto.subscription.UpdateCpfCnpjRequestDTO;
import com.boraver.teamgenerator.dto.subscription.UserProfileDTO;
import com.boraver.teamgenerator.entity.AppUser;
import com.boraver.teamgenerator.repository.AppUserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

  private final AppUserRepository appUserRepository;

  @PutMapping("/cpf-cnpj")
  public ResponseEntity<Void> updateCpfCnpj(@RequestBody @Valid UpdateCpfCnpjRequestDTO request) {
    UUID tenantId = UUID.fromString(TenantContext.getTenantId());
    AppUser user = appUserRepository.findFirstByTenantIdAndRole(tenantId, "ADMIN")
      .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));

    user.setCpfCnpj(request.cpfCnpj());
    appUserRepository.save(user);
    return ResponseEntity.ok().build();
  }

  @GetMapping("/profile")
  public ResponseEntity<UserProfileDTO> getProfile() {
    UUID tenantId = UUID.fromString(TenantContext.getTenantId());
    AppUser user = appUserRepository.findFirstByTenantIdAndRole(tenantId, "ADMIN")
      .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));
    return ResponseEntity.ok(new UserProfileDTO(user.getName(), user.getEmail(), user.getCpfCnpj()));
  }
}