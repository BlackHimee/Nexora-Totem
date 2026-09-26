package fr.astra.nexora.analytics.velocity.stats;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import fr.astra.nexora.analytics.velocity.config.AnalyticsConfig;
import fr.astra.nexora.analytics.velocity.storage.Database;
import fr.astra.nexora.analytics.velocity.storage.Days;
import fr.astra.nexora.analytics.velocity.tracker.PlayerTracker;
import fr.astra.nexora.analytics.velocity.tracker.ServerMonitor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Calcule toutes les statistiques affichées par le dashboard. Les résultats sont mis en cache
 * quelques secondes pour qu'un rafraîchissement fréquent de la page ne sollicite pas la base.
 */
public final class StatsService {
  private static final long CACHE_MS = 20_000;
  private static final int[] RETENTION_OFFSETS = {1, 3, 7, 14, 30};
  private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd/MM", Locale.FRANCE);

  private record PlayerRow(
      String uuid, String name, long firstSeen, long lastSeen, long playtime, int sessions,
      String version, String brand, String lastServer) {}

  private record Cached(long at, Map<String, Object> value) {}

  private final ProxyServer proxy;
  private final Database db;
  private final Days days;
  private final AnalyticsConfig config;
  private final PlayerTracker tracker;
  private final ServerMonitor monitor;
  private final Map<Integer, Cached> cache = new ConcurrentHashMap<>();

  public StatsService(
      ProxyServer proxy, Database db, Days days, AnalyticsConfig config, PlayerTracker tracker,
      ServerMonitor monitor) {
    this.proxy = proxy;
    this.db = db;
    this.days = days;
    this.config = config;
    this.tracker = tracker;
    this.monitor = monitor;
  }

  // ---------------------------------------------------------------------------------------------
  // Dashboard complet
  // ---------------------------------------------------------------------------------------------

  public Map<String, Object> dashboard(int range) throws Exception {
    Cached cached = cache.get(range);
    long now = System.currentTimeMillis();
    if (cached != null && now - cached.at < CACHE_MS) return cached.value;
    Map<String, Object> value = db.read(c -> compute(c, range)).get(30, TimeUnit.SECONDS);
    cache.put(range, new Cached(now, value));
    return value;
  }

  private Map<String, Object> compute(Connection c, int range) throws SQLException {
    long now = System.currentTimeMillis();
    LocalDate today = days.today();
    LocalDate rangeStart = today.minusDays(range - 1L);
    long rangeStartMs = days.startOf(rangeStart);
    int window = Math.max(range + 60, 120);
    LocalDate windowStart = today.minusDays(window);

    Map<String, PlayerRow> players = loadPlayers(c);
    Map<String, TreeSet<LocalDate>> activity = new HashMap<>();
    TreeMap<LocalDate, long[]> perDay = new TreeMap<>(); // [actifs, temps de jeu]
    for (LocalDate d = rangeStart; !d.isAfter(today); d = d.plusDays(1)) perDay.put(d, new long[2]);

    try (PreparedStatement ps =
        c.prepareStatement("SELECT uuid, day, playtime_ms FROM daily_activity WHERE day >= ?")) {
      ps.setString(1, windowStart.toString());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          LocalDate day = LocalDate.parse(rs.getString(2));
          activity.computeIfAbsent(rs.getString(1), k -> new TreeSet<>()).add(day);
          long[] agg = perDay.get(day);
          if (agg != null) {
            agg[0]++;
            agg[1] += rs.getLong(3);
          }
        }
      }
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("generatedAt", now);
    out.put("timezone", days.zone().getId());
    out.put("range", range);
    out.put("today", today.toString());

    // ---- Séries journalières -------------------------------------------------------------------
    Map<LocalDate, Map<String, Object>> daily = new TreeMap<>();
    for (var e : perDay.entrySet()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("day", e.getKey().toString());
      row.put("label", e.getKey().format(SHORT));
      row.put("active", e.getValue()[0]);
      row.put("playtimeMs", e.getValue()[1]);
      row.put("avgPlaytimeMs", e.getValue()[0] == 0 ? 0 : e.getValue()[1] / e.getValue()[0]);
      row.put("new", 0);
      row.put("connections", 0);
      row.put("disconnections", 0);
      row.put("peak", 0);
      row.put("earned", 0.0);
      row.put("spent", 0.0);
      row.put("islandsCreated", 0);
      row.put("chunks", 0.0);
      row.put("becameInactive", 0);
      row.put("errors", 0);
      daily.put(e.getKey(), row);
    }

    // Nouveaux joueurs.
    int newToday = 0;
    int newYesterday = 0;
    Map<String, LocalDate> firstDates = new HashMap<>();
    for (PlayerRow p : players.values()) {
      LocalDate first = days.date(p.firstSeen);
      firstDates.put(p.uuid, first);
      if (first.equals(today)) newToday++;
      if (first.equals(today.minusDays(1))) newYesterday++;
      inc(daily, first, "new", 1);
    }

