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
import org.bukkit.event.EventPriority;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.Event.Result;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class DebugStickListener implements org.bukkit.event.Listener {

    private final VillagerLinkHighlighterPlugin plugin;

    private final Map<UUID, UUID> selectedVillagerByPlayer = new HashMap<>();

    private static final Set<Material> WORKSTATIONS = EnumSet.of(
            Material.COMPOSTER, Material.LECTERN, Material.BLAST_FURNACE, Material.SMOKER,
            Material.SMITHING_TABLE, Material.GRINDSTONE, Material.CARTOGRAPHY_TABLE,
            Material.BREWING_STAND, Material.BARREL, Material.FLETCHING_TABLE,
            Material.CAULDRON, Material.STONECUTTER, Material.LOOM
    );

    public DebugStickListener(VillagerLinkHighlighterPlugin plugin) {
        this.plugin = plugin;
    }

    public static ItemStack makeStick() {
        ItemStack it = new ItemStack(Material.STICK);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName("§bVillager Linker");
        meta.setLore(List.of(
                "§7Sneak-Right-Click a villager to select",
                "§7Right-Click a bed to set HOME",
                "§7Right-Click a workstation to set JOB_SITE"
        ));
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.setCustomModelData(2100121);
        it.setItemMeta(meta);
        return it;
    }

    private boolean isOurStick(ItemStack item) {
        if (item == null || item.getType() != Material.STICK) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return false;
        String name = org.bukkit.ChatColor.stripColor(meta.getDisplayName());
        return name != null && name.equalsIgnoreCase("Villager Linker");
    }

    private boolean stickInEitherHand(Player p) {
        return isOurStick(p.getInventory().getItemInMainHand()) ||
               isOurStick(p.getInventory().getItemInOffHand());
    }

    /** Cancel villager UI & select target (some servers fire this event) */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractVillager(PlayerInteractEntityEvent ev) {
        if (!(ev.getRightClicked() instanceof Villager villager)) return;

        Player p = ev.getPlayer();
        if (!p.isSneaking() || !stickInEitherHand(p)) return;

        // cancel BOTH hands
        ev.setCancelled(true);
        if (ev.getHand() == EquipmentSlot.OFF_HAND) return;

        selectedVillagerByPlayer.put(p.getUniqueId(), villager.getUniqueId());
        p.sendActionBar("§aSelected villager §f" + villager.getUniqueId().toString().substring(0, 8));
        villager.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, villager.getLocation().add(0, 1.3, 0), 25, 0.3, 0.6, 0.3, 0.05);
        villager.getWorld().playSound(villager.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.9f, 1.2f);
    }

    /** Some builds fire AtEntity instead — cancel & select here too */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractAtVillager(PlayerInteractAtEntityEvent ev) {
        if (!(ev.getRightClicked() instanceof Villager villager)) return;

        Player p = ev.getPlayer();
        if (!p.isSneaking() || !stickInEitherHand(p)) return;

        ev.setCancelled(true);
        if (ev.getHand() == EquipmentSlot.OFF_HAND) return;

        selectedVillagerByPlayer.put(p.getUniqueId(), villager.getUniqueId());
        p.sendActionBar("§aSelected villager §f" + villager.getUniqueId().toString().substring(0, 8));
        villager.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, villager.getLocation().add(0, 1.3, 0), 25, 0.3, 0.6, 0.3, 0.05);
        villager.getWorld().playSound(villager.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.9f, 1.2f);
    }

    /** Cancel bed default (sleep/spawn) when using stick */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBedEnter(PlayerBedEnterEvent ev) {
        Player p = ev.getPlayer();
        if (!stickInEitherHand(p)) return;
        // Block the bed action entirely when using the stick
        ev.setCancelled(true);
        ev.setUseBed(Event.Result.DENY);
    }

    /** Click bed/workstation to assign */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractBlock(PlayerInteractEvent ev) {
        if (ev.getAction() != Action.RIGHT_CLICK_BLOCK || ev.getClickedBlock() == null) return;

        Player p = ev.getPlayer();
        if (!stickInEitherHand(p)) return;

        // Cancel & deny default block/item use for BOTH hands
        ev.setCancelled(true);
        ev.setUseInteractedBlock(Result.DENY);
        ev.setUseItemInHand(Result.DENY);
        if (ev.getHand() == EquipmentSlot.OFF_HAND) return;

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

        if (isBed(mat)) {
            Location bedLoc = bedCenter(b);
            clearMemory(villager, MemoryKey.HOME);
            setMemory(villager, MemoryKey.HOME, bedLoc);
            feedback(p, villager, bedLoc, "§bHOME linked");
            return;
        }

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
        return Tag.BEDS.isTagged(m);
    }

    private Location bedCenter(Block bedBlock) {
        Location loc = bedBlock.getLocation();
        if (bedBlock.getBlockData() instanceof Bed bed) {
            if (bed.getPart() != Bed.Part.HEAD) {
                loc = loc.add(bed.getFacing().getModX(), 0, bed.getFacing().getModZ());
            }
        }
        return loc.toCenterLocation();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private <T> void clearMemory(Villager v, MemoryKey<T> key) {
        v.setMemory((MemoryKey) key, null);
    }

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
