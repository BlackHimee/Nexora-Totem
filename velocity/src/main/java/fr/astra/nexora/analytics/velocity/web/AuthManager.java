package fr.astra.nexora.analytics.velocity.web;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Authentification du dashboard.
 *
 * <p>Un admin tape {@code /analytics} en jeu : un jeton aléatoire à usage unique, valable quelques
 * minutes, lui est envoyé sous forme de lien. En ouvrant ce lien, le jeton est échangé contre un
 * cookie de session (HttpOnly, SameSite=Strict). Aucun mot de passe n'est stocké ni transmis.
 */
public final class AuthManager {
  public record Session(String id, String admin, long expiresAt) {}

  private record Token(String admin, long expiresAt) {}

  private final SecureRandom random = new SecureRandom();
  private final Map<String, Token> tokens = new ConcurrentHashMap<>();
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final long tokenTtl;
  private final long sessionTtl;

  public AuthManager(int tokenMinutes, int sessionHours) {
    this.tokenTtl = TimeUnit.MINUTES.toMillis(tokenMinutes);
    this.sessionTtl = TimeUnit.HOURS.toMillis(sessionHours);
  }

  public String createToken(String admin) {
    purge();
    String token = randomId();
    tokens.put(token, new Token(admin, System.currentTimeMillis() + tokenTtl));
    return token;
  }

  /** Consomme un jeton (usage unique) et ouvre une session, ou renvoie {@code null}. */
  public Session exchange(String token) {
    if (token == null) return null;
    Token t = tokens.remove(token);
    if (t == null || t.expiresAt < System.currentTimeMillis()) return null;
    Session s = new Session(randomId(), t.admin, System.currentTimeMillis() + sessionTtl);
    sessions.put(s.id(), s);
    return s;
  }

  public Session session(String id) {
    if (id == null) return null;
    Session s = sessions.get(id);
    if (s == null) return null;
    if (s.expiresAt < System.currentTimeMillis()) {
      sessions.remove(id);
      return null;
    }
    return s;
  }

  public void revoke(String id) {
    if (id != null) sessions.remove(id);
  }

  /** Ferme toutes les sessions ouvertes et invalide les liens en attente. */
  public int revokeAll() {
    int n = sessions.size();
    sessions.clear();
    tokens.clear();
    return n;
  }

  public long sessionTtlMs() {
    return sessionTtl;
  }

  private void purge() {
    long now = System.currentTimeMillis();
    tokens.values().removeIf(t -> t.expiresAt < now);
    sessions.values().removeIf(s -> s.expiresAt < now);
  }

  private String randomId() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
