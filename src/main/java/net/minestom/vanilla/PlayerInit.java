package net.minestom.vanilla;

import lombok.Getter;
import net.minestom.server.MinecraftServer;
import net.minestom.server.benchmark.BenchmarkManager;
import net.minestom.server.benchmark.ThreadResult;
import net.minestom.server.chat.ChatColor;
import net.minestom.server.chat.ColoredText;
import net.minestom.server.data.Data;
import net.minestom.server.data.SerializableDataImpl;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventCallback;
import net.minestom.server.event.instance.AddEntityToInstanceEvent;
import net.minestom.server.event.item.ItemDropEvent;
import net.minestom.server.event.item.PickupItemEvent;
import net.minestom.server.event.player.*;
import net.minestom.server.extras.MojangAuth;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ExplosionSupplier;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.ConnectionManager;
import net.minestom.server.scoreboard.Sidebar;
import net.minestom.server.storage.StorageManager;
import net.minestom.server.utils.BlockPosition;
import net.minestom.server.utils.MathUtils;
import net.minestom.server.utils.Position;
import net.minestom.server.utils.Vector;
import net.minestom.server.utils.time.TimeUnit;
import net.minestom.server.utils.time.UpdateOption;
import net.minestom.server.world.DimensionType;
import net.minestom.vanilla.anvil.AnvilChunkLoader;
import net.minestom.vanilla.blocks.NetherPortalBlock;
import net.minestom.vanilla.blocks.VanillaBlocks;
import net.minestom.vanilla.dimensions.VanillaDimensionTypes;
import net.minestom.vanilla.generation.VanillaLikeGenerator;
import net.minestom.vanilla.instance.VanillaExplosion;
import net.minestom.vanilla.system.ServerProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

public class PlayerInit {

    @Getter
    private static volatile InstanceContainer overworld;
    private static volatile InstanceContainer nether;
    private static volatile InstanceContainer end;

