package fr.astra.nexora.analytics.paper.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Mesure l'argent gagné et dépensé en suivant la variation du solde Vault des joueurs connectés.
 *
 * <p>Vault ne publie aucun événement de transaction : le solde de chaque joueur en ligne est donc
 * relevé périodiquement (et à sa déconnexion). Une hausse est comptée comme argent gagné, une
 * baisse comme argent dépensé. Vault est appelé par réflexion pour ne pas en dépendre à la
 * compilation ; le collecteur reste inactif si aucun plugin d'économie n'est présent.
 *
 * <p>Toutes les méthodes s'exécutent sur le thread principal du serveur.
 */
public final class EconomyCollector implements Listener {
  private final Logger logger;
  private final Map<UUID, Double> baseline = new HashMap<>();
  private final Map<UUID, double[]> deltas = new HashMap<>();
  private Object economy;
  private Method getBalance;
  private boolean warned;
  private volatile String status = "non vérifié";

  public EconomyCollector(Logger logger) {
    this.logger = logger;
  }

  private boolean ready() {
    if (economy != null) return true;
    if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
      status = "Vault absent sur ce serveur";
      return false;
    }
    try {
      Class<?> type = Class.forName("net.milkbowl.vault.economy.Economy");
      RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(type);
      if (rsp == null) {
        status = "Vault présent mais aucun plugin d'économie enregistré";
        return false;
      }
      getBalance = type.getMethod("getBalance", OfflinePlayer.class);
      economy = rsp.getProvider();
      status = "ok (" + rsp.getPlugin().getName() + ")";
      logger.info("Économie Vault détectée : " + rsp.getPlugin().getName());
      return true;
    } catch (Throwable t) {
      status = "erreur : " + t;
      if (!warned) {
        warned = true;
        logger.warning("Vault présent mais économie inaccessible : " + t.getMessage());
      }
      return false;
    }
  }

  private Double balance(Player player) {
    try {
      Object value = getBalance.invoke(economy, player);
      return value instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : null;
    } catch (Throwable t) {
      return null;
    }
  }

  public String status() {
    return status;
  }

  /** Relève le solde de tous les joueurs connectés. */
  public void sample() {
    if (!ready()) return;
    for (Player player : Bukkit.getOnlinePlayers()) record(player);
  }

  private void record(Player player) {
    Double now = balance(player);
    if (now == null) return;
    Double before = baseline.put(player.getUniqueId(), now);
    if (before == null) return; // premier relevé : sert de référence
    double diff = now - before;
    if (Math.abs(diff) < 0.0001) return;
    double[] d = deltas.computeIfAbsent(player.getUniqueId(), k -> new double[2]);
    if (diff > 0) d[0] += diff;
    else d[1] += -diff;
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    if (economy != null) record(event.getPlayer());
    baseline.remove(event.getPlayer().getUniqueId());
  }

  /** Renvoie puis réinitialise les gains/dépenses accumulés depuis le dernier envoi. */
  public JsonArray drain() {
    JsonArray array = new JsonArray();
    for (var e : deltas.entrySet()) {
      JsonObject o = new JsonObject();
      o.addProperty("uuid", e.getKey().toString());
      o.addProperty("earned", e.getValue()[0]);
      o.addProperty("spent", e.getValue()[1]);
      array.add(o);
    }
    deltas.clear();
    return array;
  }
}
