package fr.astra.nexora.analytics.velocity.storage;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Driver;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;

/**
 * Fournit le driver JDBC SQLite.
 *
 * <p>Le driver est normalement embarqué dans le jar du plugin (maven-shade-plugin). Si ce n'est pas
 * le cas (jar « original-… » copié par erreur, jar construit par l'IDE sans passer par Maven...), il
 * est téléchargé une seule fois depuis Maven Central dans {@code plugins/nexora-analytics/libs/},
 * vérifié par empreinte SHA-256, puis chargé dans un class loader dédié.
 */
final class SqliteDriver {
  private static final String VERSION = "3.49.1.0";
  private static final String SHA256 = "5c8609d2ca341deb8c6f71778974b5ba4995c7d32d7c7c89d9392a3e72c39291";
  private static final String URL_TEMPLATE =
      "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/%1$s/sqlite-jdbc-%1$s.jar";

  private SqliteDriver() {}

  static Driver load(Path libsDir, Logger logger) throws Exception {
    try {
      Class<?> type = Class.forName("org.sqlite.JDBC");
      return (Driver) type.getDeclaredConstructor().newInstance();
    } catch (ClassNotFoundException missing) {
      logger.warn("Driver SQLite absent du jar : utilisation d'une copie externe (libs/).");
    }
    Path jar = libsDir.resolve("sqlite-jdbc-" + VERSION + ".jar");
    if (!Files.exists(jar) || !SHA256.equals(sha256(jar))) download(jar, logger);
    // Le class loader reste ouvert pendant toute la vie du plugin : le driver en a besoin.
    URLClassLoader loader =
        new URLClassLoader(new URL[] {jar.toUri().toURL()}, SqliteDriver.class.getClassLoader());
    Class<?> type = Class.forName("org.sqlite.JDBC", true, loader);
    return (Driver) type.getDeclaredConstructor().newInstance();
  }

  private static void download(Path jar, Logger logger) throws Exception {
    Files.createDirectories(jar.getParent());
    String url = String.format(URL_TEMPLATE, VERSION);
    logger.info("Téléchargement du driver SQLite depuis {}", url);
    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    Path tmp = jar.resolveSibling(jar.getFileName() + ".part");
    HttpResponse<Path> response =
        http.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3)).GET().build(),
            HttpResponse.BodyHandlers.ofFile(tmp));
    if (response.statusCode() != 200) {
      Files.deleteIfExists(tmp);
      throw new IOException("Téléchargement du driver SQLite impossible (HTTP " + response.statusCode() + ")");
    }
    if (!SHA256.equals(sha256(tmp))) {
      Files.deleteIfExists(tmp);
      throw new IOException("Driver SQLite téléchargé corrompu (empreinte SHA-256 invalide)");
    }
    Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
  }

  private static String sha256(Path file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
  }
}
