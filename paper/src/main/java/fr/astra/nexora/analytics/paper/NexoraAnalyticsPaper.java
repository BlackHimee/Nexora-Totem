package fr.astra.nexora.analytics.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.astra.nexora.analytics.paper.collect.EconomyCollector;
import fr.astra.nexora.analytics.paper.collect.ErrorCollector;
import fr.astra.nexora.analytics.paper.collect.IslandCollector;
import fr.astra.nexora.analytics.paper.collect.ResourceCollector;
import fr.astra.nexora.analytics.paper.net.ProxyClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Collecteur Paper de Nexora Analytics : relève périodiquement les données du serveur et les envoie
 * au plugin du proxy Velocity, qui les stocke et les affiche dans le dashboard.
 */
public final class NexoraAnalyticsPaper extends JavaPlugin {
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

  private ProxyClient client;
  private EconomyCollector economy;
  private ResourceCollector resources;
  private IslandCollector islands;
  private ErrorCollector errors;
  private String serverName;
  private Path runningMarker;
  private JsonObject crash;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    serverName = getConfig().getString("server-name", "").trim();
    String secret = getConfig().getString("secret", "").trim();
    if (serverName.isEmpty()) {
      serverName = "paper-" + Bukkit.getPort();
      getLogger().warning("'server-name' n'est pas renseigné dans config.yml : utilisation de '" + serverName
          + "'. Renseignez le nom du serveur tel que déclaré dans velocity.toml.");
    }
    if (secret.isEmpty()) {
      getLogger().severe("'secret' n'est pas renseigné dans config.yml : aucune donnée ne sera envoyée. "
          + "Copiez 'ingest-secret' depuis la configuration du proxy puis redémarrez.");
    }

    detectCrash();

    client = new ProxyClient(getLogger(), getConfig().getString("proxy-url", "http://127.0.0.1:8765"), secret);
    islands = new IslandCollector(getLogger());
    if (getConfig().getBoolean("collect.economy", true)) {
      economy = new EconomyCollector(getLogger());
      getServer().getPluginManager().registerEvents(economy, this);
      long period = Math.max(5, getConfig().getInt("collect.economy-sample-seconds", 30)) * 20L;
      Bukkit.getScheduler().runTaskTimer(this, economy::sample, 40L, period);
    }
    if (getConfig().getBoolean("collect.resources", true)) {
      resources = new ResourceCollector();
      getServer().getPluginManager().registerEvents(resources, this);
    }
    if (getConfig().getBoolean("collect.errors", true)) {
      try {
        errors = new ErrorCollector();
        errors.install();
      } catch (Throwable t) {
        errors = null;
        getLogger().warning("Capture des erreurs console indisponible : " + t);
      }
    }

