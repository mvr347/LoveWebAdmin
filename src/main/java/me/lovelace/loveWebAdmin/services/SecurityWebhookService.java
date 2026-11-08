package me.lovelace.loveWebAdmin.services;
import me.lovelace.loveWebAdmin.LoveWebAdmin;

/** Compatibility only. The Discord bot is independent; outgoing webhooks were removed. */
@Deprecated
public class SecurityWebhookService {
  public SecurityWebhookService(LoveWebAdmin plugin) {}

  public void notifyLogin(String username, String ip, boolean isNewIp) {}

  public void sendLoginAlert(String username, String ip, String userAgent, boolean isNewIp) {}

  public void notifyBruteForce(String ip, String username, int attempts) {}

  public void sendBruteForceAlert(String ip, String username, int attempts) {}

  public void notifyCandidateRegistered(String username) {}

  public void sendRegistrationAlert(String username, String role) {}

  public void notifyCommandExecuted(String actor, String command) {}

  public void notifyDestructiveAction(String actor, String actionDetails) {}
}
