package me.lovelace.loveWebAdmin.web;

import me.lovelace.loveWebAdmin.models.WebSession;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for session lifecycle; failures must not be acknowledged as successful
 * writes.
 */
public interface SessionRepository {
    void saveSession(WebSession session);

    Optional<WebSession> getSession(String token);

    void deleteSession(String token);

    void deleteExpiredSessions();

    List<WebSession> getAllActiveSessions();

    List<WebSession> getSessionsForAdmin(int adminId);

    void deleteSessionsForAdmin(int adminId);

    void deleteOtherSessions(int adminId, String currentToken);

    void deleteAllSessionsExcept(String currentToken);

    void updateSessionLastUsed(String token, long now);
}