    public static void init(final ServerProperties properties) {
        final String worldName = properties.get("level-name");

        final ExplosionSupplier explosionGenerator = (centerX, centerY, centerZ, strength, additionalData) -> {
            final boolean isTNT = additionalData != null ? additionalData.getOrDefault(VanillaExplosion.DROP_EVERYTHING_KEY, false) : false;
            final boolean noBlockDamage = additionalData != null ? additionalData.getOrDefault(VanillaExplosion.DONT_DESTROY_BLOCKS_KEY, false) : false;
            return new VanillaExplosion(centerX, centerY, centerZ, strength, false, isTNT, !noBlockDamage);
        };
        final StorageManager storageManager = MinecraftServer.getStorageManager();
        final VanillaLikeGenerator noiseTestGenerator = new VanillaLikeGenerator();
        overworld = MinecraftServer.getInstanceManager().createInstanceContainer(DimensionType.OVERWORLD, storageManager.getLocation(worldName + "/data")); // TODO: configurable
        overworld.enableAutoChunkLoad(true);
        overworld.setChunkGenerator(noiseTestGenerator);
        overworld.setData(new SerializableDataImpl());
        overworld.setExplosionSupplier(explosionGenerator);
        overworld.setChunkLoader(new AnvilChunkLoader(storageManager.getLocation(worldName + "/region")));

        overworld.addEventCallback(PlayerChunkLoadEvent.class, event -> {

        });

        nether = MinecraftServer.getInstanceManager().createInstanceContainer(VanillaDimensionTypes.NETHER, MinecraftServer.getStorageManager().getLocation(worldName + "/DIM-1/data"));
        nether.enableAutoChunkLoad(true);
        nether.setChunkGenerator(noiseTestGenerator);
        nether.setData(new SerializableDataImpl());
        nether.setExplosionSupplier(explosionGenerator);
        nether.setChunkLoader(new AnvilChunkLoader(storageManager.getLocation(worldName + "/DIM-1/region")));

        end = MinecraftServer.getInstanceManager().createInstanceContainer(VanillaDimensionTypes.END, MinecraftServer.getStorageManager().getLocation(worldName + "/DIM1/data"));
        end.enableAutoChunkLoad(true);
        end.setChunkGenerator(noiseTestGenerator);
        end.setData(new SerializableDataImpl());
        end.setExplosionSupplier(explosionGenerator);
        end.setChunkLoader(new AnvilChunkLoader(storageManager.getLocation(worldName + "/DIM1/region")));

        // Load some chunks beforehand
        final int loopStart = -2;
        final int loopEnd = 2;
        for (int x = loopStart; x < loopEnd; x++)
            for (int z = loopStart; z < loopEnd; z++) {
                overworld.loadChunk(x, z);
                nether.loadChunk(x, z);
                end.loadChunk(x, z);
            }

        final EventCallback<AddEntityToInstanceEvent> callback = event -> {
            event.getEntity().setData(new SerializableDataImpl());
            final Data data = event.getEntity().getData();
            if (event.getEntity() instanceof Player)
                data.set(NetherPortalBlock.PORTAL_COOLDOWN_TIME_KEY, 5 * 20L, Long.class);
        };
        overworld.addEventCallback(AddEntityToInstanceEvent.class, callback);
        nether.addEventCallback(AddEntityToInstanceEvent.class, callback);
        end.addEventCallback(AddEntityToInstanceEvent.class, callback);

        MinecraftServer.getSchedulerManager().buildShutdownTask(() -> {
            try {
                overworld.saveInstance(() -> System.out.println("Overworld saved"));
                nether.saveInstance(() -> System.out.println("Nether saved"));
                end.saveInstance(() -> System.out.println("End saved"));
            } catch (final Throwable e) {
                e.printStackTrace();
            }
        });

        if (Boolean.parseBoolean(properties.get("online-mode"))) MojangAuth.init();

        final ConnectionManager connectionManager = MinecraftServer.getConnectionManager();

        final BenchmarkManager benchmarkManager = MinecraftServer.getBenchmarkManager();

        benchmarkManager.enable(new UpdateOption(10 * 1000, TimeUnit.MILLISECOND));
        MinecraftServer.getSchedulerManager().buildTask(() -> {
            long ramUsage = benchmarkManager.getUsedMemory();
            ramUsage /= 1e6; // bytes to MB

            String benchmarkMessage = "";
            for (final Map.Entry<String, ThreadResult> resultEntry : benchmarkManager.getResultMap().entrySet()) {
                final String name = resultEntry.getKey();
                final ThreadResult result = resultEntry.getValue();
                benchmarkMessage += ChatColor.GRAY + name;
                benchmarkMessage += ": ";
                benchmarkMessage += ChatColor.YELLOW.toString() + MathUtils.round(result.getCpuPercentage(), 2) + "% CPU ";
                benchmarkMessage += ChatColor.RED.toString() + MathUtils.round(result.getUserPercentage(), 2) + "% USER ";
                benchmarkMessage += ChatColor.PINK.toString() + MathUtils.round(result.getBlockedPercentage(), 2) + "% BLOCKED ";
                benchmarkMessage += ChatColor.BRIGHT_GREEN.toString() + MathUtils.round(result.getWaitedPercentage(), 2) + "% WAITED ";
                benchmarkMessage += "\n";
            }

            for (final Player player : connectionManager.getOnlinePlayers()) {
                final ColoredText header = ColoredText.of("RAM USAGE: " + ramUsage + " MB");
                final ColoredText footer = ColoredText.of(benchmarkMessage);
                player.sendHeaderFooter(header, footer);
            }
        }).repeat(10, TimeUnit.TICK).schedule();

        connectionManager.addPlayerInitialization(player -> {
            player.addEventCallback(PlayerLoginEvent.class, event -> {
                event.setSpawningInstance(overworld);
            });

            // anticheat method
            // but also prevents client and server fighting for player position after a teleport due to a Nether portal
            player.addEventCallback(PlayerMoveEvent.class, moveEvent -> {
                final float currentX = player.getPosition().getX();
                final float currentY = player.getPosition().getY();
                final float currentZ = player.getPosition().getZ();
                final float velocityX = player.getVelocity().getX();
                final float velocityY = player.getVelocity().getY();
                final float velocityZ = player.getVelocity().getZ();

                final float dx = moveEvent.getNewPosition().getX() - currentX;
                final float dy = moveEvent.getNewPosition().getY() - currentY;
                final float dz = moveEvent.getNewPosition().getZ() - currentZ;

                final float actualDisplacement = dx * dx + dy * dy + dz * dz;
                final float expectedDisplacement = velocityX * velocityX + velocityY * velocityY + velocityZ * velocityZ;

                final float upperLimit = 100; // TODO: 300 if elytra deployed

                if (actualDisplacement - expectedDisplacement >= upperLimit) {
                    moveEvent.setCancelled(true);
                    player.teleport(player.getPosition()); // force teleport to previous position
                    System.out.println(player.getUsername() + " moved too fast! " + dx + " " + dy + " " + dz);
                }
            });

            player.addEventCallback(PlayerBlockBreakEvent.class, event -> {
                VanillaBlocks.dropOnBreak(player.getInstance(), event.getBlockPosition());
            });

            final Sidebar sidebar = new Sidebar("gen data");
            sidebar.createLine(new Sidebar.ScoreboardLine("id1", ColoredText.of(""), 1));
            sidebar.createLine(new Sidebar.ScoreboardLine("id2", ColoredText.of(""), 2));
            sidebar.createLine(new Sidebar.ScoreboardLine("id3", ColoredText.of(""), 3));
            sidebar.createLine(new Sidebar.ScoreboardLine("id4", ColoredText.of(""), 4));
            sidebar.createLine(new Sidebar.ScoreboardLine("id5", ColoredText.of(""), 5));
            player.addEventCallback(PlayerMoveEvent.class, event -> {
                final BlockPosition pos = event.getPlayer().getPosition().toBlockPosition();
                sidebar.updateLineContent("id1", ColoredText.of("weirdness: " + round(noiseTestGenerator.getWeirdness(pos.getX(), pos.getZ()))));
                sidebar.updateLineContent("id2", ColoredText.of("temperature: " + round(noiseTestGenerator.getTemperature(pos.getX(), pos.getZ()))));
                sidebar.updateLineContent("id3", ColoredText.of("humidity: " + round(noiseTestGenerator.getHumidity(pos.getX(), pos.getZ()))));
                sidebar.updateLineContent("id4", ColoredText.of("biome: " + noiseTestGenerator.getBiome(pos.getX(), pos.getZ()).getBiome().getName()));
                sidebar.updateLineContent("id5", ColoredText.of("smooth?: " + noiseTestGenerator.shouldSmooth(pos.getX(), pos.getZ())));
            });

            player.addEventCallback(PlayerSpawnEvent.class, event -> {
                sidebar.addViewer(player);
                if (event.isFirstSpawn()) {
                    player.setGameMode(GameMode.CREATIVE);
                    player.teleport(new Position(185, 100, 227));
                    player.getInventory().addItemStack(new ItemStack(Material.OBSIDIAN, (byte) 1));
                    player.getInventory().addItemStack(new ItemStack(Material.FLINT_AND_STEEL, (byte) 1));
                    player.getInventory().addItemStack(new ItemStack(Material.RED_BED, (byte) 1));
                }
            });

            player.addEventCallback(PickupItemEvent.class, event -> {
                final boolean couldAdd = player.getInventory().addItemStack(event.getItemStack());
                event.setCancelled(!couldAdd); // Cancel event if player does not have enough inventory space
            });

            player.addEventCallback(ItemDropEvent.class, event -> {
                final ItemStack droppedItem = event.getItemStack();

                final ItemEntity itemEntity = new ItemEntity(droppedItem, player.getPosition().clone().add(0, 1.5f, 0));
                itemEntity.setPickupDelay(500, TimeUnit.MILLISECOND);
                itemEntity.setInstance(player.getInstance());
                final Vector velocity = player.getPosition().clone().getDirection().multiply(6);
                itemEntity.setVelocity(velocity);
            });

            //Water puts out fire
            player.addEventCallback(PlayerChunkUnloadEvent.class, event -> {
                final Instance instance = player.getInstance();

                final Chunk chunk = instance.getChunk(event.getChunkX(), event.getChunkZ());

                if (chunk == null)
                    return;

                // Unload the chunk (save memory) if it has no remaining viewer
                if (chunk.getViewers().isEmpty()) player.getInstance().unloadChunk(chunk);
            });

            //Water puts out fire
            player.addEventCallback(PlayerTickEvent.class, event -> {
                if (event.getPlayer().isOnFire())
                    if (Block.fromStateId(event.getPlayer().getInstance().getBlockStateId(event.getPlayer().getPosition().toBlockPosition())).equals(Block.WATER))
                        event.getPlayer().setOnFire(false);
            });
        });


    }

    public static double round(final double d) {
        BigDecimal bd = BigDecimal.valueOf(d);
        bd = bd.setScale(3, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }
}
