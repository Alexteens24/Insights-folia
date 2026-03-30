package dev.frankheijden.insights.tasks;

import dev.frankheijden.insights.api.InsightsPlugin;
import dev.frankheijden.insights.api.concurrent.ScanOptions;
import dev.frankheijden.insights.api.objects.chunk.ChunkLocation;
import dev.frankheijden.insights.api.tasks.InsightsAsyncTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class PlayerTrackerTask extends InsightsAsyncTask {

    private final Map<ChunkLocation, Long> scanLocations = new ConcurrentHashMap<>();
    private static final Set<Integer> knownErrorStackTraceHashes = ConcurrentHashMap.newKeySet();

    public PlayerTrackerTask(InsightsPlugin plugin) {
        super(plugin);
    }

    @Override
    public void run() {
        var worldStorage = plugin.getWorldStorage();

        // Collect all online players; read their locations on their owning entity threads.
        List<CompletableFuture<List<ChunkLocation>>> perPlayerFutures = new ArrayList<>();
        for (Map.Entry<UUID, Player> entry : plugin.getPlayerList()) {
            Player player = entry.getValue();
            var future = new CompletableFuture<List<ChunkLocation>>();
            player.getScheduler().run(plugin, t -> {
                List<ChunkLocation> locs = new ArrayList<>();
                var location = player.getLocation();
                var world = location.getWorld();
                if (world == null) {
                    future.complete(locs);
                    return;
                }
                Set<Long> loadedChunks = worldStorage.getWorld(world.getUID()).getChunks();

                int chunkX = location.getBlockX() >> 4;
                int chunkZ = location.getBlockZ() >> 4;
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        var loc = new ChunkLocation(world, chunkX + x, chunkZ + z);
                        if (!loadedChunks.contains(loc.getKey()) && !scanLocations.containsKey(loc)) {
                            locs.add(loc);
                        }
                    }
                }
                future.complete(locs);
            }, () -> future.complete(List.of())); // player retired (logged out)

            perPlayerFutures.add(future);
        }

        // When all entity-thread reads are done, schedule chunk scans on the owning region threads.
        CompletableFuture.allOf(perPlayerFutures.toArray(new CompletableFuture[0]))
                .thenRun(() -> perPlayerFutures.forEach(f -> {
                    List<ChunkLocation> locations = f.join();
                    for (ChunkLocation loc : locations) {
                        var world = loc.getWorld();
                        Bukkit.getRegionScheduler().run(plugin, world, loc.getX(), loc.getZ(), t -> {
                            if (world.isChunkLoaded(loc.getX(), loc.getZ())) {
                                scanLocations.put(loc, System.nanoTime());
                                var chunk = world.getChunkAt(loc.getX(), loc.getZ());
                                plugin.getChunkContainerExecutor().submit(chunk, ScanOptions.all()).whenComplete((s, e) -> {
                                    if (s == null) {
                                        int hash = e.getStackTrace()[0].hashCode();
                                        if (!knownErrorStackTraceHashes.contains(hash)) {
                                            knownErrorStackTraceHashes.add(hash);
                                            plugin.getLogger().log(
                                                    Level.SEVERE,
                                                    "Error occurred while scanning "
                                                            + loc
                                                            + " (future errors with the same stacktrace are suppressed)",
                                                    e
                                            );
                                        }
                                    }
                                    scanLocations.remove(loc);
                                });
                            }
                        });
                    }
                }));
    }
}


