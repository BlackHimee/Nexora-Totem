package fr.astra.nexora.analytics.velocity.tracker;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerClientBrandEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import fr.astra.nexora.analytics.velocity.storage.Database;
import fr.astra.nexora.analytics.velocity.storage.Days;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Suit les connexions au réseau : sessions, temps de jeu (par joueur, par jour et par serveur),
 * version de Minecraft, client utilisé et pic de joueurs simultanés.
 *
 * <p>Le temps de jeu est crédité par petites tranches (à chaque échantillonnage et à la
 * déconnexion) : une session à cheval sur minuit est ainsi correctement répartie sur les deux
 * journées, et un arrêt brutal du proxy ne fait perdre qu'une minute de données au maximum.
 */
public final class PlayerTracker {
  private final ProxyServer proxy;
  private final Database db;
  private final Days days;

  /** Dernier instant (ms) jusqu'auquel le temps de jeu de chaque joueur en ligne a été crédité. */
  private final Map<UUID, Long> lastFlush = new ConcurrentHashMap<>();

  /** Serveur sur lequel le joueur jouait depuis {@link #lastFlush}. */
  private final Map<UUID, String> currentServer = new ConcurrentHashMap<>();

  private volatile int peakAll;
  private volatile long peakAllTs;
  private volatile int peakToday;
  private volatile String peakDay = "";

  public PlayerTracker(ProxyServer proxy, Database db, Days days) {
    this.proxy = proxy;
    this.db = db;
    this.days = days;
  }

  /** Clôture les sessions restées ouvertes suite à un arrêt brutal et charge les records. */
  public void init() throws Exception {
    db.write(
        c -> {
          try (Statement st = c.createStatement()) {
            st.executeUpdate(
                "UPDATE sessions SET end = MAX(start, COALESCE((SELECT last_seen FROM players p"
                    + " WHERE p.uuid = sessions.uuid), start)) WHERE end IS NULL");
          }
        });
    db.read(
            c -> {
              try (Statement st = c.createStatement();
                  ResultSet rs =
                      st.executeQuery("SELECT day, peak, ts FROM peaks_daily ORDER BY peak DESC LIMIT 1")) {
                if (rs.next()) {
                  peakAll = rs.getInt("peak");
                  peakAllTs = rs.getLong("ts");
                }
              }
              try (PreparedStatement ps = c.prepareStatement("SELECT peak FROM peaks_daily WHERE day = ?")) {
                ps.setString(1, days.todayKey());
                try (ResultSet rs = ps.executeQuery()) {
                  peakDay = days.todayKey();
                  peakToday = rs.next() ? rs.getInt(1) : 0;
                }
              }
              return null;
            })
        .get();
  }

