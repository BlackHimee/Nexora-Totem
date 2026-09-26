package fr.astra.nexora.analytics.velocity.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.astra.nexora.analytics.velocity.config.AnalyticsConfig;
import fr.astra.nexora.analytics.velocity.stats.StatsService;
import fr.astra.nexora.analytics.velocity.tracker.IngestService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;

/** Serveur HTTP embarqué : pages du dashboard, API JSON et réception des données Paper. */
public final class WebServer {
  private static final String COOKIE = "nexora_session";
  private static final int MAX_INGEST_BYTES = 2 * 1024 * 1024;
  private static final Set<Integer> RANGES = Set.of(7, 14, 30, 90);
  private static final Map<String, String> ASSETS =
      Map.of(
          "app.css", "text/css; charset=utf-8",
          "app.js", "application/javascript; charset=utf-8",
          "chart.umd.js", "application/javascript; charset=utf-8",
          "favicon.svg", "image/svg+xml");

  private static final String CSP =
      "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com;"
          + " font-src 'self' https://fonts.gstatic.com; img-src 'self' data: https://mc-heads.net;"
          + " connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

  private final AnalyticsConfig config;
  private final AuthManager auth;
  private final StatsService stats;
  private final IngestService ingest;
  private final Logger logger;
  private final Gson gson = new GsonBuilder().serializeNulls().create();
  private final Map<String, byte[]> assetCache = new HashMap<>();
  private HttpServer server;
  private ExecutorService executor;

  public WebServer(
      AnalyticsConfig config, AuthManager auth, StatsService stats, IngestService ingest, Logger logger) {
    this.config = config;
    this.auth = auth;
    this.stats = stats;
    this.ingest = ingest;
    this.logger = logger;
  }

