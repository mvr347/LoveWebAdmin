package me.lovelace.loveWebAdmin.web;

/** Interactive confirmation applies to account/access changes, not daily moderation. */
public final class SensitiveActions {
  private SensitiveActions() {}

  public static boolean requiresSecondFactor(String path) {
    if (path == null) return false;
    return path.matches("/api/(roles|admins|developer)(/.*)?")
        || path.matches("/api/server/(config|maintenance|ops)/?")
        || path.matches("/api/me/backup-codes/regenerate/?")
        || path.matches("/api/me/sessions/other(/terminate)?/?")
        || path.matches("/api/auth/sessions/[^/]+/terminate/?");
    // /api/me/password validates both the current password and TOTP in its own form.
  }
}
