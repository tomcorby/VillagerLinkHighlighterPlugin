package uk.tojoco.villagerlink;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class DebugStickListener implements Listener {

    private final VillagerLinkHighlighterPlugin plugin;

    // Which villager a player has "selected" with the stick
    private final Map<UUID, UUID> selectedVillagerByPlayer = new HashMap<>();

    // Known workstation blocks (job sites)
    private static final Set<Material> WORKSTATIONS = EnumSet.of(
            Material.COMPOSTER,          // Farmer
            Material.LECTERN,            // Librarian
            Material.BLAST_FURNACE,      // Armorer
            Material.SMOKER,             // Butcher
            Material.SMITHING_TABLE,     // Toolsmith
            Material.GRINDSTONE,         // Weaponsmith
            Material.CARTOGRAPHY_TABLE,  // Cartographer
            Material.BREWING_STAND,      // Cleric
            Material.BARREL,             // Fisherman
            Material.FLETCHING_TABLE,    // Fletcher
            Material.CAULDRON,           // Leatherworker
            Material.STONECUTTER,        // Mason
            Material.LOOM                // Shepherd
    );

    public DebugStickListener(VillagerLinkHighlighterPlugin plugin) {
        this.plugin = plugin;
    }

    /** Create the special stick item. */
    public static ItemStack makeStick() {
        ItemStack it = new ItemStack(Material.STICK);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName("§bVillager Linker");
        meta.setLore(List.of("§7Sneak-Right-Click a villager to select",
                             "§7Right-Click a bed to set HOME",
                             "§7Right-Click a workstation to set JOB_SITE"));
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        // Optional: custom model data so resource packs can skin it
        meta.setCustomModelData(2100121);
        it.setItemMeta(meta);
        return it;
    }

    private boolean isOurStick(ItemStack item) {
        if (item == null || item.getType() != Material.STICK) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return "§bVillager Linker".equals(meta.getDisplayName());
    }

    /** Step 1: Select villager (sneak-right-click with stick). */
    @EventHandler
    public void onInteractVillager(PlayerInteractEntityEvent ev) {
        if (ev.getHand() != EquipmentSlot.HAND) return; // ignore off-hand duplicate
        if (!(ev.getRightClicked() instanceof Villager villager)) return;

        Player p = ev.getPlayer();
        if (!p.isSneaking()) return;
        if (!isOurStick(p.getInventory().getItemInMainHand())) return;

        // prevent opening trade UI
        ev.setCancelled(true);

        selectedVillagerByPlayer.put(p.getUniqueId(), villager.getUniqueId());
        p.sendActionBar("§aSelected villager §f" + villager.getUniqueId().toString().substring(0, 8));
        villager.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, villager.getLocation().add(0, 1.3, 0), 25, 0.3, 0.6, 0.3, 0.05);
        villager.getWorld().playSound(villager.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.9f, 1.2f);
    }

    /** Step 2: Click a bed or workstation to assign. */
    @EventHandler
    public void onInteractBlock(PlayerInteractEvent ev) {
        if (ev.getHand() != EquipmentSlot.HAND) return; // ignore off-hand duplicate
        if (ev.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (ev.getClickedBlock() == null) return;

        Player p = ev.getPlayer();
        if (!isOurStick(p.getInventory().getItemInMainHand())) return;

        UUID sel = selectedVillagerByPlayer.get(p.getUniqueId());
        if (sel == null) {
            p.sendActionBar("§eNo villager selected. Sneak-right-click a villager first.");
            return;
        }

        Villager villager = findVillager(sel);
        if (villager == null || !villager.isValid()) {
            p.sendActionBar("§cSelected villager is gone.");
            selectedVillagerByPlayer.remove(p.getUniqueId());
            return;
        }

        Block b = ev.getClickedBlock();
        Material mat = b.getType();

        // We are handling this; stop default (bed spawn/sleep etc.)
        ev.setCancelled(true);

        // Assign HOME if bed
        if (isBed(mat)) {
            Location bedLoc = bedCenter(b);
            clearMemory(villager, MemoryKey.HOME);
            setMemory(villager, MemoryKey.HOME, bedLoc);

            feedback(p, villager, bedLoc, "§bHOME linked");
            return;
        }

        // Assign JOB_SITE if workstation
        if (WORKSTATIONS.contains(mat)) {
            Location wsLoc = b.getLocation().toCenterLocation();
            clearMemory(villager, MemoryKey.JOB_SITE);
            setMemory(villager, MemoryKey.JOB_SITE, wsLoc);

            feedback(p, villager, wsLoc, "§dJOB_SITE linked");
        }
    }

    private Villager findVillager(UUID id) {
        for (var w : plugin.getServer().getWorlds()) {
            for (var v : w.getEntitiesByClass(Villager.class)) {
                if (v.getUniqueId().equals(id)) return v;
            }
        }
        return null;
    }

    private boolean isBed(Material m) {
        // tag covers all colored beds
        return Tag.BEDS.isTagged(m);
    }

    private Location bedCenter(Block bedBlock) {
        // Use head part center for consistency
        Location loc = bedBlock.getLocation();
        if (bedBlock.getBlockData() instanceof Bed bed) {
            // If we're on the FOOT, step one block toward the HEAD
            if (bed.getPart() != Bed.Part.HEAD) {
                loc = loc.add(bed.getFacing().getModX(), 0, bed.getFacing().getModZ());
            }
        }
        return loc.toCenterLocation();
    }

    /** Generic memory clear with tolerant generics. */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private <T> void clearMemory(Villager v, MemoryKey<T> key) {
        v.setMemory((MemoryKey) key, null);
    }

    /** Generic memory set with tolerant generics. */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private <T> void setMemory(Villager v, MemoryKey<T> key, T val) {
        v.setMemory((MemoryKey) key, val);
    }

    private void feedback(Player p, Villager v, Location poi, String msg) {
        p.sendActionBar("§a" + msg + " §7for §f" + v.getUniqueId().toString().substring(0, 8));
        v.getWorld().spawnParticle(Particle.ENCHANT, poi, 40, 0.3, 0.5, 0.3, 0.0);
        v.getWorld().playSound(poi, Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.0f);
    }
}
