package fr.astra.nexora.analytics.velocity.tracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.astra.nexora.analytics.velocity.storage.Database;
import fr.astra.nexora.analytics.velocity.storage.Days;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Intègre les lots de données envoyés par les plugins Paper : santé du serveur, économie,
 * ressources, îles NexoraMc et erreurs console.
 */
public final class IngestService {
  private static final double CHUNK = 16.0;

  private final Database db;
  private final Days days;
  private final ServerMonitor monitor;

  public IngestService(Database db, Days days, ServerMonitor monitor) {
    this.db = db;
    this.days = days;
    this.monitor = monitor;
  }

  public void ingest(JsonObject payload) {
    String server = str(payload, "server", "inconnu");
    if (server.length() > 64) server = server.substring(0, 64);
    long now = System.currentTimeMillis();
    String day = days.day(now);

    ServerMonitor.Health h = monitor.health(server);
    h.lastHeartbeat = now;
    h.pluginVersion = str(payload, "pluginVersion", null);
    if (payload.has("health") && payload.get("health").isJsonObject()) {
      JsonObject health = payload.getAsJsonObject("health");
      h.tps = dbl(health, "tps");
      h.mspt = dbl(health, "mspt");
      h.usedMemory = (long) dbl(health, "usedMemory");
      h.maxMemory = (long) dbl(health, "maxMemory");
      h.loadedChunks = (int) dbl(health, "loadedChunks");
      h.entities = (int) dbl(health, "entities");
      h.serverVersion = str(health, "version", null);
    }

    if (payload.has("crash") && payload.get("crash").isJsonObject()) {
      JsonObject crash = payload.getAsJsonObject("crash");
      monitor.recordIncident(
          server, "CRASH", str(crash, "message", "Arrêt anormal détecté"), str(crash, "details", null));
    }

    JsonArray errors = array(payload, "errors");
    if (errors != null) {
      int count = 0;
      for (JsonElement e : errors) {
        if (!e.isJsonObject() || ++count > 50) break;
        JsonObject err = e.getAsJsonObject();
        String level = str(err, "level", "ERROR");
        String logger = str(err, "logger", "");
        String message = str(err, "message", "");
        String thrown = str(err, "thrown", null);
        String details = (logger.isEmpty() ? "" : "[" + logger + "]\n") + (thrown == null ? "" : thrown);
        monitor.recordIncident(
            server, "FATAL".equals(level) ? "FATAL" : "ERROR", message, details.isEmpty() ? null : details);
      }
    }

    String srv = server;
    JsonArray economy = array(payload, "economy");
    JsonArray resources = array(payload, "resources");
    JsonObject islands =
        payload.has("islands") && payload.get("islands").isJsonObject()
            ? payload.getAsJsonObject("islands")
            : null;

    db.write(
        c -> {
          if (economy != null) writeEconomy(c, srv, day, economy);
          if (resources != null) writeResources(c, day, resources);
          if (islands != null && islands.has("list")) writeIslands(c, srv, now, day, islands.getAsJsonArray("list"));
        });
  }

