package fr.astra.nexora.analytics.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import fr.astra.nexora.analytics.velocity.command.AnalyticsCommand;
import fr.astra.nexora.analytics.velocity.config.AnalyticsConfig;
import fr.astra.nexora.analytics.velocity.stats.StatsService;
import fr.astra.nexora.analytics.velocity.storage.Database;
import fr.astra.nexora.analytics.velocity.storage.Days;
import fr.astra.nexora.analytics.velocity.tracker.IngestService;
import fr.astra.nexora.analytics.velocity.tracker.PlayerTracker;
import fr.astra.nexora.analytics.velocity.tracker.ServerMonitor;
import fr.astra.nexora.analytics.velocity.web.AuthManager;
import fr.astra.nexora.analytics.velocity.web.WebServer;
import java.nio.file.Path;
import org.slf4j.Logger;

@Plugin(
    id = "nexora-analytics",
    name = "Nexora Analytics",
    version = "1.0.0",
    description = "Dashboard web d'analytics pour le réseau Nexora",
    authors = {"Astra"})
public final class NexoraAnalyticsVelocity {
  private final ProxyServer proxy;
  private final Logger logger;
  private final Path dataDir;

  private Database database;
  private PlayerTracker tracker;
  private WebServer web;

  @Inject
  public NexoraAnalyticsVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
    this.proxy = proxy;
    this.logger = logger;
    this.dataDir = dataDir;
  }

  @Subscribe
  public void onInit(ProxyInitializeEvent event) {
    try {
      AnalyticsConfig config = AnalyticsConfig.load(dataDir);
      Days days = new Days(config.zone());
      database = new Database(dataDir.resolve("analytics.db"), logger);

      tracker = new PlayerTracker(proxy, database, days);
      tracker.init();
      ServerMonitor monitor = new ServerMonitor(proxy, database, config, tracker);
      IngestService ingest = new IngestService(database, days, monitor);
      StatsService stats = new StatsService(proxy, database, days, config, tracker, monitor);
      AuthManager auth = new AuthManager(config.tokenMinutes(), config.sessionHours());

      proxy.getEventManager().register(this, tracker);
      monitor.start(this);

      CommandMeta meta =
          proxy.getCommandManager().metaBuilder("analytics").aliases("nexoraanalytics", "nadash").plugin(this).build();
      proxy.getCommandManager().register(meta, new AnalyticsCommand(config, auth));

      web = new WebServer(config, auth, stats, ingest, logger);
      web.start();
      logger.info("Nexora Analytics activé. Utilisez /analytics en jeu pour ouvrir le dashboard.");
      if (AnalyticsConfig.isLocal(config.publicUrl())) {
        logger.warn("public-url vaut {} : ce lien ne fonctionnera pas depuis votre navigateur.", config.publicUrl());
        logger.warn("Renseignez public-url=http://<IP de votre serveur>:{} dans plugins/nexora-analytics/config.properties"
            + " (le port doit être ouvert chez votre hébergeur), puis redémarrez le proxy.", config.port());
      }
      logger.info("Secret à copier dans le config.yml des serveurs Paper : tapez 'analytics secret' dans cette console.");
    } catch (Exception e) {
      logger.error("Impossible de démarrer Nexora Analytics", e);
    }
  }

  @Subscribe
  public void onShutdown(ProxyShutdownEvent event) {
    if (web != null) web.stop();
    if (tracker != null) tracker.shutdown();
    if (database != null) database.close();
  }
}
