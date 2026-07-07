package com.boraver.teamgenerator.service;

import com.boraver.teamgenerator.entity.AppUser;
import com.boraver.teamgenerator.entity.PasswordResetToken;
import com.boraver.teamgenerator.repository.AppUserRepository;
import com.boraver.teamgenerator.repository.PasswordResetTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

  private final AppUserRepository appUserRepository;
  private final PasswordResetTokenRepository tokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final JavaMailSender mailSender;

  @Value("${app.frontend-url:http://localhost:5173}")
  private String frontendUrl;

  @Transactional
  public void requestPasswordReset(String email) {
    List<AppUser> users = appUserRepository.findAllByEmail(email);

    if (users.isEmpty()) {
      return;
    }

    for (AppUser user : users) {
      String token = UUID.randomUUID().toString();

      PasswordResetToken resetToken = new PasswordResetToken();
      resetToken.setUserId(user.getId());
      resetToken.setToken(token);
      resetToken.setExpiryDate(LocalDateTime.now().plusHours(1));
      tokenRepository.save(resetToken);

      String resetLink = frontendUrl + "/reset-password?token=" + token;
      SimpleMailMessage message = new SimpleMailMessage();
      message.setFrom("noreply@rando.esp.br");
      message.setTo(user.getEmail());
      message.setSubject("R4NDO - Recuperação de Senha");
      message.setText("Olá " + user.getName() + ",\n\n" +
              "Você solicitou a recuperação de senha no grupo " + user.getTenantId() + ".\n" +
              "Clique no link abaixo para definir uma nova senha:\n\n" +
              resetLink + "\n\n" +
              "Este link expira em 1 hora.\n\n" +
              "Se você não solicitou esta alteração, ignore este e-mail.");
      mailSender.send(message);
    }
  }

  @Transactional
  public void resetPassword(String token, String newPassword) {
    PasswordResetToken resetToken = tokenRepository.findByTokenAndUsedFalse(token)
            .orElseThrow(() -> new RuntimeException("Token inválido ou já utilizado"));

    if (resetToken.getExpiryDate().isBefore(LocalDateTime.now())) {
      throw new RuntimeException("Token expirado");
    }

    AppUser user = appUserRepository.findById(resetToken.getUserId())
            .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));

    user.setPasswordHash(passwordEncoder.encode(newPassword));
    appUserRepository.save(user);

    resetToken.setUsed(true);
    tokenRepository.save(resetToken);
  }
}