  private void writeEconomy(Connection c, String server, String day, JsonArray entries) throws SQLException {
    double earnedTotal = 0;
    double spentTotal = 0;
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO economy_players (uuid, earned, spent) VALUES (?, ?, ?)"
                + " ON CONFLICT(uuid) DO UPDATE SET earned = earned + excluded.earned,"
                + " spent = spent + excluded.spent")) {
      for (JsonElement e : entries) {
        if (!e.isJsonObject()) continue;
        JsonObject o = e.getAsJsonObject();
        double earned = Math.max(0, dbl(o, "earned"));
        double spent = Math.max(0, dbl(o, "spent"));
        if (!Double.isFinite(earned) || !Double.isFinite(spent) || earned + spent == 0) continue;
        earnedTotal += earned;
        spentTotal += spent;
        ps.setString(1, str(o, "uuid", ""));
        ps.setDouble(2, earned);
        ps.setDouble(3, spent);
        ps.addBatch();
      }
      ps.executeBatch();
    }
    if (earnedTotal + spentTotal == 0) return;
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO economy_daily (day, server, earned, spent) VALUES (?, ?, ?, ?)"
                + " ON CONFLICT(day, server) DO UPDATE SET earned = earned + excluded.earned,"
                + " spent = spent + excluded.spent")) {
      ps.setString(1, day);
      ps.setString(2, server);
      ps.setDouble(3, earnedTotal);
      ps.setDouble(4, spentTotal);
      ps.executeUpdate();
    }
  }

  private void writeResources(Connection c, String day, JsonArray entries) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO resources_daily (day, action, material, count) VALUES (?, ?, ?, ?)"
                + " ON CONFLICT(day, action, material) DO UPDATE SET count = count + excluded.count")) {
      for (JsonElement e : entries) {
        if (!e.isJsonObject()) continue;
        JsonObject o = e.getAsJsonObject();
        long count = (long) dbl(o, "count");
        String action = str(o, "action", "");
        String material = str(o, "material", "");
        if (count <= 0 || action.isEmpty() || material.isEmpty() || material.length() > 64) continue;
        ps.setString(1, day);
        ps.setString(2, action);
        ps.setString(3, material);
        ps.setLong(4, count);
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  /**
   * Compare l'instantané des îles envoyé par le serveur à celui stocké : les îles inconnues sont
   * comptées comme créées, les agrandissements de bordure comme des chunks débloqués. Le tout
   * premier instantané d'un serveur sert de référence (les îles déjà existantes ne sont pas
   * comptées comme créées aujourd'hui).
   */
  private void writeIslands(Connection c, String server, long now, String day, JsonArray list)
      throws SQLException {
    Map<String, Double> known = new HashMap<>();
    Set<String> deleted = new HashSet<>();
    try (PreparedStatement ps =
        c.prepareStatement("SELECT id, border, deleted_at FROM islands WHERE server = ?")) {
      ps.setString(1, server);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          known.put(rs.getString(1), rs.getDouble(2));
          if (rs.getObject(3) != null) deleted.add(rs.getString(1));
        }
      }
    }
    boolean baseline = !flag(c, "islands_baseline:" + server);

    Set<String> seen = new HashSet<>();
    try (PreparedStatement upsert =
            c.prepareStatement(
                "INSERT INTO islands (server, id, name, owner, border, level, members, milestones,"
                    + " objectives_done, created_at, deleted_at, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)"
                    + " ON CONFLICT(server, id) DO UPDATE SET name = excluded.name, owner = excluded.owner,"
                    + " border = excluded.border, level = excluded.level, members = excluded.members,"
                    + " milestones = excluded.milestones, objectives_done = excluded.objectives_done,"
                    + " deleted_at = NULL, updated_at = excluded.updated_at");
        PreparedStatement event =
            c.prepareStatement(
                "INSERT INTO island_events (ts, day, server, island_id, type, value) VALUES (?, ?, ?, ?, ?, ?)")) {
      for (JsonElement e : list) {
        if (!e.isJsonObject()) continue;
        JsonObject o = e.getAsJsonObject();
        String id = str(o, "id", null);
        if (id == null) continue;
        seen.add(id);
        double border = dbl(o, "border");
        boolean isNew = !known.containsKey(id) || deleted.contains(id);

        upsert.setString(1, server);
        upsert.setString(2, id);
        upsert.setString(3, str(o, "name", null));
        upsert.setString(4, str(o, "owner", null));
        upsert.setDouble(5, border);
        upsert.setInt(6, (int) dbl(o, "level"));
        upsert.setInt(7, (int) dbl(o, "members"));
        upsert.setInt(8, (int) dbl(o, "milestones"));
        upsert.setInt(9, (int) dbl(o, "objectivesDone"));
        if (isNew && !baseline) upsert.setLong(10, now);
        else upsert.setNull(10, java.sql.Types.INTEGER);
        upsert.setLong(11, now);
        upsert.addBatch();

        if (baseline) continue;
        if (isNew) {
          addEvent(event, now, day, server, id, "CREATED", chunks(border));
        } else {
          double old = known.get(id);
          if (border > old + 0.01) addEvent(event, now, day, server, id, "GROWTH", chunks(border) - chunks(old));
        }
      }
      upsert.executeBatch();

      for (String id : known.keySet()) {
        if (seen.contains(id) || deleted.contains(id)) continue;
        try (PreparedStatement ps =
            c.prepareStatement("UPDATE islands SET deleted_at = ? WHERE server = ? AND id = ?")) {
          ps.setLong(1, now);
          ps.setString(2, server);
          ps.setString(3, id);
          ps.executeUpdate();
        }
        if (!baseline) addEvent(event, now, day, server, id, "DELETED", 0);
      }
      event.executeBatch();
    }
    if (baseline) setFlag(c, "islands_baseline:" + server);
  }

  private static void addEvent(
      PreparedStatement ps, long ts, String day, String server, String id, String type, double value)
      throws SQLException {
    ps.setLong(1, ts);
    ps.setString(2, day);
    ps.setString(3, server);
    ps.setString(4, id);
    ps.setString(5, type);
    ps.setDouble(6, value);
    ps.addBatch();
  }

  /** Surface d'une bordure carrée, exprimée en chunks (16×16 blocs). */
  static double chunks(double border) {
    return (border / CHUNK) * (border / CHUNK);
  }

  private static boolean flag(Connection c, String key) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kv WHERE key = ?")) {
      ps.setString(1, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private static void setFlag(Connection c, String key) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO kv (key, value) VALUES (?, '1')")) {
      ps.setString(1, key);
      ps.executeUpdate();
    }
  }

  private static JsonArray array(JsonObject o, String key) {
    return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : null;
  }

  private static String str(JsonObject o, String key, String def) {
    if (!o.has(key) || o.get(key).isJsonNull()) return def;
    try {
      return o.get(key).getAsString();
    } catch (Exception e) {
      return def;
    }
  }

  private static double dbl(JsonObject o, String key) {
    if (!o.has(key) || o.get(key).isJsonNull()) return 0;
    try {
      return o.get(key).getAsDouble();
    } catch (Exception e) {
      return 0;
    }
  }
}
