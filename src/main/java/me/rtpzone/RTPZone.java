package me.rtpzone;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class RTPZone extends JavaPlugin implements Listener {

    private NamespacedKey toolKey;
    private File zonesFile;

    private final List<Zone> zones = new ArrayList<>();
    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();
    private final Map<UUID, BukkitTask> countdowns = new HashMap<>();
    private final Map<UUID, int[]> lastBlockPos = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        toolKey = new NamespacedKey(this, "rtpzone_tool");
        zonesFile = new File(getDataFolder(), "zones.yml");
        loadZones();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("RTPZone enabled. " + zones.size() + " zone(s) loaded.");
    }

    // ---------- Commands ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("setzone")) {
            player.getInventory().addItem(createTool());
            player.sendMessage(Component.text(
                    "Right-click a block for Position 1, left-click a block for Position 2.",
                    NamedTextColor.GOLD));
            return true;
        }

        if (command.getName().equalsIgnoreCase("setrtpzone")) {
            Location p1 = pos1.get(player.getUniqueId());
            Location p2 = pos2.get(player.getUniqueId());

            if (p1 == null || p2 == null) {
                player.sendMessage(Component.text(
                        "Select both positions with the hoe first (/setzone).", NamedTextColor.RED));
                return true;
            }
            if (!p1.getWorld().equals(p2.getWorld())) {
                player.sendMessage(Component.text(
                        "Both positions must be in the same world.", NamedTextColor.RED));
                return true;
            }

            Zone zone = new Zone(
                    "zone" + (zones.size() + 1),
                    p1.getWorld().getName(),
                    Math.min(p1.getBlockX(), p2.getBlockX()),
                    Math.min(p1.getBlockY(), p2.getBlockY()),
                    Math.min(p1.getBlockZ(), p2.getBlockZ()),
                    Math.max(p1.getBlockX(), p2.getBlockX()),
                    Math.max(p1.getBlockY(), p2.getBlockY()),
                    Math.max(p1.getBlockZ(), p2.getBlockZ())
            );
            zones.add(zone);
            saveZones();

            pos1.remove(player.getUniqueId());
            pos2.remove(player.getUniqueId());

            player.sendMessage(Component.text(
                    "RTP zone '" + zone.id + "' created in world '" + zone.world + "'.",
                    NamedTextColor.GREEN));
            return true;
        }

        return false;
    }

    // ---------- Selection tool ----------

    private ItemStack createTool() {
        ItemStack hoe = new ItemStack(Material.WOODEN_HOE);
        ItemMeta meta = hoe.getItemMeta();
        meta.displayName(Component.text("RTP Zone Tool", NamedTextColor.AQUA));
        meta.lore(List.of(
                Component.text("Right-click: set Position 1", NamedTextColor.GRAY),
                Component.text("Left-click: set Position 2", NamedTextColor.GRAY)));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.BYTE, (byte) 1);
        hoe.setItemMeta(meta);
        return hoe;
    }

    private boolean isTool(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(toolKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!isTool(event.getItem()) || event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        Location loc = event.getClickedBlock().getLocation();

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            pos1.put(player.getUniqueId(), loc);
            player.sendMessage(Component.text(
                    "Position 1 set: " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ(),
                    NamedTextColor.YELLOW));
        } else if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            pos2.put(player.getUniqueId(), loc);
            player.sendMessage(Component.text(
                    "Position 2 set: " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ(),
                    NamedTextColor.YELLOW));
        }
    }

    // Safety net: never let the selection tool break a block.
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (isTool(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    // ---------- Zone detection & countdown ----------

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        UUID id = event.getPlayer().getUniqueId();
        int[] last = lastBlockPos.get(id);
        int bx = to.getBlockX(), by = to.getBlockY(), bz = to.getBlockZ();
        if (last != null && last[0] == bx && last[1] == by && last[2] == bz) {
            return; // Didn't change block position; skip the (more expensive) zone check.
        }
        lastBlockPos.put(id, new int[]{bx, by, bz});

        boolean inside = isInAnyZone(to);
        boolean counting = countdowns.containsKey(id);

        if (inside && !counting) {
            Player player = event.getPlayer();
            player.sendMessage(Component.text("RTP in 5 seconds.........", NamedTextColor.GREEN));
            BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
                countdowns.remove(id);
                if (player.isOnline()) {
                    teleportPlayer(player);
                }
            }, 100L); // 5 seconds
            countdowns.put(id, task);
        } else if (!inside && counting) {
            countdowns.remove(id).cancel();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastBlockPos.remove(id);
        BukkitTask task = countdowns.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    private boolean isInAnyZone(Location loc) {
        String worldName = loc.getWorld().getName();
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        for (Zone zone : zones) {
            if (zone.world.equals(worldName)
                    && x >= zone.minX && x <= zone.maxX
                    && y >= zone.minY && y <= zone.maxY
                    && z >= zone.minZ && z <= zone.maxZ) {
                return true;
            }
        }
        return false;
    }

    // ---------- Teleporting ----------

    private void teleportPlayer(Player player) {
        World destination = resolveDestinationWorld(player);
        if (destination == null) {
            return; // resolveDestinationWorld already warned/messaged as needed.
        }
        int radius = Math.max(1, getConfig().getInt("radius", 500));
        attemptRandomTeleport(player, destination, radius, 15);
    }

    private World resolveDestinationWorld(Player player) {
        boolean multiverseInstalled = Bukkit.getPluginManager().isPluginEnabled("Multiverse-Core");
        String configuredWorld = getConfig().getString("multiverse.world", "");

        if (multiverseInstalled && configuredWorld != null && !configuredWorld.isEmpty()) {
            World world = Bukkit.getWorld(configuredWorld);
            if (world == null) {
                getLogger().warning("multiverse.world is set to '" + configuredWorld
                        + "' but no such world exists. Not teleporting " + player.getName() + ".");
                return null;
            }
            return world;
        }

        // No Multiverse override: use the zone's own world (the player's current world).
        return player.getWorld();
    }

    private void attemptRandomTeleport(Player player, World world, int radius, int attemptsLeft) {
        if (!player.isOnline()) {
            return;
        }
        if (attemptsLeft <= 0) {
            player.sendMessage(Component.text(
                    "Couldn't find a safe spot to teleport you to, try again.", NamedTextColor.RED));
            return;
        }

        Location spawn = world.getSpawnLocation();
        int x = spawn.getBlockX() + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
        int z = spawn.getBlockZ() + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
        int chunkX = x >> 4;
        int chunkZ = z >> 4;

        // Loaded asynchronously so this never freezes the server like a forced sync chunk load would.
        world.getChunkAtAsync(chunkX, chunkZ, true).thenAccept(chunk ->
                Bukkit.getScheduler().runTask(this, () -> {
                    int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                    Block ground = world.getBlockAt(x, y - 1, z);
                    Block feet = world.getBlockAt(x, y, z);
                    Block head = world.getBlockAt(x, y + 1, z);

                    boolean safe = ground.getType().isSolid()
                            && !ground.isLiquid()
                            && feet.getType() == Material.AIR
                            && head.getType() == Material.AIR;

                    if (safe) {
                        Location dest = new Location(world, x + 0.5, y, z + 0.5);
                        player.teleportAsync(dest);
                        player.sendMessage(Component.text("You've been randomly teleported!", NamedTextColor.GREEN));
                    } else {
                        attemptRandomTeleport(player, world, radius, attemptsLeft - 1);
                    }
                })
        );
    }

    // ---------- Zone persistence ----------

    private void loadZones() {
        zones.clear();
        if (!zonesFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(zonesFile);
        ConfigurationSection section = yaml.getConfigurationSection("zones");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection z = section.getConfigurationSection(key);
            if (z == null) {
                continue;
            }
            zones.add(new Zone(
                    key,
                    z.getString("world", "world"),
                    z.getInt("minX"), z.getInt("minY"), z.getInt("minZ"),
                    z.getInt("maxX"), z.getInt("maxY"), z.getInt("maxZ")
            ));
        }
    }

    private void saveZones() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Zone zone : zones) {
            String path = "zones." + zone.id;
            yaml.set(path + ".world", zone.world);
            yaml.set(path + ".minX", zone.minX);
            yaml.set(path + ".minY", zone.minY);
            yaml.set(path + ".minZ", zone.minZ);
            yaml.set(path + ".maxX", zone.maxX);
            yaml.set(path + ".maxY", zone.maxY);
            yaml.set(path + ".maxZ", zone.maxZ);
        }
        try {
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }
            yaml.save(zonesFile);
        } catch (IOException e) {
            getLogger().severe("Could not save zones.yml: " + e.getMessage());
        }
    }

    private static final class Zone {
        final String id;
        final String world;
        final int minX, minY, minZ, maxX, maxY, maxZ;

        Zone(String id, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.id = id;
            this.world = world;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
    }
}
