package fr.astra.nexora.analytics.paper.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Compte les ressources utilisées par les joueurs (hors créatif) : blocs cassés, blocs posés et
 * objets fabriqués. Les compteurs sont agrégés en mémoire puis envoyés au proxy à chaque envoi.
 */
public final class ResourceCollector implements Listener {
  private final Map<String, Long> counts = new HashMap<>();

  private static boolean ignored(HumanEntity player) {
    return player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
  }

  private void add(String action, Material material, long amount) {
    if (material == null || material.isAir() || amount <= 0) return;
    counts.merge(action + ":" + material.name(), amount, Long::sum);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBreak(BlockBreakEvent event) {
    if (!ignored(event.getPlayer())) add("BREAK", event.getBlock().getType(), 1);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPlace(BlockPlaceEvent event) {
    if (!ignored(event.getPlayer())) add("PLACE", event.getBlockPlaced().getType(), 1);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onCraft(CraftItemEvent event) {
    if (!(event.getWhoClicked() instanceof Player player) || ignored(player)) return;
    ItemStack result = event.getRecipe().getResult();
    int amount = result.getAmount();
    if (event.isShiftClick()) {
      // Shift-clic : fabrique autant d'exemplaires que le permettent les ingrédients.
      int crafts = Integer.MAX_VALUE;
      for (ItemStack ingredient : event.getInventory().getMatrix()) {
        if (ingredient != null && !ingredient.getType().isAir()) crafts = Math.min(crafts, ingredient.getAmount());
      }
      if (crafts != Integer.MAX_VALUE) amount *= crafts;
    }
    add("CRAFT", result.getType(), amount);
  }

  public JsonArray drain() {
    JsonArray array = new JsonArray();
    for (var e : counts.entrySet()) {
      int i = e.getKey().indexOf(':');
      JsonObject o = new JsonObject();
      o.addProperty("action", e.getKey().substring(0, i));
      o.addProperty("material", e.getKey().substring(i + 1));
      o.addProperty("count", e.getValue());
      array.add(o);
    }
    counts.clear();
    return array;
  }
}
