package fr.astra.nexora.analytics.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import fr.astra.nexora.analytics.velocity.config.AnalyticsConfig;
import fr.astra.nexora.analytics.velocity.web.AuthManager;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * {@code /analytics} : envoie à l'admin un lien personnel et temporaire vers le dashboard.
 *
 * <ul>
 *   <li>{@code /analytics} : génère un lien de connexion (usage unique).
 *   <li>{@code /analytics logout} : déconnecte toutes les sessions ouvertes du dashboard.
 *   <li>{@code /analytics secret} : affiche le secret d'ingestion (console uniquement).
 * </ul>
 */
public final class AnalyticsCommand implements SimpleCommand {
  public static final String PERMISSION = "nexora.analytics.admin";
  private static final TextColor BRAND = TextColor.color(0x8B5CF6);
  private static final TextColor ACCENT = TextColor.color(0x22D3EE);

  private final AnalyticsConfig config;
  private final AuthManager auth;

  public AnalyticsCommand(AnalyticsConfig config, AuthManager auth) {
    this.config = config;
    this.auth = auth;
  }

  @Override
  public void execute(Invocation invocation) {
    CommandSource source = invocation.source();
    String[] args = invocation.arguments();
    String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
    switch (sub) {
      case "logout" -> {
        int n = auth.revokeAll();
        source.sendMessage(prefix().append(Component.text(n + " session(s) du dashboard fermée(s).", NamedTextColor.GRAY)));
      }
      case "secret" -> {
        if (!(source instanceof ConsoleCommandSource)) {
          source.sendMessage(prefix().append(Component.text(
              "Par sécurité, le secret ne s'affiche que dans la console du proxy.", NamedTextColor.RED)));
          return;
        }
        source.sendMessage(prefix().append(Component.text("Secret d'ingestion : " + config.ingestSecret(), NamedTextColor.GRAY)));
      }
      default -> sendLink(source);
    }
  }

  private void sendLink(CommandSource source) {
    String admin = source instanceof Player p ? p.getUsername() : "Console";
    String url = config.publicUrl() + "/auth?token=" + auth.createToken(admin);
    Component link =
        Component.text("  ➜ Ouvrir le dashboard", ACCENT, TextDecoration.BOLD)
            .clickEvent(ClickEvent.openUrl(url))
            .hoverEvent(HoverEvent.showText(Component.text("Cliquer pour ouvrir Nexora Analytics", NamedTextColor.GRAY)));
    source.sendMessage(Component.empty());
    source.sendMessage(prefix().append(Component.text("Votre lien d'accès personnel :", NamedTextColor.WHITE)));
    source.sendMessage(link);
    source.sendMessage(Component.text(
        "  Valable " + config.tokenMinutes() + " min, utilisable une seule fois. Ne le partagez pas.",
        NamedTextColor.DARK_GRAY));
    if (!(source instanceof Player)) source.sendMessage(Component.text("  " + url, NamedTextColor.GRAY));
    source.sendMessage(Component.empty());
  }

  private static Component prefix() {
    return Component.text("Nexora", BRAND, TextDecoration.BOLD)
        .append(Component.text("Analytics ", ACCENT, TextDecoration.BOLD))
        .append(Component.text("» ", NamedTextColor.DARK_GRAY));
  }

  @Override
  public boolean hasPermission(Invocation invocation) {
    return invocation.source().hasPermission(PERMISSION);
  }

  @Override
  public List<String> suggest(Invocation invocation) {
    String[] args = invocation.arguments();
    if (args.length <= 1) {
      String start = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
      return List.of("logout", "secret").stream().filter(s -> s.startsWith(start)).toList();
    }
    return List.of();
  }
}
