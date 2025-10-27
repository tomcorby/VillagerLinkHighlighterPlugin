package uk.tojoco.villagerlink;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public final class VillagerLinkHighlighterPlugin extends JavaPlugin {

    private final Map<UUID, Optional<?>> lastHome = new HashMap<>();
    private final Map<UUID, Optional<?>> lastJob  = new HashMap<>();
    private final Map<UUID, Long> lastTriggered = new HashMap<>();

    // Config
    private int scanInterval;
    private int capPerTick;
    private int villagerDuration;
    private int villagerEvery;
    private Particle villagerParticle;
    private int villagerCount;
    private double villagerExtra;
    private Sound villagerSound;
    private float villagerSoundVol;
    private float villagerSoundPitch;

    private int poiDuration;
    private int poiEvery;
    private Particle poiParticle;
    private int poiCount;
    private double poiExtra;
    private Sound poiSound;
    private float poiSoundVol;
    private float poiSoundPitch;

    private int cooldownTicks;
    private boolean debug;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        // Command: /villagerlink reload
        getCommand("villagerlink").setExecutor((sender, cmd, label, args) -> {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                loadConfigValues();
                sender.sendMessage("§aVillagerLinkHighlighter reloaded.");
                return true;
            }
            sender.sendMessage("§eUsage: /villagerlink reload");
            return true;
        });

        // Repeating scanner
        new BukkitRunnable() {
            @Override
            public void run() {
                scanTick();
            }
        }.runTaskTimer(this, scanInterval, scanInterval);

        getLogger().info("VillagerLinkHighlighter enabled.");
    }

    @Override
    public void onDisable() {
        lastHome.clear();
        lastJob.clear();
        lastTriggered.clear();
    }

    private void loadConfigValues() {
        FileConfiguration c = getConfig();
        scanInterval = c.getInt("scan.interval_ticks", 10);
        capPerTick = c.getInt("scan.max_villagers_per_tick", 200);

        villagerParticle = parseParticle(c.getString("effects.villager.particle", "HAPPY_VILLAGER"));
        villagerCount = c.getInt("effects.villager.count", 30);
        villagerExtra = c.getDouble("effects.villager.extra", 0.1);
        villagerDuration = c.getInt("effects.villager.duration_ticks", 60);
        villagerEvery = c.getInt("effects.villager.every_ticks", 5);
        villagerSound = parseSound(c.getString("effects.villager.sound", "ENTITY_VILLAGER_YES"));
        villagerSoundVol = (float) c.getDouble("effects.villager.sound_volume", 0.9);
        villagerSoundPitch = (float) c.getDouble("effects.villager.sound_pitch", 1.2f);

        poiParticle = parseParticle(c.getString("effects.poi.particle", "ENCHANT"));
        poiCount = c.getInt("effects.poi.count", 40);
        poiExtra = c.getDouble("effects.poi.extra", 0.0);
        poiDuration = c.getInt("effects.poi.duration_ticks", 60);
        poiEvery = c.getInt("effects.poi.every_ticks", 5);
        poiSound = parseSound(c.getString("effects.poi.sound", "BLOCK_BEACON_ACTIVATE"));
        poiSoundVol = (float) c.getDouble("effects.poi.sound_volume", 0.6);
        poiSoundPitch = (float) c.getDouble("effects.poi.sound_pitch", 1.0f);

        cooldownTicks = c.getInt("cooldowns.per_villager_ticks", 40);
        debug = c.getBoolean("debug", false);
    }

    private Particle parseParticle(String name) {
        try { return Particle.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (Exception e) { return Particle.HAPPY_VILLAGER; }
    }

    private Sound parseSound(String name) {
        try { return Sound.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (Exception e) { return Sound.ENTITY_VILLAGER_YES; }
    }

    private void scanTick() {
        int processed = 0;
        long nowTick = Bukkit.getCurrentTick();

        for (World world : Bukkit.getWorlds()) {
            Collection<Villager> villagers = world.getEntitiesByClass(Villager.class);
            for (Villager v : villagers) {
                if (processed >= capPerTick) return;
                processed++;

                UUID id = v.getUniqueId();

                // Cooldown (ensure default is a Long)
                long last = lastTriggered.getOrDefault(id, -1L * (long) cooldownTicks);
                if (nowTick - last < cooldownTicks) continue;

                // Read memories; handle both Optional<T> and direct T (e.g., Location)
                Optional<?> home = readMemory(v, MemoryKey.HOME);
                Optional<?> job  = readMemory(v, MemoryKey.JOB_SITE);

                boolean changed = false;

                if (!Objects.equals(lastHome.get(id), home)) {
                    if (home.isPresent()) {
                        Location poiLoc = memoryToLocation(v, home.get());
                        triggerEffects(v, poiLoc, "HOME");
                        changed = true;
                    }
                    lastHome.put(id, home);
                    if (debug) getLogger().info("Villager " + id + " HOME changed: " + home.map(Object::toString).orElse("empty"));
                }

                if (!Objects.equals(lastJob.get(id), job)) {
                    if (job.isPresent()) {
                        Location poiLoc = memoryToLocation(v, job.get());
                        triggerEffects(v, poiLoc, "JOB");
                        changed = true;
                    }
                    lastJob.put(id, job);
                    if (debug) getLogger().info("Villager " + id + " JOB_SITE changed: " + job.map(Object::toString).orElse("empty"));
                }

                if (changed) {
                    lastTriggered.put(id, nowTick);
                }
            }
        }
    }

    // Normalise Paper's getMemory() output across versions/builds.
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private Optional<?> readMemory(Villager v, MemoryKey<?> key) {
        Object raw = v.getMemory((MemoryKey) key);      // may be Optional<T> or T
        if (raw instanceof Optional) return (Optional<?>) raw;
        return Optional.ofNullable(raw);
    }

    /**
     * Convert Paper/Mojang memory object into a Location if possible.
     * Works when Paper returns a Location directly, or falls back to parsing known toString formats.
     */
    private Location memoryToLocation(Villager villager, Object memoryVal) {
        if (memoryVal == null) return null;

        if (memoryVal instanceof Location loc) {
            return loc;
        }

        // Fallback: attempt parse from strings like "GlobalPos{dimension=minecraft:overworld, pos=(x,y,z)}"
        try {
            String s = memoryVal.toString();
            World w = villager.getWorld();
            int i = s.indexOf('('), j = s.indexOf(')');
            if (i >= 0 && j > i) {
                String[] parts = s.substring(i + 1, j).split(",");
                if (parts.length >= 3) {
                    double x = Double.parseDouble(parts[0].trim());
                    double y = Double.parseDouble(parts[1].trim());
                    double z = Double.parseDouble(parts[2].trim());
                    return new Location(w, x + 0.5, y + 0.5, z + 0.5);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void triggerEffects(Villager villager, Location poiLoc, String kind) {
        Location villagerLoc = villager.getLocation().add(0, 1.0, 0);

        // Villager sparkle loop
        new BukkitRunnable() {
            int elapsed = 0;
            @Override
            public void run() {
                if (elapsed >= villagerDuration || !villager.isValid()) { cancel(); return; }
                villager.getWorld().spawnParticle(
                        villagerParticle,
                        villagerLoc,
                        villagerCount,
                        0.4, 0.7, 0.4,
                        villagerExtra
                );
                if (elapsed == 0 && villagerSound != null) {
                    villager.getWorld().playSound(villagerLoc, villagerSound, villagerSoundVol, villagerSoundPitch);
                }
                elapsed += villagerEvery;
            }
        }.runTaskTimer(this, 0L, villagerEvery);

        // POI sparkle loop (bed/workstation) if we have a location
        if (poiLoc != null) {
            Location center = poiLoc.toCenterLocation();
            new BukkitRunnable() {
                int elapsed = 0;
                @Override
                public void run() {
                    if (elapsed >= poiDuration) { cancel(); return; }
                    center.getWorld().spawnParticle(
                            poiParticle,
                            center,
                            poiCount,
                            0.3, 0.5, 0.3,
                            poiExtra
                    );
                    if (elapsed == 0 && poiSound != null) {
                        center.getWorld().playSound(center, poiSound, poiSoundVol, poiSoundPitch);
                    }
                    elapsed += poiEvery;
                }
            }.runTaskTimer(this, 0L, poiEvery);
        }

        if (debug) getLogger().info("Triggered " + kind + " highlight for villager " + villager.getUniqueId());
    }
}
