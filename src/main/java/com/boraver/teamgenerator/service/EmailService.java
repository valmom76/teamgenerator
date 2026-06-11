package com.boraver.teamgenerator.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

  private final JavaMailSender mailSender;

  @Value("${app.email.from:contato@rando.esp.br}")
  private String fromEmail;

  @Value("${app.email.verification-url:https://rando.esp.com/verify}")
  private String verificationUrl;

  public EmailService(JavaMailSender mailSender) {
    this.mailSender = mailSender;
  }

  /**
   * Envia e-mail de verificação de conta
   */
  public void sendVerificationEmail(String to, String token) {
    String subject = "Verificação de E-mail - R4NDO";
    String body = buildVerificationEmailBody(token);

    sendEmail(to, subject, body);
  }

  /**
   * Envia e-mail de boas-vindas
   */
  public void sendWelcomeEmail(String to, String userName) {
    String subject = "Bem-vindo ao R4NDO!";
    String body = buildWelcomeEmailBody(userName);

    sendEmail(to, subject, body);
  }

  /**
   * Envia e-mail de recuperação de senha (futuro)
   */
  public void sendPasswordResetEmail(String to, String resetToken) {
    String subject = "Recuperação de Senha - R4NDO";
    String body = buildPasswordResetEmailBody(resetToken);

    sendEmail(to, subject, body);
  }

  /**
   * Envia e-mail genérico
   */
  public void sendEmail(String to, String subject, String body) {
    try {
      SimpleMailMessage message = new SimpleMailMessage();
      message.setFrom(fromEmail);
      message.setTo(to);
      message.setSubject(subject);
      message.setText(body);
      mailSender.send(message);
    } catch (Exception e) {
      // Log do erro, mas não interrompe o fluxo principal
      System.err.println("Erro ao enviar e-mail para " + to + ": " + e.getMessage());
      throw new RuntimeException("Falha ao enviar e-mail", e);
    }
  }

  /**
   * Constrói o corpo do e-mail de verificação
   */
  private String buildVerificationEmailBody(String token) {
    String verificationLink = verificationUrl + "?token=" + token;

    return "Olá!\n\n" +
            "Obrigado por se cadastrar no R4NDO, a plataforma de gestão de grupos de vôlei.\n\n" +
            "Para ativar sua conta, clique no link abaixo:\n" +
            verificationLink + "\n\n" +
            "Este link é válido por 24 horas.\n\n" +
            "Se você não solicitou este cadastro, ignore este e-mail.\n\n" +
            "Atenciosamente,\n" +
            "Equipe R4NDO\n" +
            "https://rando.esp.br";
  }

  /**
   * Constrói o corpo do e-mail de boas-vindas
   */
  private String buildWelcomeEmailBody(String userName) {
    return "Olá, " + userName + "!\n\n" +
            "Sua conta no R4NDO foi ativada com sucesso!\n\n" +
            "Agora você pode:\n" +
            "• Cadastrar atletas e avaliar habilidades\n" +
            "• Gerar times balanceados automaticamente\n" +
            "• Criar e gerenciar campeonatos\n" +
            "• Acompanhar o desempenho dos seus atletas\n\n" +
            "Acesse sua conta em: https://rando.esp.br\n\n" +
            "Bons jogos!\n" +
            "Equipe R4NDO";
  }

  /**
   * Constrói o corpo do e-mail de recuperação de senha
   */
  private String buildPasswordResetEmailBody(String resetToken) {
    String resetLink = "https://rando.esp.br/reset-password?token=" + resetToken;

    return "Olá!\n\n" +
            "Recebemos uma solicitação de recuperação de senha para sua conta.\n\n" +
            "Para redefinir sua senha, clique no link abaixo:\n" +
            resetLink + "\n\n" +
            "Este link é válido por 1 hora.\n\n" +
            "Se você não solicitou a recuperação de senha, ignore este e-mail.\n\n" +
            "Atenciosamente,\n" +
            "Equipe R4NDO";
  }
}