    if (!secret.isEmpty()) {
      long period = Math.max(15, getConfig().getInt("send-interval-seconds", 60)) * 20L;
      // Premier envoi rapide pour que le serveur apparaisse tout de suite dans le dashboard.
      Bukkit.getScheduler().runTaskTimer(this, this::send, 100L, period);
    }
    var command = getCommand("nanalytics");
    if (command != null) command.setExecutor(this::onStatusCommand);
    getLogger().info("Collecte active pour le serveur '" + serverName + "'. Diagnostic : /nanalytics");
  }

  @Override
  public void onDisable() {
    if (client != null && getConfig().getString("secret", "").trim().length() > 0) {
      try {
        send();
      } catch (Throwable ignored) {
        // arrêt en cours : l'envoi final est au mieux
      }
    }
    if (errors != null) errors.uninstall();
    try {
      if (runningMarker != null) Files.deleteIfExists(runningMarker);
    } catch (IOException ignored) {
      // sans conséquence : au pire un faux « arrêt anormal » au prochain démarrage
    }
  }

  /** {@code /nanalytics [test]} : état du collecteur, pour diagnostiquer une installation. */
  private boolean onStatusCommand(CommandSender sender, Command command, String label, String[] args) {
    boolean secretSet = !getConfig().getString("secret", "").trim().isEmpty();
    if (args.length > 0 && args[0].equalsIgnoreCase("test")) {
      if (!secretSet) {
        sender.sendMessage("§c[NexoraAnalytics] Impossible : 'secret' est vide dans config.yml.");
        return true;
      }
      send();
      sender.sendMessage("§a[NexoraAnalytics] Envoi de test lancé. Retapez §f/nanalytics§a dans 2 secondes pour voir le résultat.");
      return true;
    }
    String proxyState = client.lastResult();
    String color = proxyState.startsWith("OK") ? "§a" : proxyState.startsWith("aucun") ? "§e" : "§c";
    sender.sendMessage("§d§lNexora§b§lAnalytics §8» §7Diagnostic du collecteur");
    sender.sendMessage("§7Nom du serveur : §f" + serverName + " §8(doit être identique au nom dans velocity.toml)");
    sender.sendMessage("§7Proxy : §f" + client.endpoint());
    sender.sendMessage("§7Secret : " + (secretSet ? "§arenseigné" : "§cVIDE — copiez 'ingest-secret' du proxy"));
    sender.sendMessage("§7Dernier envoi : " + color + proxyState
        + (client.lastAttempt() > 0 ? " §8(" + TIME.format(Instant.ofEpochMilli(client.lastAttempt())) + ")" : ""));
    if (client.pending() > 1) sender.sendMessage("§7Lots en attente d'envoi : §e" + client.pending());
    sender.sendMessage("§7Économie : §f" + (economy == null ? "désactivée" : economy.status()));
    sender.sendMessage("§7Îles NexoraMc : §f" + islands.status());
    sender.sendMessage("§7Erreurs console : §f" + (errors != null ? "capturées" : "non capturées"));
    sender.sendMessage("§8Astuce : /nanalytics test force un envoi immédiat.");
    return true;
  }

  /**
   * Un fichier témoin est créé au démarrage et supprimé à l'arrêt normal. S'il existe encore au
   * démarrage suivant, le serveur a été arrêté brutalement (crash, kill, coupure) : on le signale.
   */
  private void detectCrash() {
    runningMarker = getDataFolder().toPath().resolve(".running");
    try {
      if (Files.exists(runningMarker)) {
        FileTime last = Files.getLastModifiedTime(runningMarker);
        crash = new JsonObject();
        crash.addProperty("message", "Arrêt anormal détecté (crash, kill ou coupure)");
        crash.addProperty(
            "details",
            "Le serveur ne s'est pas arrêté proprement lors de sa précédente exécution.\n"
                + "Dernier signe de vie : " + TIME.format(last.toInstant()));
        getLogger().warning("Le serveur ne s'était pas arrêté proprement : incident signalé au dashboard.");
      }
      Files.createDirectories(runningMarker.getParent());
      Files.writeString(runningMarker, String.valueOf(System.currentTimeMillis()));
    } catch (IOException e) {
      getLogger().warning("Fichier témoin inaccessible : " + e.getMessage());
    }
  }

  /** Construit le lot de données (thread principal) et le confie au client HTTP (asynchrone). */
  private void send() {
    JsonObject payload = new JsonObject();
    payload.addProperty("server", serverName);
    payload.addProperty("pluginVersion", getPluginMeta().getVersion());
    payload.addProperty("ts", System.currentTimeMillis());
    payload.add("health", health());
    if (crash != null) {
      payload.add("crash", crash);
      crash = null;
    }
    if (economy != null) {
      economy.sample();
      JsonArray eco = economy.drain();
      if (!eco.isEmpty()) payload.add("economy", eco);
    }
    if (resources != null) {
      JsonArray res = resources.drain();
      if (!res.isEmpty()) payload.add("resources", res);
    }
    if (getConfig().getBoolean("collect.islands", true)) {
      JsonObject snapshot = islands.snapshot();
      if (snapshot != null) payload.add("islands", snapshot);
    }
    if (errors != null) {
      JsonArray err = errors.drain();
      if (!err.isEmpty()) payload.add("errors", err);
    }
    JsonObject diagnostics = new JsonObject();
    diagnostics.addProperty("economy", economy == null ? "désactivé (collect.economy)" : economy.status());
    diagnostics.addProperty("islands",
        getConfig().getBoolean("collect.islands", true) ? islands.status() : "désactivé (collect.islands)");
    diagnostics.addProperty("errors", errors != null ? "ok" : "indisponible");
    payload.add("diagnostics", diagnostics);
    client.enqueue(payload);
    try {
      if (runningMarker != null) Files.setLastModifiedTime(runningMarker, FileTime.from(Instant.now()));
    } catch (IOException ignored) {
      // non bloquant
    }
  }

  private JsonObject health() {
    JsonObject h = new JsonObject();
    double[] tps = Bukkit.getTPS();
    h.addProperty("tps", Math.min(20.0, tps.length > 0 ? tps[0] : 20.0));
    h.addProperty("mspt", Bukkit.getAverageTickTime());
    Runtime rt = Runtime.getRuntime();
    h.addProperty("usedMemory", rt.totalMemory() - rt.freeMemory());
    h.addProperty("maxMemory", rt.maxMemory());
    int chunks = 0;
    int entities = 0;
    for (World world : Bukkit.getWorlds()) {
      chunks += world.getChunkCount();
      entities += world.getEntityCount();
    }
    h.addProperty("loadedChunks", chunks);
    h.addProperty("entities", entities);
    h.addProperty("players", Bukkit.getOnlinePlayers().size());
    h.addProperty("version", Bukkit.getName() + " " + Bukkit.getMinecraftVersion());
    return h;
  }
}
