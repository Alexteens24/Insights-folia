package dev.frankheijden.insights.api.config.notifications;

import dev.frankheijden.insights.api.InsightsPlugin;
import dev.frankheijden.insights.api.config.Messages;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BossBarNotification implements Notification {

    protected final InsightsPlugin plugin;
    protected final BossBar bossBar;
    protected final Messages.Message content;
    protected final Queue<Audience> receivers = new ConcurrentLinkedQueue<>();
    protected final Queue<Audience> viewers = new ConcurrentLinkedQueue<>();
    protected final int ticks;
    protected final Runnable bossBarClearer;
    protected ScheduledTask task;

    protected BossBarNotification(InsightsPlugin plugin, BossBar bossBar, Messages.Message content, int ticks) {
        this.plugin = plugin;
        this.bossBar = bossBar;
        this.content = content;
        this.ticks = ticks;
        this.bossBarClearer = () -> {
            Audience viewer;
            while ((viewer = viewers.poll()) != null) {
                viewer.hideBossBar(bossBar);
            }
        };
    }

    @Override
    public BossBarNotification add(Player player) {
        receivers.add(plugin.getMessages().getAudiences().player(player));
        return this;
    }

    @Override
    public SendableNotification create() {
        return new SendableNotification(content.resetTemplates()) {
            @Override
            public void send() {
                if (task != null) {
                    task.cancel();
                }
                bossBar.name(content.toComponent().orElse(Component.empty()));

                Audience audience;
                while ((audience = receivers.poll()) != null) {
                    audience.showBossBar(bossBar);
                    viewers.add(audience);
                }
                task = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> bossBarClearer.run(), ticks);
            }
        };
    }

    @Override
    public void clear() {
        bossBarClearer.run();
        if (task != null) {
            task.cancel();
        }
    }
}
