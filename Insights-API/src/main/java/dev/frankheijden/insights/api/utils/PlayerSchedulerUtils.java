package dev.frankheijden.insights.api.utils;

import dev.frankheijden.insights.api.InsightsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class PlayerSchedulerUtils {

    private PlayerSchedulerUtils() {
        // Utility class
    }

    public static void run(InsightsPlugin plugin, Player player, Runnable runnable) {
        run(plugin, player, runnable, () -> {});
    }

    public static void run(InsightsPlugin plugin, Player player, Runnable runnable, Runnable retiredTask) {
        player.getScheduler().run(plugin, task -> runnable.run(), retiredTask);
    }

    public static <T> T call(
            InsightsPlugin plugin,
            Player player,
            Supplier<T> supplier,
            Supplier<T> retiredSupplier
    ) {
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            return supplier.get();
        }

        var future = new CompletableFuture<T>();
        player.getScheduler().run(plugin, task -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        }, () -> {
            try {
                future.complete(retiredSupplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future.join();
    }
}