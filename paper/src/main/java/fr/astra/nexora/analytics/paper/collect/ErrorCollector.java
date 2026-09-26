package fr.astra.nexora.analytics.paper.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Intercepte les messages ERROR / FATAL de la console du serveur (exceptions de plugins, erreurs
 * de tick, watchdog...) pour les remonter comme incidents dans le dashboard.
 *
 * <p>Les erreurs identiques sont regroupées entre deux envois et leur nombre est plafonné, afin
 * qu'une boucle d'erreurs ne sature ni le réseau ni la base du proxy.
 */
public final class ErrorCollector extends AbstractAppender {
  private static final int MAX_DISTINCT = 40;
  private static final int MAX_STACK_LINES = 25;

  private static final class Entry {
    final long ts;
    final String level;
    final String logger;
    final String message;
    final String thrown;
    int count = 1;

    Entry(long ts, String level, String logger, String message, String thrown) {
      this.ts = ts;
      this.level = level;
      this.logger = logger;
      this.message = message;
      this.thrown = thrown;
    }
  }

  private final Map<String, Entry> pending = new LinkedHashMap<>();
  private int dropped;

  public ErrorCollector() {
    super("NexoraAnalyticsErrors", null, null, true, Property.EMPTY_ARRAY);
  }

  public void install() {
    start();
    ((Logger) LogManager.getRootLogger()).addAppender(this);
  }

  public void uninstall() {
    ((Logger) LogManager.getRootLogger()).removeAppender(this);
    stop();
  }

  @Override
  public void append(LogEvent event) {
    if (!event.getLevel().isMoreSpecificThan(Level.ERROR)) return;
    String loggerName = event.getLoggerName() == null ? "" : event.getLoggerName();
    if (loggerName.contains("NexoraAnalytics")) return;
    String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
    if (message.length() > 500) message = message.substring(0, 500) + "…";
    Throwable thrown = event.getThrown();
    String key = message + "|" + (thrown == null ? "" : thrown.getClass().getName());
    synchronized (pending) {
      Entry existing = pending.get(key);
      if (existing != null) {
        existing.count++;
        return;
      }
      if (pending.size() >= MAX_DISTINCT) {
        dropped++;
        return;
      }
      pending.put(
          key,
          new Entry(
              event.getTimeMillis(),
              event.getLevel().name(),
              loggerName,
              message.isBlank() && thrown != null ? String.valueOf(thrown) : message,
              thrown == null ? null : stack(thrown)));
    }
  }

  private static String stack(Throwable t) {
    StringWriter sw = new StringWriter();
    t.printStackTrace(new PrintWriter(sw));
    String[] lines = sw.toString().split("\\R");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < Math.min(lines.length, MAX_STACK_LINES); i++) sb.append(lines[i]).append('\n');
    if (lines.length > MAX_STACK_LINES) sb.append("\t... ").append(lines.length - MAX_STACK_LINES).append(" lignes de plus");
    return sb.toString();
  }

  public JsonArray drain() {
    JsonArray array = new JsonArray();
    synchronized (pending) {
      for (Entry e : pending.values()) {
        JsonObject o = new JsonObject();
        o.addProperty("ts", e.ts);
        o.addProperty("level", e.level);
        o.addProperty("logger", e.logger);
        o.addProperty("message", e.count > 1 ? e.message + " (×" + e.count + ")" : e.message);
        if (e.thrown != null) o.addProperty("thrown", e.thrown);
        array.add(o);
      }
      if (dropped > 0) {
        JsonObject o = new JsonObject();
        o.addProperty("ts", System.currentTimeMillis());
        o.addProperty("level", "ERROR");
        o.addProperty("logger", "NexoraAnalytics");
        o.addProperty("message", dropped + " autre(s) erreur(s) distincte(s) non détaillée(s) (limite atteinte)");
        array.add(o);
      }
      pending.clear();
      dropped = 0;
    }
    return array;
  }
}
