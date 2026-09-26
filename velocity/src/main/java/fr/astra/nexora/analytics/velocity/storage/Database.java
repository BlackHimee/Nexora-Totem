package fr.astra.nexora.analytics.velocity.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/**
 * Accès à la base SQLite du plugin.
 *
 * <p>SQLite n'accepte qu'un seul écrivain à la fois : toutes les opérations (lectures comprises)
 * passent donc par un unique thread dédié, ce qui évite tout verrouillage et garantit l'ordre des
 * écritures (une déconnexion est toujours traitée après la connexion correspondante).
 */
public final class Database {
  @FunctionalInterface
  public interface SqlWork<T> {
    T run(Connection c) throws SQLException;
  }

  @FunctionalInterface
  public interface SqlTask {
    void run(Connection c) throws SQLException;
  }

  private final Logger logger;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "NexoraAnalytics-DB");
            t.setDaemon(true);
            return t;
          });
  private Connection connection;

  public Database(Path file, Logger logger) throws Exception {
    this.logger = logger;
    Class.forName("org.sqlite.JDBC");
    String url = "jdbc:sqlite:" + file.toAbsolutePath();
    executor
        .submit(
            () -> {
              connection = DriverManager.getConnection(url);
              try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA foreign_keys=OFF");
              }
              createSchema();
              return null;
            })
        .get(30, TimeUnit.SECONDS);
  }

  private void createSchema() throws SQLException {
    String[] ddl = {
      """
      CREATE TABLE IF NOT EXISTS players (
        uuid TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        first_seen INTEGER NOT NULL,
        last_seen INTEGER NOT NULL,
        playtime_ms INTEGER NOT NULL DEFAULT 0,
        sessions INTEGER NOT NULL DEFAULT 0,
        version TEXT,
        brand TEXT,
        last_server TEXT
      )""",
      "CREATE INDEX IF NOT EXISTS idx_players_name ON players(name COLLATE NOCASE)",
      """
      CREATE TABLE IF NOT EXISTS sessions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        uuid TEXT NOT NULL,
        start INTEGER NOT NULL,
        end INTEGER,
        version TEXT
      )""",
      "CREATE INDEX IF NOT EXISTS idx_sessions_start ON sessions(start)",
      "CREATE INDEX IF NOT EXISTS idx_sessions_uuid ON sessions(uuid)",
      """
      CREATE TABLE IF NOT EXISTS daily_activity (
        uuid TEXT NOT NULL,
        day TEXT NOT NULL,
        playtime_ms INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (uuid, day)
      )""",
      "CREATE INDEX IF NOT EXISTS idx_daily_day ON daily_activity(day)",
      """
      CREATE TABLE IF NOT EXISTS server_daily (
        day TEXT NOT NULL,
        server TEXT NOT NULL,
        playtime_ms INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (day, server)
      )""",
      """
      CREATE TABLE IF NOT EXISTS samples (
        ts INTEGER PRIMARY KEY,
        online INTEGER NOT NULL,
        servers TEXT
      )""",
      """
      CREATE TABLE IF NOT EXISTS economy_daily (
        day TEXT NOT NULL,
        server TEXT NOT NULL,
        earned REAL NOT NULL DEFAULT 0,
        spent REAL NOT NULL DEFAULT 0,
        PRIMARY KEY (day, server)
      )""",
      """
      CREATE TABLE IF NOT EXISTS economy_players (
        uuid TEXT PRIMARY KEY,
        earned REAL NOT NULL DEFAULT 0,
        spent REAL NOT NULL DEFAULT 0
      )""",
      """
      CREATE TABLE IF NOT EXISTS resources_daily (
        day TEXT NOT NULL,
        action TEXT NOT NULL,
        material TEXT NOT NULL,
        count INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (day, action, material)
      )""",
      """
      CREATE TABLE IF NOT EXISTS islands (
        server TEXT NOT NULL,
        id TEXT NOT NULL,
        name TEXT,
        owner TEXT,
        border REAL NOT NULL DEFAULT 0,
        level INTEGER NOT NULL DEFAULT 1,
        members INTEGER NOT NULL DEFAULT 1,
        milestones INTEGER NOT NULL DEFAULT 0,
        objectives_done INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER,
        deleted_at INTEGER,
        updated_at INTEGER NOT NULL,
        PRIMARY KEY (server, id)
      )""",
      """
      CREATE TABLE IF NOT EXISTS island_events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        ts INTEGER NOT NULL,
        day TEXT NOT NULL,
        server TEXT NOT NULL,
        island_id TEXT NOT NULL,
        type TEXT NOT NULL,
        value REAL NOT NULL DEFAULT 0
      )""",
      "CREATE INDEX IF NOT EXISTS idx_island_events_day ON island_events(day)",
      """
      CREATE TABLE IF NOT EXISTS incidents (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        ts INTEGER NOT NULL,
        server TEXT NOT NULL,
        type TEXT NOT NULL,
        message TEXT NOT NULL,
        details TEXT
      )""",
      "CREATE INDEX IF NOT EXISTS idx_incidents_ts ON incidents(ts)",
      """
      CREATE TABLE IF NOT EXISTS peaks_daily (
        day TEXT PRIMARY KEY,
        peak INTEGER NOT NULL,
        ts INTEGER NOT NULL
      )""",
      """
      CREATE TABLE IF NOT EXISTS kv (
        key TEXT PRIMARY KEY,
        value TEXT
      )"""
    };
    try (Statement st = connection.createStatement()) {
      for (String sql : ddl) st.execute(sql);
    }
  }

  /** Exécute une tâche d'écriture en arrière-plan ; les erreurs sont journalisées. */
  public void write(SqlTask task) {
    executor.execute(
        () -> {
          try {
            connection.setAutoCommit(false);
            task.run(connection);
            connection.commit();
          } catch (Exception e) {
            try {
              connection.rollback();
            } catch (SQLException ignored) {
              // la connexion est déjà dans un état invalide : rien de plus à faire
            }
            logger.warn("Écriture SQLite échouée", e);
          } finally {
            try {
              connection.setAutoCommit(true);
            } catch (SQLException ignored) {
              // idem
            }
          }
        });
  }

  /** Exécute une lecture sur le thread de la base et renvoie son résultat. */
  public <T> CompletableFuture<T> read(SqlWork<T> work) {
    CompletableFuture<T> future = new CompletableFuture<>();
    executor.execute(
        () -> {
          try {
            future.complete(work.run(connection));
          } catch (Throwable t) {
            future.completeExceptionally(t);
          }
        });
    return future;
  }

  public void close() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        logger.warn("Des écritures SQLite n'ont pas pu être terminées avant l'arrêt.");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    try {
      if (connection != null) connection.close();
    } catch (SQLException e) {
      logger.warn("Fermeture SQLite échouée", e);
    }
  }
}
