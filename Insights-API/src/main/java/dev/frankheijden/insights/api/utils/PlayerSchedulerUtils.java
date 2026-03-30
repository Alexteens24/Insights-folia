package dev.frankheijden.insights.api.utils;

import dev.frankheijden.insights.api.InsightsPlugin;
import org.bukkit.entity.Player;

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
}