  @Subscribe(order = PostOrder.LAST)
  public void onLogin(PostLoginEvent event) {
    Player player = event.getPlayer();
    long now = System.currentTimeMillis();
    UUID uuid = player.getUniqueId();
    String name = player.getUsername();
    String version = player.getProtocolVersion().getMostRecentSupportedVersion();
    String day = days.day(now);
    lastFlush.put(uuid, now);
    currentServer.remove(uuid);

    db.write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO players (uuid, name, first_seen, last_seen, sessions, version)"
                      + " VALUES (?, ?, ?, ?, 1, ?)"
                      + " ON CONFLICT(uuid) DO UPDATE SET name = excluded.name,"
                      + " last_seen = excluded.last_seen, sessions = sessions + 1,"
                      + " version = excluded.version")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.setString(5, version);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement("INSERT INTO sessions (uuid, start, version) VALUES (?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, now);
            ps.setString(3, version);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT OR IGNORE INTO daily_activity (uuid, day, playtime_ms) VALUES (?, ?, 0)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, day);
            ps.executeUpdate();
          }
        });

    updatePeak(proxy.getPlayerCount(), now);
  }

  @Subscribe
  public void onBrand(PlayerClientBrandEvent event) {
    String brand = event.getBrand();
    if (brand == null || brand.isBlank()) return;
    String cleaned = brand.length() > 48 ? brand.substring(0, 48) : brand;
    String uuid = event.getPlayer().getUniqueId().toString();
    db.write(
        c -> {
          try (PreparedStatement ps = c.prepareStatement("UPDATE players SET brand = ? WHERE uuid = ?")) {
            ps.setString(1, cleaned);
            ps.setString(2, uuid);
            ps.executeUpdate();
          }
        });
  }

  @Subscribe
  public void onServerConnected(ServerConnectedEvent event) {
    Player player = event.getPlayer();
    UUID uuid = player.getUniqueId();
    // Le temps passé sur le serveur précédent lui est crédité avant de changer de serveur.
    flush(uuid, System.currentTimeMillis(), false);
    String server = event.getServer().getServerInfo().getName();
    currentServer.put(uuid, server);
    db.write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("UPDATE players SET last_server = ? WHERE uuid = ?")) {
            ps.setString(1, server);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
          }
        });
  }

  @Subscribe(order = PostOrder.LAST)
  public void onDisconnect(DisconnectEvent event) {
    UUID uuid = event.getPlayer().getUniqueId();
    if (!lastFlush.containsKey(uuid)) return; // connexion refusée avant PostLogin
    long now = System.currentTimeMillis();
    flush(uuid, now, true);
    lastFlush.remove(uuid);
    currentServer.remove(uuid);
    db.write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE sessions SET end = ? WHERE id = (SELECT id FROM sessions WHERE uuid = ?"
                      + " AND end IS NULL ORDER BY start DESC LIMIT 1)")) {
            ps.setLong(1, now);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
          }
        });
  }

  /** Crédite le temps de jeu de tous les joueurs en ligne (appelé à chaque échantillonnage). */
  public void flushAll() {
    long now = System.currentTimeMillis();
    for (UUID uuid : lastFlush.keySet()) flush(uuid, now, false);
    // Le jour change pendant qu'un joueur est connecté : il compte aussi comme actif aujourd'hui.
    if (!peakDay.equals(days.todayKey())) updatePeak(proxy.getPlayerCount(), now);
  }

  private void flush(UUID uuid, long now, boolean disconnect) {
    Long since = lastFlush.get(uuid);
    if (since == null) return;
    long delta = Math.max(0, now - since);
    lastFlush.put(uuid, now);
    String server = currentServer.get(uuid);
    String day = days.day(now);
    db.write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE players SET playtime_ms = playtime_ms + ?, last_seen = ? WHERE uuid = ?")) {
            ps.setLong(1, delta);
            ps.setLong(2, now);
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO daily_activity (uuid, day, playtime_ms) VALUES (?, ?, ?)"
                      + " ON CONFLICT(uuid, day) DO UPDATE SET playtime_ms = playtime_ms + excluded.playtime_ms")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, day);
            ps.setLong(3, delta);
            ps.executeUpdate();
          }
          if (server != null && delta > 0) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "INSERT INTO server_daily (day, server, playtime_ms) VALUES (?, ?, ?)"
                        + " ON CONFLICT(day, server) DO UPDATE SET playtime_ms = playtime_ms + excluded.playtime_ms")) {
              ps.setString(1, day);
              ps.setString(2, server);
              ps.setLong(3, delta);
              ps.executeUpdate();
            }
          }
        });
  }

  private synchronized void updatePeak(int online, long now) {
    String today = days.day(now);
    if (!today.equals(peakDay)) {
      peakDay = today;
      peakToday = 0;
    }
    boolean dayRecord = online > peakToday;
    if (dayRecord) peakToday = online;
    if (online > peakAll) {
      peakAll = online;
      peakAllTs = now;
    }
    if (!dayRecord) return;
    int value = online;
    db.write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO peaks_daily (day, peak, ts) VALUES (?, ?, ?)"
                      + " ON CONFLICT(day) DO UPDATE SET peak = excluded.peak, ts = excluded.ts"
                      + " WHERE excluded.peak > peaks_daily.peak")) {
            ps.setString(1, today);
            ps.setInt(2, value);
            ps.setLong(3, now);
            ps.executeUpdate();
          }
        });
  }

  public int peakAll() {
    return peakAll;
  }

  public long peakAllTs() {
    return peakAllTs;
  }

  public int peakToday() {
    return peakDay.equals(days.todayKey()) ? peakToday : 0;
  }

  /** Crédite le temps restant de tous les joueurs avant l'arrêt du proxy. */
  public void shutdown() {
    long now = System.currentTimeMillis();
    for (UUID uuid : lastFlush.keySet()) flush(uuid, now, true);
    db.write(
        c -> {
          try (PreparedStatement ps = c.prepareStatement("UPDATE sessions SET end = ? WHERE end IS NULL")) {
            ps.setLong(1, now);
            ps.executeUpdate();
          }
        });
    lastFlush.clear();
  }
}