  public void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 64);
    executor =
        Executors.newFixedThreadPool(
            4,
            r -> {
              Thread t = new Thread(r, "NexoraAnalytics-Web");
              t.setDaemon(true);
              return t;
            });
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
    logger.info("Dashboard disponible sur {} (écoute sur {}:{})", config.publicUrl(), config.bindAddress(), config.port());
  }

  public void stop() {
    if (server != null) server.stop(1);
    if (executor != null) executor.shutdownNow();
  }

  private void handle(HttpExchange ex) {
    try {
      securityHeaders(ex);
      String path = ex.getRequestURI().getPath();
      String method = ex.getRequestMethod();
      if (path.equals("/api/ingest")) {
        handleIngest(ex, method);
        return;
      }
      if (path.equals("/") || path.equals("/index.html")) {
        sendAsset(ex, "index.html", "text/html; charset=utf-8");
        return;
      }
      if (path.startsWith("/assets/")) {
        String name = path.substring("/assets/".length());
        String type = ASSETS.get(name);
        if (type == null) sendJson(ex, 404, Map.of("error", "not_found"));
        else sendAsset(ex, name, type);
        return;
      }
      if (path.equals("/auth")) {
        handleAuth(ex);
        return;
      }
      if (path.startsWith("/api/")) {
        handleApi(ex, path, method);
        return;
      }
      sendJson(ex, 404, Map.of("error", "not_found"));
    } catch (Exception e) {
      logger.warn("Erreur lors du traitement de {}", ex.getRequestURI().getPath(), e);
      try {
        sendJson(ex, 500, Map.of("error", "internal"));
      } catch (Exception ignored) {
        // réponse déjà partiellement envoyée
      }
    } finally {
      ex.close();
    }
  }

  private void handleAuth(HttpExchange ex) throws IOException {
    String token = query(ex).get("token");
    AuthManager.Session session = auth.exchange(token);
    if (session == null) {
      redirect(ex, "/?error=token");
      return;
    }
    boolean secure = config.publicUrl().startsWith("https://");
    ex.getResponseHeaders()
        .add(
            "Set-Cookie",
            COOKIE + "=" + session.id() + "; Path=/; HttpOnly; SameSite=Strict; Max-Age="
                + (auth.sessionTtlMs() / 1000) + (secure ? "; Secure" : ""));
    logger.info("Connexion au dashboard : {}", session.admin());
    redirect(ex, "/");
  }

  private void handleApi(HttpExchange ex, String path, String method) throws Exception {
    AuthManager.Session session = auth.session(cookie(ex));
    if (path.equals("/api/me")) {
      Map<String, Object> me = new LinkedHashMap<>();
      me.put("authenticated", session != null);
      me.put("admin", session == null ? null : session.admin());
      me.put("expiresAt", session == null ? null : session.expiresAt());
      sendJson(ex, 200, me);
      return;
    }
    if (session == null) {
      sendJson(ex, 401, Map.of("error", "unauthorized"));
      return;
    }
    Map<String, String> q = query(ex);
    switch (path) {
      case "/api/logout" -> {
        if (!"POST".equals(method)) {
          sendJson(ex, 405, Map.of("error", "method"));
          return;
        }
        auth.revoke(session.id());
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
        sendJson(ex, 200, Map.of("ok", true));
      }
      case "/api/dashboard" -> {
        int range = parseInt(q.get("range"), 30);
        if (!RANGES.contains(range)) range = 30;
        sendJson(ex, 200, stats.dashboard(range));
      }
      case "/api/live" -> sendJson(ex, 200, stats.live());
      case "/api/players" -> {
        int page = Math.max(0, parseInt(q.get("page"), 0));
        String search = q.getOrDefault("q", "");
        if (search.length() > 36) search = search.substring(0, 36);
        sendJson(ex, 200, stats.players(search, q.get("sort"), page, 25));
      }
      case "/api/player" -> {
        Map<String, Object> player = stats.player(q.getOrDefault("uuid", ""));
        if (player == null) sendJson(ex, 404, Map.of("error", "not_found"));
        else sendJson(ex, 200, player);
      }
      default -> sendJson(ex, 404, Map.of("error", "not_found"));
    }
  }

  private void handleIngest(HttpExchange ex, String method) throws IOException {
    if (!"POST".equals(method)) {
      sendJson(ex, 405, Map.of("error", "method"));
      return;
    }
    String secret = ex.getRequestHeaders().getFirst("X-Nexora-Secret");
    if (secret == null
        || !MessageDigest.isEqual(
            secret.getBytes(StandardCharsets.UTF_8), config.ingestSecret().getBytes(StandardCharsets.UTF_8))) {
      sendJson(ex, 403, Map.of("error", "forbidden"));
      return;
    }
    byte[] body = readLimited(ex.getRequestBody(), MAX_INGEST_BYTES);
    if (body == null) {
      sendJson(ex, 413, Map.of("error", "too_large"));
      return;
    }
    JsonObject payload;
    try {
      payload = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
    } catch (Exception e) {
      sendJson(ex, 400, Map.of("error", "bad_json"));
      return;
    }
    ingest.ingest(payload);
    sendJson(ex, 200, Map.of("ok", true));
  }

  // ---------------------------------------------------------------------------------------------

  private void securityHeaders(HttpExchange ex) {
    var h = ex.getResponseHeaders();
    h.set("X-Content-Type-Options", "nosniff");
    h.set("X-Frame-Options", "DENY");
    h.set("Referrer-Policy", "no-referrer");
    h.set("Content-Security-Policy", CSP);
    h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
  }

  private void sendAsset(HttpExchange ex, String name, String type) throws IOException {
    byte[] data;
    synchronized (assetCache) {
      data = assetCache.get(name);
      if (data == null) {
        try (InputStream in = WebServer.class.getResourceAsStream("/web/" + name)) {
          if (in == null) {
            sendJson(ex, 404, Map.of("error", "not_found"));
            return;
          }
          data = in.readAllBytes();
        }
        assetCache.put(name, data);
      }
    }
    ex.getResponseHeaders().set("Content-Type", type);
    ex.getResponseHeaders()
        .set("Cache-Control", name.equals("chart.umd.js") ? "public, max-age=86400" : "no-cache");
    if ("HEAD".equals(ex.getRequestMethod())) {
      ex.sendResponseHeaders(200, -1);
      return;
    }
    ex.sendResponseHeaders(200, data.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(data);
    }
  }

  private void sendJson(HttpExchange ex, int status, Object body) throws IOException {
    byte[] data = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    ex.getResponseHeaders().set("Cache-Control", "no-store");
    ex.sendResponseHeaders(status, data.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(data);
    }
  }

  private void redirect(HttpExchange ex, String location) throws IOException {
    ex.getResponseHeaders().set("Location", location);
    ex.getResponseHeaders().set("Cache-Control", "no-store");
    ex.sendResponseHeaders(302, -1);
  }

  private static String cookie(HttpExchange ex) {
    List<String> headers = ex.getRequestHeaders().get("Cookie");
    if (headers == null) return null;
    for (String header : headers) {
      for (String part : header.split(";")) {
        String p = part.trim();
        if (p.startsWith(COOKIE + "=")) return p.substring(COOKIE.length() + 1);
      }
    }
    return null;
  }

  private static Map<String, String> query(HttpExchange ex) {
    Map<String, String> map = new HashMap<>();
    String raw = ex.getRequestURI().getRawQuery();
    if (raw == null) return map;
    for (String pair : raw.split("&")) {
      int i = pair.indexOf('=');
      if (i <= 0) continue;
      map.put(
          URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
    }
    return map;
  }

  private static int parseInt(String s, int def) {
    try {
      return s == null ? def : Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return def;
    }
  }

  private static byte[] readLimited(InputStream in, int max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int total = 0;
    int n;
    while ((n = in.read(buf)) != -1) {
      total += n;
      if (total > max) return null;
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }
}
