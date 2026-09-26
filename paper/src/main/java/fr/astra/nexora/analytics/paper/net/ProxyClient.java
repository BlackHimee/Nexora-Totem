package fr.astra.nexora.analytics.paper.net;

import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Logger;

/**
 * Envoie les lots de données au proxy. En cas d'échec (proxy redémarré, réseau coupé...), les lots
 * sont conservés et renvoyés dans l'ordre à la prochaine tentative, dans la limite de
 * {@link #MAX_PENDING} lots pour borner la mémoire utilisée.
 */
public final class ProxyClient {
  private static final int MAX_PENDING = 60;

  private final Logger logger;
  private final URI endpoint;
  private final String secret;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final Deque<String> pending = new ArrayDeque<>();
  private boolean sending;
  private boolean failing;

  public ProxyClient(Logger logger, String proxyUrl, String secret) {
    this.logger = logger;
    String base = proxyUrl.endsWith("/") ? proxyUrl.substring(0, proxyUrl.length() - 1) : proxyUrl;
    this.endpoint = URI.create(base + "/api/ingest");
    this.secret = secret;
  }

  public synchronized void enqueue(JsonObject payload) {
    pending.addLast(payload.toString());
    while (pending.size() > MAX_PENDING) pending.removeFirst();
    if (!sending) sendNext();
  }

  private synchronized void sendNext() {
    String body = pending.peekFirst();
    if (body == null) {
      sending = false;
      return;
    }
    sending = true;
    HttpRequest request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("X-Nexora-Secret", secret)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
        .whenComplete((response, error) -> onResult(body, response, error));
  }

  private synchronized void onResult(String body, HttpResponse<Void> response, Throwable error) {
    if (error == null && response.statusCode() == 200) {
      if (pending.peekFirst() == body) pending.removeFirst();
      if (failing) logger.info("Connexion au proxy Nexora Analytics rétablie.");
      failing = false;
      sendNext();
      return;
    }
    sending = false;
    if (error == null && response.statusCode() == 403) {
      // Secret invalide : inutile de conserver les données, elles seraient refusées indéfiniment.
      pending.clear();
      if (!failing) logger.warning("Le proxy refuse les données : vérifiez 'secret' dans config.yml.");
    } else if (!failing) {
      String reason = error != null ? error.getClass().getSimpleName() : "HTTP " + response.statusCode();
      logger.warning("Proxy Nexora Analytics injoignable (" + endpoint + ", " + reason
          + "). Les données sont conservées et seront renvoyées.");
    }
    failing = true;
  }
}
