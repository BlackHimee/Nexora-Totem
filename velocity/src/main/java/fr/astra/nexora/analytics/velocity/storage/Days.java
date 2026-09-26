package fr.astra.nexora.analytics.velocity.storage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Conversion entre horodatages (ms) et journées ({@code yyyy-MM-dd}) dans le fuseau configuré. */
public final class Days {
  private final ZoneId zone;

  public Days(ZoneId zone) {
    this.zone = zone;
  }

  public ZoneId zone() {
    return zone;
  }

  public LocalDate today() {
    return LocalDate.now(zone);
  }

  public LocalDate date(long epochMs) {
    return Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate();
  }

  public String day(long epochMs) {
    return date(epochMs).toString();
  }

  public String todayKey() {
    return today().toString();
  }

  /** Début (ms) de la journée {@code date}. */
  public long startOf(LocalDate date) {
    return date.atStartOfDay(zone).toInstant().toEpochMilli();
  }
}
