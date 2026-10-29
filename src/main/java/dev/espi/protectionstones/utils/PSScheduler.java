/*
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.espi.protectionstones.utils;

import dev.espi.protectionstones.ProtectionStones;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/**
 * Thread-aware scheduling on top of Paper's region schedulers.
 *
 * <p>The same calls work on Paper/Purpur (where every non-async scheduler runs on the main thread) and on
 * Folia-based servers (where blocks belong to the region thread that owns their chunk, entities to the thread
 * that owns the entity, and world-agnostic state to the global region thread). There are no Folia-only branches.</p>
 *
 * <p>Methods named {@code run*} run the task immediately when the calling thread already owns the target, and
 * schedule it otherwise. Methods named {@code *NextTick} or {@code *Later} always schedule.</p>
 */
public final class PSScheduler {

    private static final long MS_PER_TICK = 50L;

    private PSScheduler() {
    }

    private static Plugin plugin() {
        return ProtectionStones.getInstance();
    }

    // -=-=-=- async (no world, block or entity access allowed) -=-=-=-

    public static ScheduledTask runAsync(Runnable task) {
        return Bukkit.getAsyncScheduler().runNow(plugin(), t -> task.run());
    }

    public static ScheduledTask runAsyncLater(Runnable task, long delayTicks) {
        return Bukkit.getAsyncScheduler().runDelayed(plugin(), t -> task.run(), Math.max(1L, delayTicks) * MS_PER_TICK, TimeUnit.MILLISECONDS);
    }

    public static ScheduledTask runAsyncTimer(Runnable task, long delayTicks, long periodTicks) {
        return Bukkit.getAsyncScheduler().runAtFixedRate(plugin(), t -> task.run(), Math.max(1L, delayTicks) * MS_PER_TICK, periodTicks * MS_PER_TICK, TimeUnit.MILLISECONDS);
    }

    // -=-=-=- global region (console commands, economy, other world-agnostic server state) -=-=-=-

    public static void runGlobal(Runnable task) {
        if (Bukkit.isGlobalTickThread()) {
            task.run();
        } else {
            Bukkit.getGlobalRegionScheduler().execute(plugin(), task);
        }
    }

    public static void runGlobalNextTick(Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin(), task);
    }

    public static ScheduledTask runGlobalLater(Runnable task, long delayTicks) {
        return Bukkit.getGlobalRegionScheduler().runDelayed(plugin(), t -> task.run(), Math.max(1L, delayTicks));
    }

    // -=-=-=- region owning a location (block reads and writes) -=-=-=-

    public static void runAt(Location location, Runnable task) {
        if (Bukkit.isOwnedByCurrentRegion(location)) {
            task.run();
        } else {
            Bukkit.getRegionScheduler().execute(plugin(), location, task);
        }
    }

    public static void runAtNextTick(Location location, Runnable task) {
        Bukkit.getRegionScheduler().execute(plugin(), location, task);
    }

    public static boolean isOwnedByCurrentRegion(Location location) {
        return Bukkit.isOwnedByCurrentRegion(location);
    }

    // -=-=-=- entity (player inventory, location, teleport, commands run as the player) -=-=-=-

    /**
     * Run a task on the thread owning the entity. If the entity is removed (player logged out) before the task
     * runs, the task is dropped.
     */
    public static void runFor(Entity entity, Runnable task) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            task.run();
        } else {
            entity.getScheduler().execute(plugin(), task, null, 1L);
        }
    }

    public static void runForNextTick(Entity entity, Runnable task) {
        entity.getScheduler().execute(plugin(), task, null, 1L);
    }

    public static ScheduledTask runForLater(Entity entity, Runnable task, Runnable retired, long delayTicks) {
        return entity.getScheduler().runDelayed(plugin(), t -> task.run(), retired, Math.max(1L, delayTicks));
    }

    public static ScheduledTask runForTimer(Entity entity, Runnable task, Runnable retired, long delayTicks, long periodTicks) {
        return entity.getScheduler().runAtFixedRate(plugin(), t -> task.run(), retired, Math.max(1L, delayTicks), periodTicks);
    }

    /**
     * Teleport a player with {@link Player#teleportAsync}, from the thread owning the player.
     * Fires PlayerTeleportEvent with cause PLUGIN, the same as {@link Player#teleport(Location)}.
     */
    public static void teleport(Player player, Location location) {
        runFor(player, () -> player.teleportAsync(location, PlayerTeleportEvent.TeleportCause.PLUGIN));
    }

    // -=-=-=- commands -=-=-=-

    /**
     * Dispatch a command as the console, from the global region thread.
     */
    public static void dispatchConsoleCommand(String command) {
        runGlobal(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    /**
     * Dispatch a command as a player, from the thread owning the player.
     */
    public static void dispatchPlayerCommand(Player player, String command) {
        runFor(player, () -> Bukkit.dispatchCommand(player, command));
    }
}
