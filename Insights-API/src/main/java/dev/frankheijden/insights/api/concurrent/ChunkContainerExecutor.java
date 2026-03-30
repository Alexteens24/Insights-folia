package dev.frankheijden.insights.api.concurrent;

import dev.frankheijden.insights.api.InsightsPlugin;
import dev.frankheijden.insights.api.concurrent.containers.ChunkContainer;
import dev.frankheijden.insights.api.concurrent.containers.LoadedChunkContainer;
import dev.frankheijden.insights.api.concurrent.containers.RunnableContainer;
import dev.frankheijden.insights.api.concurrent.containers.SupplierContainer;
import dev.frankheijden.insights.api.concurrent.containers.UnloadedChunkContainer;
import dev.frankheijden.insights.api.concurrent.storage.Storage;
import dev.frankheijden.insights.api.concurrent.storage.WorldStorage;
import dev.frankheijden.insights.api.concurrent.tracker.WorldChunkScanTracker;
import dev.frankheijden.insights.api.exceptions.ChunkCuboidOutOfBoundsException;
import dev.frankheijden.insights.api.objects.chunk.ChunkCuboid;
import dev.frankheijden.insights.nms.core.InsightsNMS;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Decorator class for ContainerExecutor to add chunk scanning functionality.
 */
public class ChunkContainerExecutor implements ContainerExecutor {

    private final InsightsNMS nms;
    private final ContainerExecutor containerExecutor;
    private final WorldStorage worldStorage;
    private final WorldChunkScanTracker scanTracker;

    /**
     * Constructs a new ChunkContainerExecutor.
     */
    public ChunkContainerExecutor(
            InsightsNMS nms,
            ContainerExecutor containerExecutor,
            WorldStorage worldStorage,
            WorldChunkScanTracker scanTracker
    ) {
        this.nms = nms;
        this.containerExecutor = containerExecutor;
        this.worldStorage = worldStorage;
        this.scanTracker = scanTracker;
    }

    public CompletableFuture<Storage> submit(Chunk chunk) {
        return submit(chunk, ScanOptions.all());
    }

    public CompletableFuture<Storage> submit(World world, int x, int z) {
        return submit(world, x, z, ScanOptions.all());
    }

    public CompletableFuture<Storage> submit(Chunk chunk, ScanOptions options) {
        return submit(chunk, ChunkCuboid.maxCuboid(chunk.getWorld()), options);
    }

    public CompletableFuture<Storage> submit(World world, int x, int z, ScanOptions options) {
        return submit(world, x, z, ChunkCuboid.maxCuboid(world), options);
    }

    public CompletableFuture<Storage> submit(Chunk chunk, ChunkCuboid cuboid, ScanOptions options) {
        return submit(new LoadedChunkContainer(nms, chunk, cuboid, options), options);
    }

    /**
     * Schedules the loaded/unloaded decision on the chunk's owning region thread, then
     * runs a LoadedChunkContainer on that region thread or submits an UnloadedChunkContainer
     * to the background worker pool.
     */
    public CompletableFuture<Storage> submit(World world, int x, int z, ChunkCuboid cuboid, ScanOptions options) {
        var future = new CompletableFuture<Storage>();
        var plugin = InsightsPlugin.getInstance();
        Bukkit.getRegionScheduler().run(plugin, world, x, z, t -> {
            ChunkContainer container;
            if (world.isChunkLoaded(x, z)) {
                container = new LoadedChunkContainer(nms, world.getChunkAt(x, z), cuboid, options);
            } else {
                container = new UnloadedChunkContainer(nms, world, x, z, cuboid, options);
            }
            submitInternal(container, options).whenComplete((s, e) -> {
                if (e != null) {
                    future.completeExceptionally(e);
                } else {
                    future.complete(s);
                }
            });
        });
        return future;
    }

    /**
     * Submits a ChunkContainer for scanning, returning a DistributionStorage object.
     * DistributionStorage is essentially merged from the scan result and given entities Distribution.
     */
    public CompletableFuture<Storage> submit(ChunkContainer container, ScanOptions options) {
        return submitInternal(container, options);
    }

    /**
     * Submits a SupplierContainer for execution.
     * LoadedChunkContainer runs on the chunk's owning region thread.
     * All other containers run on the background worker pool.
     */
    @Override
    public <T> CompletableFuture<T> submit(SupplierContainer<T> container) {
        if (container instanceof LoadedChunkContainer loaded) {
            var future = new CompletableFuture<T>();
            var plugin = InsightsPlugin.getInstance();
            Runnable task = () -> {
                try {
                    @SuppressWarnings("unchecked")
                    T result = (T) loaded.get();
                    future.complete(result);
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            };
            if (Bukkit.isOwnedByCurrentRegion(loaded.getWorld(), loaded.getX() << 4, loaded.getZ() << 4)) {
                task.run();
            } else {
                Bukkit.getRegionScheduler().run(
                        plugin, loaded.getWorld(), loaded.getX(), loaded.getZ(), t -> task.run()
                );
            }
            return future;
        }
        return containerExecutor.submit(container);
    }

    @Override
    public CompletableFuture<Void> submit(RunnableContainer container) {
        return containerExecutor.submit(container);
    }

    private CompletableFuture<Storage> submitInternal(ChunkContainer container, ScanOptions options) {
        var world = container.getWorld();
        var maxCuboid = ChunkCuboid.maxCuboid(world);
        if (!maxCuboid.contains(container.getChunkCuboid())) {
            return CompletableFuture.failedFuture(new ChunkCuboidOutOfBoundsException(
                    maxCuboid,
                    container.getChunkCuboid()
            ));
        }

        UUID worldUid = world.getUID();
        long chunkKey = container.getChunkKey();
        if (options.track()) {
            scanTracker.set(worldUid, chunkKey, true);
        }

        return submit(container).<Storage>thenApply(storage -> {
            if (options.save()) {
                worldStorage.getWorld(worldUid).put(chunkKey, storage);
            }

            var metricsManager = InsightsPlugin.getInstance().getMetricsManager();
            metricsManager.getChunkScanMetric().increment();
            metricsManager.getTotalBlocksScanned().add(container.getChunkCuboid().getVolume());

            return storage;
        }).whenComplete((storage, ex) -> {
            if (options.track()) {
                scanTracker.set(worldUid, chunkKey, false);
            }
        });
    }

    @Override
    public void shutdown() {
        containerExecutor.shutdown();
    }
}

