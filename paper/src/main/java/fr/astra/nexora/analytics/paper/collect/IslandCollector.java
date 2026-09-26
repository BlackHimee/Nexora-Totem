package fr.astra.nexora.analytics.paper.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Lit l'état des îles du plugin NexoraMc (github.com/BlackHimee/NexoraMC).
 *
 * <p>NexoraMc ne publie pas d'événements : on relève donc un instantané complet de ses îles à
 * chaque envoi (identifiant, nom, propriétaire, taille de bordure, niveau, membres, succès et
 * objectifs du jour). Le proxy compare ensuite les instantanés successifs pour en déduire les
 * créations d'îles et les chunks débloqués. L'accès se fait par réflexion via les accesseurs
 * publics de NexoraMc ({@code getIslands().all()}, {@code getLevels().level(ile)}), sans
 * dépendance de compilation.
 */
public final class IslandCollector {
  private final Logger logger;
  private boolean warned;
  private volatile String status = "non vérifié";

  public IslandCollector(Logger logger) {
    this.logger = logger;
  }

  /** Instantané des îles, ou {@code null} si NexoraMc n'est pas installé sur ce serveur. */
  public JsonObject snapshot() {
    Plugin plugin = Bukkit.getPluginManager().getPlugin("NexoraMc");
    if (plugin == null) {
      status = "NexoraMc absent sur ce serveur";
      return null;
    }
    if (!plugin.isEnabled()) {
      status = "NexoraMc installé mais désactivé (erreur au démarrage ?)";
      return null;
    }
    try {
      Object manager = call(plugin, "getIslands");
      Object levels = call(plugin, "getLevels");
      Collection<?> islands = (Collection<?>) call(manager, "all");
      JsonArray list = new JsonArray();
      Method level = null;
      for (Object island : islands) {
        if (level == null && levels != null) level = findMethod(levels.getClass(), "level", island.getClass());
        JsonObject o = new JsonObject();
        o.addProperty("id", String.valueOf(call(island, "id")));
        Object name = call(island, "name");
        o.addProperty("name", name == null ? null : String.valueOf(name));
        o.addProperty("owner", String.valueOf(call(island, "owner")));
        o.addProperty("border", ((Number) call(island, "borderSize")).doubleValue());
        o.addProperty("members", ((Number) call(island, "memberCount")).intValue());
        o.addProperty("level", level == null ? 1 : ((Number) level.invoke(levels, island)).intValue());
        o.addProperty("milestones", completed(call(island, "milestones")));
        o.addProperty("objectivesDone", completed(call(island, "objectives")));
        list.add(o);
      }
      JsonObject out = new JsonObject();
      out.add("list", list);
      warned = false;
      status = "ok (" + list.size() + " île" + (list.size() > 1 ? "s" : "") + ")";
      return out;
    } catch (Throwable t) {
      Throwable cause = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
      status = "erreur : " + cause;
      if (!warned) {
        warned = true;
        logger.warning("Lecture des îles NexoraMc impossible (version incompatible ?) : " + cause);
      }
      return null;
    }
  }

  /** État de la dernière lecture, affiché par /nanalytics et dans le dashboard. */
  public String status() {
    return status;
  }

  private static int completed(Object objectives) throws Exception {
    if (!(objectives instanceof List<?> list)) return 0;
    int n = 0;
    for (Object o : list) if (Boolean.TRUE.equals(call(o, "completed"))) n++;
    return n;
  }

  private static Object call(Object target, String method) throws Exception {
    Method m = target.getClass().getMethod(method);
    return m.invoke(target);
  }

  private static Method findMethod(Class<?> type, String name, Class<?> arg) {
    for (Method m : type.getMethods()) {
      if (m.getName().equals(name) && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(arg)) {
        return m;
      }
    }
    return null;
  }
}
