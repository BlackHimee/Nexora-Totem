package fr.astra.nexora.analytics.velocity.tracker;

import com.google.gson.Gson;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import fr.astra.nexora.analytics.velocity.config.AnalyticsConfig;
import fr.astra.nexora.analytics.velocity.storage.Database;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Échantillonne le nombre de joueurs en ligne (par serveur) et surveille la disponibilité des
 * serveurs du réseau. Une coupure ou un retour en ligne génère un incident.
 */
public final class ServerMonitor {
  /** État de santé d'un serveur, alimenté par les pings du proxy et les données du plugin Paper. */
  public static final class Health {
    public volatile boolean online;
    public volatile boolean observed;
    public volatile long since = System.currentTimeMillis();
    public volatile long lastHeartbeat;
    public volatile double tps = -1;
    public volatile double mspt = -1;
    public volatile long usedMemory;
    public volatile long maxMemory;
    public volatile int loadedChunks;
    public volatile int entities;
    public volatile String pluginVersion;
    public volatile String serverVersion;
    public volatile String economyStatus;
    public volatile String islandsStatus;
  }

  private final ProxyServer proxy;
  private final Database db;
  private final AnalyticsConfig config;
  private final PlayerTracker players;
  private final Gson gson = new Gson();
  private final Map<String, Health> health = new ConcurrentHashMap<>();
  private long lastPurge;

  public ServerMonitor(ProxyServer proxy, Database db, AnalyticsConfig config, PlayerTracker players) {
    this.proxy = proxy;
    this.db = db;
    this.config = config;
    this.players = players;
  }

  public void start(Object plugin) {
    proxy
        .getScheduler()
        .buildTask(plugin, this::sample)
        .delay(config.sampleSeconds(), TimeUnit.SECONDS)
        .repeat(config.sampleSeconds(), TimeUnit.SECONDS)
        .schedule();
    proxy
        .getScheduler()
        .buildTask(plugin, this::pingAll)
        .delay(5, TimeUnit.SECONDS)
        .repeat(config.pingSeconds(), TimeUnit.SECONDS)
        .schedule();
  }

  /**
   * Nom du serveur tel que déclaré dans velocity.toml si {@code name} n'en diffère que par la casse
   * (« Skyblock » / « skyblock »), sinon {@code name} inchangé.
   */
  public String canonicalName(String name) {
    for (RegisteredServer server : proxy.getAllServers()) {
      String registered = server.getServerInfo().getName();
      if (registered.equalsIgnoreCase(name)) return registered;
    }
    return name;
  }

  public Health health(String server) {
    return health.computeIfAbsent(server, s -> new Health());
  }

  public Map<String, Health> allHealth() {
    return health;
  }

  private void sample() {
    players.flushAll();
    long now = System.currentTimeMillis();
    int online = proxy.getPlayerCount();
    Map<String, Integer> perServer = new LinkedHashMap<>();
    for (RegisteredServer server : proxy.getAllServers()) {
      perServer.put(server.getServerInfo().getName(), server.getPlayersConnected().size());
    }
    String json = gson.toJson(perServer);
    db.write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("INSERT OR REPLACE INTO samples (ts, online, servers) VALUES (?, ?, ?)")) {
            ps.setLong(1, now);
            ps.setInt(2, online);
            ps.setString(3, json);
            ps.executeUpdate();
          }
        });
    if (now - lastPurge > TimeUnit.HOURS.toMillis(6)) {
      lastPurge = now;
      long sampleLimit = now - TimeUnit.DAYS.toMillis(config.sampleRetentionDays());
      long incidentLimit = now - TimeUnit.DAYS.toMillis(config.incidentRetentionDays());
      db.write(
          c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM samples WHERE ts < ?")) {
              ps.setLong(1, sampleLimit);
              ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM incidents WHERE ts < ?")) {
              ps.setLong(1, incidentLimit);
              ps.executeUpdate();
            }
          });
    }
  }

  private void pingAll() {
    for (RegisteredServer server : proxy.getAllServers()) {
      String name = server.getServerInfo().getName();
      server
          .ping()
          .orTimeout(5, TimeUnit.SECONDS)
          .whenComplete((ping, error) -> onPingResult(name, error == null && ping != null));
    }
  }

  private void onPingResult(String server, boolean reachable) {
    Health h = health(server);
    long now = System.currentTimeMillis();
    if (!h.observed) {
      h.observed = true;
      h.online = reachable;
      h.since = now;
      return;
    }
    if (h.online == reachable) return;
    long duration = now - h.since;
    h.online = reachable;
    h.since = now;
    if (reachable) {
      recordIncident(
          server,
          "UP",
          "Serveur de nouveau en ligne",
          "Indisponible pendant " + formatDuration(duration));
    } else {
      recordIncident(server, "DOWN", "Serveur injoignable depuis le proxy", null);
    }
  }

  public void recordIncident(String server, String type, String message, String details) {
    long now = System.currentTimeMillis();
    String msg = truncate(message, 500);
    String det = details == null ? null : truncate(details, 6000);
    db.write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO incidents (ts, server, type, message, details) VALUES (?, ?, ?, ?, ?)")) {
            ps.setLong(1, now);
            ps.setString(2, server);
            ps.setString(3, type);
            ps.setString(4, msg);
            ps.setString(5, det);
            ps.executeUpdate();
          }
        });
  }

  static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max) + "…";
  }

  static String formatDuration(long ms) {
    long s = ms / 1000;
    if (s < 60) return s + " s";
    long m = s / 60;
    if (m < 60) return m + " min";
    long h = m / 60;
    return h + " h " + (m % 60) + " min";
  }
}
