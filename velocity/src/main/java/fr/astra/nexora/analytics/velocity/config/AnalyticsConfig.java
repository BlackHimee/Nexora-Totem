package fr.astra.nexora.analytics.velocity.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Properties;

/**
 * Configuration du plugin, stockée dans {@code plugins/nexora-analytics/config.properties}.
 *
 * <p>Au premier démarrage, le fichier par défaut est copié depuis le jar et un secret d'ingestion
 * aléatoire est généré : c'est ce secret que les serveurs Paper doivent renseigner pour pouvoir
 * envoyer leurs données au proxy.
 */
public final class AnalyticsConfig {
  private static final String SECRET_PLACEHOLDER = "CHANGE-ME";

  private final Properties props = new Properties();

  public static AnalyticsConfig load(Path dataDir) throws IOException {
    Files.createDirectories(dataDir);
    Path file = dataDir.resolve("config.properties");
    if (Files.notExists(file)) {
      try (InputStream in = AnalyticsConfig.class.getResourceAsStream("/config.properties")) {
        if (in == null) throw new IOException("config.properties absent du jar");
        String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        byte[] secret = new byte[24];
        new SecureRandom().nextBytes(secret);
        content = content.replace(SECRET_PLACEHOLDER, HexFormat.of().formatHex(secret));
        Files.writeString(file, content, StandardCharsets.UTF_8);
      }
    }
    AnalyticsConfig config = new AnalyticsConfig();
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      config.props.load(reader);
    }
    if (SECRET_PLACEHOLDER.equals(config.ingestSecret()) || config.ingestSecret().length() < 16) {
      byte[] secret = new byte[24];
      new SecureRandom().nextBytes(secret);
      config.props.setProperty("ingest-secret", HexFormat.of().formatHex(secret));
      try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
        config.props.store(writer, "Nexora Analytics - secret d'ingestion regenere (trop court)");
      }
    }
    return config;
  }

  private String str(String key, String def) {
    String v = props.getProperty(key);
    return v == null || v.isBlank() ? def : v.trim();
  }

  private int integer(String key, int def) {
    try {
      return Integer.parseInt(str(key, String.valueOf(def)));
    } catch (NumberFormatException e) {
      return def;
    }
  }

  public String bindAddress() {
    return str("bind-address", "0.0.0.0");
  }

  public int port() {
    return integer("port", 8765);
  }

  /** URL publique du dashboard, utilisée pour construire le lien envoyé en jeu. */
  public String publicUrl() {
    String url = str("public-url", "http://127.0.0.1:" + port());
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  public String ingestSecret() {
    return str("ingest-secret", SECRET_PLACEHOLDER);
  }

  public ZoneId zone() {
    try {
      return ZoneId.of(str("timezone", "Europe/Paris"));
    } catch (Exception e) {
      return ZoneId.systemDefault();
    }
  }

  public int sessionHours() {
    return Math.max(1, integer("session-hours", 12));
  }

  public int tokenMinutes() {
    return Math.max(1, integer("token-minutes", 5));
  }

  public int sampleSeconds() {
    return Math.max(15, integer("sample-interval-seconds", 60));
  }

  public int pingSeconds() {
    return Math.max(10, integer("ping-interval-seconds", 30));
  }

  public int sampleRetentionDays() {
    return Math.max(7, integer("sample-retention-days", 120));
  }

  public int incidentRetentionDays() {
    return Math.max(7, integer("incident-retention-days", 60));
  }

  public int inactiveDays() {
    return Math.max(2, integer("inactive-after-days", 7));
  }
}