    // Connexions / déconnexions.
    long connectionsRange = 0;
    long disconnectionsRange = 0;
    long sessionTotal = 0;
    long sessionCount = 0;
    try (PreparedStatement ps =
        c.prepareStatement("SELECT start, end FROM sessions WHERE start >= ? OR end >= ?")) {
      ps.setLong(1, rangeStartMs);
      ps.setLong(2, rangeStartMs);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          long start = rs.getLong(1);
          long end = rs.getLong(2);
          boolean ended = !rs.wasNull();
          if (start >= rangeStartMs) {
            connectionsRange++;
            inc(daily, days.date(start), "connections", 1);
          }
          if (ended && end >= rangeStartMs) {
            disconnectionsRange++;
            inc(daily, days.date(end), "disconnections", 1);
            if (end > start) {
              sessionTotal += end - start;
              sessionCount++;
            }
          }
        }
      }
    }

    // Pics journaliers.
    try (PreparedStatement ps = c.prepareStatement("SELECT day, peak FROM peaks_daily WHERE day >= ?")) {
      ps.setString(1, rangeStart.toString());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) set(daily, LocalDate.parse(rs.getString(1)), "peak", rs.getInt(2));
      }
    }

    // ---- KPIs d'activité -----------------------------------------------------------------------
    int active1 = 0;
    int active7 = 0;
    int active30 = 0;
    int activeRange = 0;
    long rangePlaytime = 0;
    long rangePlayerDays = 0;
    for (var e : perDay.entrySet()) {
      rangePlaytime += e.getValue()[1];
      rangePlayerDays += e.getValue()[0];
    }
    for (TreeSet<LocalDate> set : activity.values()) {
      LocalDate last = set.last();
      if (!last.isBefore(today)) active1++;
      if (!last.isBefore(today.minusDays(6))) active7++;
      if (!last.isBefore(today.minusDays(29))) active30++;
      if (!last.isBefore(rangeStart)) activeRange++;
    }

    Map<String, Object> kpis = new LinkedHashMap<>();
    kpis.put("uniquePlayers", players.size());
    kpis.put("newToday", newToday);
    kpis.put("newYesterday", newYesterday);
    kpis.put("activeToday", active1);
    kpis.put("active7", active7);
    kpis.put("active30", active30);
    kpis.put("activeRange", activeRange);
    kpis.put("online", proxy.getPlayerCount());
    kpis.put("peakToday", tracker.peakToday());
    kpis.put("peakAll", tracker.peakAll());
    kpis.put("peakAllTs", tracker.peakAllTs());
    kpis.put("avgPlaytimeDayMs", rangePlayerDays == 0 ? 0 : rangePlaytime / rangePlayerDays);
    kpis.put("avgSessionMs", sessionCount == 0 ? 0 : sessionTotal / sessionCount);
    kpis.put("totalPlaytimeRangeMs", rangePlaytime);
    kpis.put("connectionsToday", get(daily, today, "connections"));
    kpis.put("disconnectionsToday", get(daily, today, "disconnections"));
    kpis.put("connectionsRange", connectionsRange);
    kpis.put("disconnectionsRange", disconnectionsRange);
    out.put("kpis", kpis);

    // ---- Rétention -----------------------------------------------------------------------------
    Map<String, Object> retention = new LinkedHashMap<>();
    for (int n : new int[] {1, 7, 30}) {
      int cohort = 0;
      int retained = 0;
      for (var e : firstDates.entrySet()) {
        LocalDate first = e.getValue();
        if (first.isAfter(today.minusDays(n)) || first.isBefore(today.minusDays(n + 29L))) continue;
        cohort++;
        TreeSet<LocalDate> set = activity.get(e.getKey());
        if (set != null && set.contains(first.plusDays(n))) retained++;
      }
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("cohort", cohort);
      r.put("retained", retained);
      r.put("rate", cohort == 0 ? null : round(100.0 * retained / cohort, 1));
      retention.put("d" + n, r);
    }
    retention.put("offsets", RETENTION_OFFSETS);
    retention.put("cohorts", cohortMatrix(today, firstDates, activity));
    out.put("retention", retention);

    // ---- Retours après absence, streaks, inactivité ---------------------------------------------
    Set<String> back1 = new HashSet<>();
    Set<String> back7 = new HashSet<>();
    Set<String> back30 = new HashSet<>();
    List<Map<String, Object>> streaks = new ArrayList<>();
    long streakSum = 0;
    int streakPlayers = 0;
    int bestStreak = 0;
    String bestStreakName = null;
    for (var e : activity.entrySet()) {
      String uuid = e.getKey();
      TreeSet<LocalDate> set = e.getValue();
      LocalDate first = firstDates.get(uuid);
      for (LocalDate d : set.tailSet(rangeStart, true)) {
        LocalDate prev = set.lower(d);
        long gap;
        if (prev != null) gap = ChronoUnit.DAYS.between(prev, d) - 1;
        else if (first != null && first.isBefore(windowStart)) gap = ChronoUnit.DAYS.between(windowStart, d);
        else continue; // première venue : pas un retour
        if (gap >= 1) back1.add(uuid);
        if (gap >= 7) back7.add(uuid);
        if (gap >= 30) back30.add(uuid);
      }
      int current = currentStreak(set, today);
      int best = bestStreak(set);
      PlayerRow p = players.get(uuid);
      if (best > bestStreak && p != null) {
        bestStreak = best;
        bestStreakName = p.name;
      }
      if (current > 0) {
        streakSum += current;
        streakPlayers++;
      }
      if (current >= 2 && p != null) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("uuid", uuid);
        s.put("name", p.name);
        s.put("streak", current);
        s.put("best", best);
        s.put("playsToday", set.contains(today));
        streaks.add(s);
      }
    }
    streaks.sort(Comparator.comparingInt((Map<String, Object> m) -> (int) m.get("streak")).reversed());
    Map<String, Object> returning = new LinkedHashMap<>();
    returning.put("after1", back1.size());
    returning.put("after7", back7.size());
    returning.put("after30", back30.size());
    out.put("returning", returning);

    Map<String, Object> streakOut = new LinkedHashMap<>();
    streakOut.put("average", streakPlayers == 0 ? 0 : round((double) streakSum / streakPlayers, 1));
    streakOut.put("activeStreaks", streaks.size());
    streakOut.put("best", bestStreak);
    streakOut.put("bestName", bestStreakName);
    streakOut.put("windowDays", window);
    streakOut.put("top", streaks.subList(0, Math.min(10, streaks.size())));
    out.put("streaks", streakOut);

    out.put("inactivity", inactivity(players, activity, daily, today, rangeStart));

    // ---- Heures d'affluence et courbe des dernières 24 h ----------------------------------------
    out.putAll(samples(c, now, rangeStartMs));

    // ---- Versions, clients, serveurs -----------------------------------------------------------
    Map<String, Integer> versions = new HashMap<>();
    Map<String, Integer> brands = new HashMap<>();
    long rangeStartTs = rangeStartMs;
    for (PlayerRow p : players.values()) {
      if (p.lastSeen < rangeStartTs) continue;
      versions.merge(p.version == null ? "Inconnue" : p.version, 1, Integer::sum);
      brands.merge(normalizeBrand(p.brand), 1, Integer::sum);
    }
    out.put("versions", topEntries(versions, "version", 12));
    out.put("brands", topEntries(brands, "brand", 8));
    out.put("servers", servers(c, rangeStart));

    // ---- Économie, îles, ressources, incidents --------------------------------------------------
    out.put("economy", economy(c, daily, rangeStart, players));
    out.put("islands", islands(c, daily, today, rangeStart, players));
    out.put("resources", resources(c, rangeStart));
    out.put("incidents", incidents(c, daily, now, rangeStartMs));

    out.put("daily", new ArrayList<>(daily.values()));
    return out;
  }

  private Map<String, PlayerRow> loadPlayers(Connection c) throws SQLException {
    Map<String, PlayerRow> players = new HashMap<>();
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT uuid, name, first_seen, last_seen, playtime_ms, sessions, version, brand,"
                    + " last_server FROM players")) {
      while (rs.next()) {
        PlayerRow p =
            new PlayerRow(
                rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                rs.getInt(6), rs.getString(7), rs.getString(8), rs.getString(9));
        players.put(p.uuid, p);
      }
    }
    return players;
  }

  /** Matrice de rétention par cohorte hebdomadaire (semaine de première connexion). */
  private List<Map<String, Object>> cohortMatrix(
      LocalDate today, Map<String, LocalDate> firstDates, Map<String, TreeSet<LocalDate>> activity) {
    List<Map<String, Object>> rows = new ArrayList<>();
    LocalDate weekStart = today.with(DayOfWeek.MONDAY);
    for (int w = 0; w < 8; w++) {
      LocalDate from = weekStart.minusWeeks(w);
      LocalDate to = from.plusDays(6);
      List<String> members = new ArrayList<>();
      for (var e : firstDates.entrySet()) {
        if (!e.getValue().isBefore(from) && !e.getValue().isAfter(to)) members.add(e.getKey());
      }
      List<Object> values = new ArrayList<>();
      for (int off : RETENTION_OFFSETS) {
        int eligible = 0;
        int retained = 0;
        for (String uuid : members) {
          LocalDate target = firstDates.get(uuid).plusDays(off);
          if (target.isAfter(today)) continue;
          eligible++;
          TreeSet<LocalDate> set = activity.get(uuid);
          if (set != null && set.contains(target)) retained++;
        }
        values.add(eligible == 0 ? null : round(100.0 * retained / eligible, 1));
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("label", "Sem. du " + from.format(SHORT));
      row.put("size", members.size());
      row.put("values", values);
      rows.add(row);
    }
    return rows;
  }

  private Map<String, Object> inactivity(
      Map<String, PlayerRow> players, Map<String, TreeSet<LocalDate>> activity,
      Map<LocalDate, Map<String, Object>> daily, LocalDate today, LocalDate rangeStart) {
    int threshold = config.inactiveDays();
    int inactive = 0;
    int atRisk = 0;
    int recentlyInactive = 0;
    List<PlayerRow> list = new ArrayList<>();
    for (PlayerRow p : players.values()) {
      LocalDate last = days.date(p.lastSeen);
      long away = ChronoUnit.DAYS.between(last, today);
      LocalDate becameInactive = last.plusDays(threshold);
      if (!becameInactive.isAfter(today)) {
        inactive++;
        inc(daily, becameInactive, "becameInactive", 1);
        if (away < threshold + 7L) recentlyInactive++;
        if (away < threshold + 30L && p.sessions >= 3) list.add(p);
      } else if (away >= Math.max(2, threshold / 2) && p.sessions >= 3) {
        atRisk++;
      }
    }
    list.sort(Comparator.comparingLong((PlayerRow p) -> p.playtime).reversed());
    List<Map<String, Object>> rows = new ArrayList<>();
    for (PlayerRow p : list.subList(0, Math.min(15, list.size()))) {
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("uuid", p.uuid);
      r.put("name", p.name);
      r.put("lastSeen", p.lastSeen);
      r.put("playtimeMs", p.playtime);
      r.put("sessions", p.sessions);
      TreeSet<LocalDate> set = activity.get(p.uuid);
      r.put("activeDays", set == null ? 0 : set.size());
      rows.add(r);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("thresholdDays", threshold);
    out.put("inactive", inactive);
    out.put("recentlyInactive", recentlyInactive);
    out.put("atRisk", atRisk);
    out.put("lost", rows);
    return out;
  }

  private Map<String, Object> samples(Connection c, long now, long rangeStartMs) throws SQLException {
    double[][] heatSum = new double[7][24];
    int[][] heatCount = new int[7][24];
    long dayAgo = now - TimeUnit.HOURS.toMillis(24);
    long bucketMs = TimeUnit.MINUTES.toMillis(10);
    TreeMap<Long, double[]> timeline = new TreeMap<>(); // bucket -> [somme, n]
    TreeMap<Long, Map<String, double[]>> perServer = new TreeMap<>();
    long from = Math.min(rangeStartMs, dayAgo);
    try (PreparedStatement ps = c.prepareStatement("SELECT ts, online, servers FROM samples WHERE ts >= ?")) {
      ps.setLong(1, from);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          long ts = rs.getLong(1);
          int online = rs.getInt(2);
          if (ts >= rangeStartMs) {
            ZonedDateTime t = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ts), days.zone());
            int dow = t.getDayOfWeek().getValue() - 1;
            heatSum[dow][t.getHour()] += online;
            heatCount[dow][t.getHour()]++;
          }
          if (ts >= dayAgo) {
            long bucket = ts - (ts % bucketMs);
            double[] agg = timeline.computeIfAbsent(bucket, k -> new double[2]);
            agg[0] += online;
            agg[1]++;
            String servers = rs.getString(3);
            if (servers != null) {
              try {
                JsonObject o = JsonParser.parseString(servers).getAsJsonObject();
                Map<String, double[]> map = perServer.computeIfAbsent(bucket, k -> new HashMap<>());
                for (var e : o.entrySet()) {
                  double[] a = map.computeIfAbsent(e.getKey(), k -> new double[2]);
                  a[0] += e.getValue().getAsDouble();
                  a[1]++;
                }
              } catch (Exception ignored) {
                // échantillon corrompu : ignoré
              }
            }
          }
        }
      }
    }
    List<List<Double>> heatmap = new ArrayList<>();
    double[] hourSum = new double[24];
    int[] hourCount = new int[24];
    for (int d = 0; d < 7; d++) {
      List<Double> row = new ArrayList<>();
      for (int h = 0; h < 24; h++) {
        row.add(heatCount[d][h] == 0 ? 0.0 : round(heatSum[d][h] / heatCount[d][h], 1));
        hourSum[h] += heatSum[d][h];
        hourCount[h] += heatCount[d][h];
      }
      heatmap.add(row);
    }
    List<Double> hours = new ArrayList<>();
    for (int h = 0; h < 24; h++) hours.add(hourCount[h] == 0 ? 0.0 : round(hourSum[h] / hourCount[h], 1));

    List<Map<String, Object>> points = new ArrayList<>();
    Set<String> serverNames = new TreeSet<>();
    perServer.values().forEach(m -> serverNames.addAll(m.keySet()));
    for (var e : timeline.entrySet()) {
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("ts", e.getKey());
      p.put("online", round(e.getValue()[0] / e.getValue()[1], 1));
      Map<String, Object> srv = new LinkedHashMap<>();
      Map<String, double[]> m = perServer.getOrDefault(e.getKey(), Map.of());
      for (String s : serverNames) {
        double[] a = m.get(s);
        srv.put(s, a == null ? 0.0 : round(a[0] / a[1], 1));
      }
      p.put("servers", srv);
      points.add(p);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("heatmap", heatmap);
    out.put("hours", hours);
    out.put("timeline", points);
    return out;
  }

  private List<Map<String, Object>> servers(Connection c, LocalDate rangeStart) throws SQLException {
    Map<String, Long> playtime = new HashMap<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT server, SUM(playtime_ms) FROM server_daily WHERE day >= ? GROUP BY server")) {
      ps.setString(1, rangeStart.toString());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) playtime.put(rs.getString(1), rs.getLong(2));
      }
    }
    List<Map<String, Object>> list = new ArrayList<>();
    Set<String> names = new java.util.LinkedHashSet<>();
    for (RegisteredServer s : proxy.getAllServers()) names.add(s.getServerInfo().getName());
    names.addAll(playtime.keySet());
    // Collecteurs Paper dont le server-name ne correspond à aucun serveur du proxy.
    monitor.allHealth().forEach((name, h) -> {
      if (h.lastHeartbeat > 0) names.add(name);
    });
    for (String name : names) {
      Map<String, Object> row = serverStatus(name);
      row.put("playtimeMs", playtime.getOrDefault(name, 0L));
      list.add(row);
    }
    list.sort(Comparator.comparingLong((Map<String, Object> m) -> (long) m.get("playtimeMs")).reversed());
    return list;
  }

  private Map<String, Object> serverStatus(String name) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name", name);
    Optional<RegisteredServer> server = proxy.getServer(name);
    row.put("registered", server.isPresent());
    row.put("online", server.map(s -> s.getPlayersConnected().size()).orElse(0));
    ServerMonitor.Health h = monitor.allHealth().get(name);
    long now = System.currentTimeMillis();
    if (h != null) {
      boolean fresh = now - h.lastHeartbeat < TimeUnit.MINUTES.toMillis(3);
      String status = !h.observed ? "unknown" : h.online ? "online" : "offline";
      if (server.isEmpty()) status = fresh ? "online" : "unknown"; // pas de ping possible : on se fie au collecteur
      row.put("status", status);
      row.put("since", h.since);
      row.put("collector", h.lastHeartbeat == 0 ? "absent" : fresh ? "ok" : "stale");
      row.put("lastHeartbeat", h.lastHeartbeat);
      row.put("tps", fresh ? round(h.tps, 2) : null);
      row.put("mspt", fresh ? round(h.mspt, 2) : null);
      row.put("usedMemory", fresh ? h.usedMemory : null);
      row.put("maxMemory", fresh ? h.maxMemory : null);
      row.put("loadedChunks", fresh ? h.loadedChunks : null);
      row.put("entities", fresh ? h.entities : null);
      row.put("serverVersion", h.serverVersion);
      row.put("pluginVersion", h.pluginVersion);
      row.put("economyStatus", h.economyStatus);
      row.put("islandsStatus", h.islandsStatus);
    } else {
      row.put("status", "unknown");
      row.put("collector", "absent");
    }
    return row;
  }

  private Map<String, Object> economy(
      Connection c, Map<LocalDate, Map<String, Object>> daily, LocalDate rangeStart,
      Map<String, PlayerRow> players) throws SQLException {
    double earned = 0;
    double spent = 0;
    Map<String, double[]> perServer = new TreeMap<>();
    try (PreparedStatement ps =
        c.prepareStatement("SELECT day, server, earned, spent FROM economy_daily WHERE day >= ?")) {
      ps.setString(1, rangeStart.toString());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          LocalDate day = LocalDate.parse(rs.getString(1));
          double e = rs.getDouble(3);
          double s = rs.getDouble(4);
          earned += e;
          spent += s;
          incD(daily, day, "earned", e);
          incD(daily, day, "spent", s);
          double[] agg = perServer.computeIfAbsent(rs.getString(2), k -> new double[2]);
          agg[0] += e;
          agg[1] += s;
        }
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("earned", round(earned, 2));
    out.put("spent", round(spent, 2));
    out.put("net", round(earned - spent, 2));
    List<Map<String, Object>> servers = new ArrayList<>();
    perServer.forEach(
        (k, v) -> {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("server", k);
          r.put("earned", round(v[0], 2));
          r.put("spent", round(v[1], 2));
          servers.add(r);
        });
    out.put("servers", servers);
    out.put("topEarners", topEconomy(c, players, "earned"));
    out.put("topSpenders", topEconomy(c, players, "spent"));
    return out;
  }

  private List<Map<String, Object>> topEconomy(Connection c, Map<String, PlayerRow> players, String column)
      throws SQLException {
    List<Map<String, Object>> rows = new ArrayList<>();
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT uuid, earned, spent FROM economy_players WHERE " + column
                    + " > 0 ORDER BY " + column + " DESC LIMIT 8")) {
      while (rs.next()) {
        Map<String, Object> r = new LinkedHashMap<>();
        PlayerRow p = players.get(rs.getString(1));
        r.put("uuid", rs.getString(1));
        r.put("name", p == null ? "?" : p.name);
        r.put("earned", round(rs.getDouble(2), 2));
        r.put("spent", round(rs.getDouble(3), 2));
        rows.add(r);
      }
    }
    return rows;
  }

  private Map<String, Object> islands(
      Connection c, Map<LocalDate, Map<String, Object>> daily, LocalDate today, LocalDate rangeStart,
      Map<String, PlayerRow> players) throws SQLException {
    Map<String, Object> out = new LinkedHashMap<>();
    boolean available;
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM kv WHERE key LIKE 'islands_baseline:%'")) {
      available = rs.next() && rs.getInt(1) > 0;
    }
    out.put("available", available);

    int total = 0;
    double levelSum = 0;
    double borderSum = 0;
    double chunkSum = 0;
    long memberSum = 0;
    double weightedLevel = 0;
    long milestoneSum = 0;
    Map<Integer, Integer> levels = new TreeMap<>();
    List<Map<String, Object>> top = new ArrayList<>();
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT server, id, name, owner, border, level, members, milestones, objectives_done,"
                    + " created_at FROM islands WHERE deleted_at IS NULL ORDER BY border DESC")) {
      while (rs.next()) {
        total++;
        double border = rs.getDouble(5);
        int level = rs.getInt(6);
        int members = Math.max(1, rs.getInt(7));
        levelSum += level;
        borderSum += border;
        chunkSum += (border / 16.0) * (border / 16.0);
        memberSum += members;
        weightedLevel += (double) level * members;
        milestoneSum += rs.getInt(8);
        levels.merge(level, 1, Integer::sum);
        if (top.size() < 10) {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("server", rs.getString(1));
          r.put("name", rs.getString(3));
          PlayerRow owner = rs.getString(4) == null ? null : players.get(rs.getString(4));
          r.put("owner", owner == null ? "?" : owner.name);
          r.put("border", round(border, 1));
          r.put("level", level);
          r.put("members", members);
          r.put("milestones", rs.getInt(8));
          r.put("objectivesToday", rs.getInt(9));
          long created = rs.getLong(10);
          r.put("createdAt", rs.wasNull() ? null : created);
          top.add(r);
        }
      }
    }
    int createdToday = 0;
    int created7 = 0;
    int createdRange = 0;
    int deletedRange = 0;
    double chunksRange = 0;
    try (PreparedStatement ps =
        c.prepareStatement("SELECT day, type, value FROM island_events WHERE day >= ?")) {
      ps.setString(1, today.minusDays(Math.max(7, daily.size())).toString());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          LocalDate day = LocalDate.parse(rs.getString(1));
          String type = rs.getString(2);
          double value = rs.getDouble(3);
          boolean inRange = !day.isBefore(rangeStart);
          switch (type) {
            case "CREATED" -> {
              if (day.equals(today)) createdToday++;
              if (!day.isBefore(today.minusDays(6))) created7++;
              if (inRange) {
                createdRange++;
                inc(daily, day, "islandsCreated", 1);
              }
            }
            case "GROWTH" -> {
              if (inRange) {
                chunksRange += value;
                incD(daily, day, "chunks", value);
              }
            }
            case "DELETED" -> {
              if (inRange) deletedRange++;
            }
            default -> {}
          }
        }
      }
    }
    out.put("total", total);
    out.put("createdToday", createdToday);
    out.put("created7", created7);
    out.put("createdRange", createdRange);
    out.put("deletedRange", deletedRange);
    out.put("chunksTotal", round(chunkSum, 1));
    out.put("chunksRange", round(chunksRange, 1));
    out.put("avgLevel", total == 0 ? 0 : round(levelSum / total, 2));
    out.put("avgBorder", total == 0 ? 0 : round(borderSum / total, 1));
    out.put("avgMembers", total == 0 ? 0 : round((double) memberSum / total, 2));
    out.put("avgMilestones", total == 0 ? 0 : round((double) milestoneSum / total, 2));
    out.put("avgPlayerLevel", memberSum == 0 ? 0 : round(weightedLevel / memberSum, 2));
    out.put("playersWithIsland", memberSum);
    out.put("playersWithIslandPct", players.isEmpty() ? 0 : round(100.0 * memberSum / players.size(), 1));
    List<Map<String, Object>> levelRows = new ArrayList<>();
    levels.forEach(
        (k, v) -> {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("level", k);
          r.put("count", v);
          levelRows.add(r);
        });
    out.put("levels", levelRows);
    out.put("top", top);
    return out;
  }

  private Map<String, Object> resources(Connection c, LocalDate rangeStart) throws SQLException {
    Map<String, Map<String, Long>> byAction = new LinkedHashMap<>();
    Map<String, Long> totals = new LinkedHashMap<>();
    for (String a : new String[] {"BREAK", "PLACE", "CRAFT"}) {
      byAction.put(a, new HashMap<>());
      totals.put(a, 0L);
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT action, material, SUM(count) FROM resources_daily WHERE day >= ? GROUP BY action, material")) {
      ps.setString(1, rangeStart.toString());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String action = rs.getString(1);
          byAction.computeIfAbsent(action, k -> new HashMap<>()).put(rs.getString(2), rs.getLong(3));
          totals.merge(action, rs.getLong(3), Long::sum);
        }
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("totals", totals);
    for (var e : byAction.entrySet()) {
      List<Map<String, Object>> rows = new ArrayList<>();
      e.getValue().entrySet().stream()
          .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
          .limit(12)
          .forEach(
              m -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("material", m.getKey());
                r.put("count", m.getValue());
                rows.add(r);
              });
      out.put(e.getKey().toLowerCase(Locale.ROOT), rows);
    }
    return out;
  }

  private Map<String, Object> incidents(
      Connection c, Map<LocalDate, Map<String, Object>> daily, long now, long rangeStartMs)
      throws SQLException {
    Map<String, Integer> last24 = new LinkedHashMap<>();
    for (String t : new String[] {"CRASH", "FATAL", "ERROR", "DOWN", "UP"}) last24.put(t, 0);
    long dayAgo = now - TimeUnit.HOURS.toMillis(24);
    try (PreparedStatement ps = c.prepareStatement("SELECT ts, type FROM incidents WHERE ts >= ?")) {
      ps.setLong(1, Math.min(rangeStartMs, dayAgo));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          long ts = rs.getLong(1);
          String type = rs.getString(2);
          if (ts >= dayAgo) last24.merge(type, 1, Integer::sum);
          if (ts >= rangeStartMs && !"UP".equals(type)) inc(daily, days.date(ts), "errors", 1);
        }
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("last24h", last24);
    out.put("recent", recentIncidents(c, 60));
    return out;
  }

  private List<Map<String, Object>> recentIncidents(Connection c, int limit) throws SQLException {
    List<Map<String, Object>> rows = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, ts, server, type, message, details FROM incidents ORDER BY ts DESC LIMIT ?")) {
      ps.setInt(1, limit);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("id", rs.getLong(1));
          r.put("ts", rs.getLong(2));
          r.put("server", rs.getString(3));
          r.put("type", rs.getString(4));
          r.put("message", rs.getString(5));
          r.put("details", rs.getString(6));
          rows.add(r);
        }
      }
    }
    return rows;
  }

  // ---------------------------------------------------------------------------------------------
  // Temps réel
  // ---------------------------------------------------------------------------------------------

  public Map<String, Object> live() {
    Map<String, Object> out = new LinkedHashMap<>();
    long now = System.currentTimeMillis();
    List<Map<String, Object>> list = new ArrayList<>();
    for (Player p : proxy.getAllPlayers()) {
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("uuid", p.getUniqueId().toString());
      r.put("name", p.getUsername());
      r.put("server", p.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null));
      r.put("version", p.getProtocolVersion().getMostRecentSupportedVersion());
      r.put("brand", normalizeBrand(p.getClientBrand()));
      r.put("ping", p.getPing());
      list.add(r);
    }
    list.sort(Comparator.comparing(m -> String.valueOf(m.get("name")), String.CASE_INSENSITIVE_ORDER));
    out.put("ts", now);
    out.put("online", list.size());
    out.put("peakToday", tracker.peakToday());
    out.put("peakAll", tracker.peakAll());
    out.put("players", list);
    List<Map<String, Object>> servers = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (RegisteredServer s : proxy.getAllServers()) {
      seen.add(s.getServerInfo().getName());
      servers.add(serverStatus(s.getServerInfo().getName()));
    }
    monitor.allHealth().forEach((name, h) -> {
      if (h.lastHeartbeat > 0 && !seen.contains(name)) servers.add(serverStatus(name));
    });
    out.put("servers", servers);
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // Liste et fiche joueur
  // ---------------------------------------------------------------------------------------------

  public Map<String, Object> players(String query, String sort, int page, int size) throws Exception {
    String order =
        switch (sort == null ? "" : sort) {
          case "name" -> "name COLLATE NOCASE ASC";
          case "first" -> "first_seen DESC";
          case "playtime" -> "playtime_ms DESC";
          case "sessions" -> "sessions DESC";
          default -> "last_seen DESC";
        };
    String q = query == null ? "" : query.trim();
    Set<String> online = new HashSet<>();
    for (Player p : proxy.getAllPlayers()) online.add(p.getUniqueId().toString());
    return db.read(
            c -> {
              String where = q.isEmpty() ? "" : " WHERE name LIKE ? ESCAPE '\\' OR uuid = ?";
              int total;
              try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM players" + where)) {
                bindSearch(ps, q);
                try (ResultSet rs = ps.executeQuery()) {
                  total = rs.next() ? rs.getInt(1) : 0;
                }
              }
              List<Map<String, Object>> rows = new ArrayList<>();
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "SELECT uuid, name, first_seen, last_seen, playtime_ms, sessions, version,"
                          + " last_server FROM players" + where + " ORDER BY " + order + " LIMIT ? OFFSET ?")) {
                int i = bindSearch(ps, q);
                ps.setInt(i, size);
                ps.setInt(i + 1, page * size);
                try (ResultSet rs = ps.executeQuery()) {
                  while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("uuid", rs.getString(1));
                    r.put("name", rs.getString(2));
                    r.put("firstSeen", rs.getLong(3));
                    r.put("lastSeen", rs.getLong(4));
                    r.put("playtimeMs", rs.getLong(5));
                    r.put("sessions", rs.getInt(6));
                    r.put("version", rs.getString(7));
                    r.put("lastServer", rs.getString(8));
                    r.put("online", online.contains(rs.getString(1)));
                    rows.add(r);
                  }
                }
              }
              LocalDate today = days.today();
              for (Map<String, Object> r : rows) {
                r.put("streak", currentStreak(activeDays(c, (String) r.get("uuid"), today.minusDays(400)), today));
              }
              Map<String, Object> out = new LinkedHashMap<>();
              out.put("total", total);
              out.put("page", page);
              out.put("size", size);
              out.put("rows", rows);
              return out;
            })
        .get(30, TimeUnit.SECONDS);
  }

  private static int bindSearch(PreparedStatement ps, String q) throws SQLException {
    if (q.isEmpty()) return 1;
    String escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    ps.setString(1, "%" + escaped + "%");
    ps.setString(2, q);
    return 3;
  }

  public Map<String, Object> player(String uuid) throws Exception {
    Optional<Player> online;
    try {
      online = proxy.getPlayer(UUID.fromString(uuid));
    } catch (IllegalArgumentException e) {
      return null;
    }
    return db.read(
            c -> {
              Map<String, Object> out = new LinkedHashMap<>();
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "SELECT uuid, name, first_seen, last_seen, playtime_ms, sessions, version, brand,"
                          + " last_server FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                  if (!rs.next()) return null;
                  out.put("uuid", rs.getString(1));
                  out.put("name", rs.getString(2));
                  out.put("firstSeen", rs.getLong(3));
                  out.put("lastSeen", rs.getLong(4));
                  out.put("playtimeMs", rs.getLong(5));
                  out.put("sessions", rs.getInt(6));
                  out.put("version", rs.getString(7));
                  out.put("brand", normalizeBrand(rs.getString(8)));
                  out.put("lastServer", rs.getString(9));
                }
              }
              out.put("online", online.isPresent());
              out.put(
                  "currentServer",
                  online.flatMap(Player::getCurrentServer).map(s -> s.getServerInfo().getName()).orElse(null));
              LocalDate today = days.today();
              TreeSet<LocalDate> set = activeDays(c, uuid, today.minusDays(400));
              out.put("streak", currentStreak(set, today));
              out.put("bestStreak", bestStreak(set));
              out.put("activeDays", set.size());

              List<Map<String, Object>> calendar = new ArrayList<>();
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "SELECT day, playtime_ms FROM daily_activity WHERE uuid = ? AND day >= ? ORDER BY day")) {
                ps.setString(1, uuid);
                ps.setString(2, today.minusDays(111).toString());
                try (ResultSet rs = ps.executeQuery()) {
                  while (rs.next()) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("day", rs.getString(1));
                    d.put("playtimeMs", rs.getLong(2));
                    calendar.add(d);
                  }
                }
              }
              out.put("calendar", calendar);

              List<Map<String, Object>> sessions = new ArrayList<>();
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "SELECT start, end, version FROM sessions WHERE uuid = ? ORDER BY start DESC LIMIT 15")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                  while (rs.next()) {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("start", rs.getLong(1));
                    long end = rs.getLong(2);
                    s.put("end", rs.wasNull() ? null : end);
                    s.put("version", rs.getString(3));
                    sessions.add(s);
                  }
                }
              }
              out.put("recentSessions", sessions);

              try (PreparedStatement ps =
                  c.prepareStatement("SELECT earned, spent FROM economy_players WHERE uuid = ?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                  boolean found = rs.next();
                  out.put("earned", found ? round(rs.getDouble(1), 2) : 0.0);
                  out.put("spent", found ? round(rs.getDouble(2), 2) : 0.0);
                }
              }
              return out;
            })
        .get(30, TimeUnit.SECONDS);
  }

  private static TreeSet<LocalDate> activeDays(Connection c, String uuid, LocalDate from) throws SQLException {
    TreeSet<LocalDate> set = new TreeSet<>();
    try (PreparedStatement ps =
        c.prepareStatement("SELECT day FROM daily_activity WHERE uuid = ? AND day >= ?")) {
      ps.setString(1, uuid);
      ps.setString(2, from.toString());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) set.add(LocalDate.parse(rs.getString(1)));
      }
    }
    return set;
  }

  // ---------------------------------------------------------------------------------------------
  // Utilitaires
  // ---------------------------------------------------------------------------------------------

  /** Nombre de jours consécutifs joués, se terminant aujourd'hui ou hier (streak encore en cours). */
  static int currentStreak(TreeSet<LocalDate> set, LocalDate today) {
    if (set == null || set.isEmpty()) return 0;
    LocalDate cursor = set.contains(today) ? today : today.minusDays(1);
    int streak = 0;
    while (set.contains(cursor)) {
      streak++;
      cursor = cursor.minusDays(1);
    }
    return streak;
  }

  static int bestStreak(TreeSet<LocalDate> set) {
    int best = 0;
    int run = 0;
    LocalDate prev = null;
    for (LocalDate d : set) {
      run = prev != null && prev.plusDays(1).equals(d) ? run + 1 : 1;
      best = Math.max(best, run);
      prev = d;
    }
    return best;
  }

  private static String normalizeBrand(String brand) {
    if (brand == null || brand.isBlank()) return "Inconnu";
    String b = brand.toLowerCase(Locale.ROOT);
    if (b.contains("lunar")) return "Lunar Client";
    if (b.contains("badlion")) return "Badlion";
    if (b.contains("feather")) return "Feather";
    if (b.contains("labymod")) return "LabyMod";
    if (b.contains("fabric")) return "Fabric";
    if (b.contains("neoforge")) return "NeoForge";
    if (b.contains("forge")) return "Forge";
    if (b.contains("quilt")) return "Quilt";
    if (b.contains("vanilla")) return "Vanilla";
    return brand.length() > 24 ? brand.substring(0, 24) : brand;
  }

  private static List<Map<String, Object>> topEntries(Map<String, Integer> map, String key, int limit) {
    List<Map<String, Object>> rows = new ArrayList<>();
    List<Map.Entry<String, Integer>> sorted = new ArrayList<>(map.entrySet());
    sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
    int others = 0;
    for (int i = 0; i < sorted.size(); i++) {
      if (i < limit) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put(key, sorted.get(i).getKey());
        r.put("players", sorted.get(i).getValue());
        rows.add(r);
      } else {
        others += sorted.get(i).getValue();
      }
    }
    if (others > 0) {
      Map<String, Object> r = new LinkedHashMap<>();
      r.put(key, "Autres");
      r.put("players", others);
      rows.add(r);
    }
    return rows;
  }

  private static void inc(Map<LocalDate, Map<String, Object>> daily, LocalDate day, String key, int by) {
    Map<String, Object> row = daily.get(day);
    if (row != null) row.put(key, ((Number) row.get(key)).intValue() + by);
  }

  private static void incD(Map<LocalDate, Map<String, Object>> daily, LocalDate day, String key, double by) {
    Map<String, Object> row = daily.get(day);
    if (row != null) row.put(key, round(((Number) row.get(key)).doubleValue() + by, 2));
  }

  private static void set(Map<LocalDate, Map<String, Object>> daily, LocalDate day, String key, Object value) {
    Map<String, Object> row = daily.get(day);
    if (row != null) row.put(key, value);
  }

  private static Object get(Map<LocalDate, Map<String, Object>> daily, LocalDate day, String key) {
    Map<String, Object> row = daily.get(day);
    return row == null ? 0 : row.get(key);
  }

  static double round(double v, int decimals) {
    if (!Double.isFinite(v)) return 0;
    double f = Math.pow(10, decimals);
    return Math.round(v * f) / f;
  }
}
