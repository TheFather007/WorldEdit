/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
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

package com.sk89q.worldedit.neoforge;

import com.sk89q.worldedit.internal.util.LogManagerCompat;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.platform.PlayerAdapter;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves WorldEdit's permission checks on NeoForge, in the following order.
 *
 * <ol>
 *   <li><b>LuckPerms</b> - when the LuckPerms mod is present, its API is queried directly and is
 *       authoritative: an explicit {@code TRUE} grants and an explicit {@code FALSE} denies, and
 *       an unset permission is denied too (no operator/creative fallback). This is string-based
 *       and works for every permission (command, in-code and dynamic) regardless of the NeoForge
 *       permission-handler configuration. Vanilla is used only if LuckPerms cannot be queried
 *       (not yet initialised or an error).</li>
 *   <li><b>NeoForge PermissionAPI</b> - when LuckPerms is absent, checks are resolved through
 *       {@link PermissionAPI#getPermission}, so any other permission manager registered as the
 *       active NeoForge handler is honoured.</li>
 *   <li><b>Vanilla</b> - operator/creative/cheat checks, used when LuckPerms is absent and the
 *       PermissionAPI does not define the permission (node not registered / no manager installed).</li>
 * </ol>
 *
 * <p>WorldEdit's permissions are registered with NeoForge's PermissionAPI during
 * {@link PermissionGatherEvent.Nodes}: this both feeds the PermissionAPI resolution path and
 * makes the permissions discoverable in a manager's editor. Each node's default resolver
 * delegates to the vanilla checks.</p>
 */
public class LuckPermsPermissionsProvider implements NeoForgePermissionsProvider {

    private static final Logger LOGGER = LogManagerCompat.getLogger();

    private static final boolean LUCKPERMS_LOADED = isLuckPermsLoaded();

    private static boolean isLuckPermsLoaded() {
        try {
            return ModList.get().isLoaded("luckperms");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Permissions WorldEdit checks in code rather than as command conditions. Registering them
     * lets the NeoForge PermissionAPI resolve them and makes them show up in a manager's editor.
     */
    private static final List<String> EXTRA_PERMISSIONS = List.of(
        "worldedit.anyblock",
        "worldedit.inventory.unrestricted",
        "worldedit.limit.unrestricted",
        "worldedit.timeout.unrestricted",
        "worldedit.override.bedrock",
        "worldedit.override.data-cycler",
        "worldedit.superpickaxe",
        "worldedit.superpickaxe.area",
        "worldedit.superpickaxe.recursive",
        "worldedit.butcher",
        "worldedit.butcher.ambient",
        "worldedit.butcher.animals",
        "worldedit.butcher.armorstands",
        "worldedit.butcher.golems",
        "worldedit.butcher.killed",
        "worldedit.butcher.npcs",
        "worldedit.butcher.pets",
        "worldedit.butcher.tagged",
        "worldedit.butcher.water",
        "worldedit.navigation.thru.tool",
        "worldedit.navigation.jumpto.tool",
        "worldedit.setnbt",
        "worldedit.selection.pos",
        "worldedit.error.detailed",
        "worldedit.scripting.execute"
    );

    private final NeoForgePermissionsProvider fallback;
    private final Map<String, PermissionNode<Boolean>> nodes = new ConcurrentHashMap<>();

    public LuckPermsPermissionsProvider(NeoForgePermissionsProvider fallback) {
        this.fallback = fallback;
        LOGGER.info("WorldEdit will resolve permissions via {}",
            LUCKPERMS_LOADED ? "LuckPerms" : "the NeoForge PermissionAPI");
    }

    @Override
    public boolean hasPermission(ServerPlayer player, String permission) {
        // 1. LuckPerms, when installed, is authoritative: its TRUE/FALSE (and "unset" as deny)
        //    is used directly. Vanilla is used only when LuckPerms cannot be queried (result null).
        if (LUCKPERMS_LOADED) {
            Boolean luckPerms = LuckPermsResolver.check(player, permission);
            if (luckPerms != null) {
                return luckPerms;
            }
            return fallback.hasPermission(player, permission);
        }
        // 2. Otherwise resolve through NeoForge's PermissionAPI (honours any other active handler).
        PermissionNode<Boolean> node = nodes.get(permission);
        if (node != null) {
            try {
                Boolean result = PermissionAPI.getPermission(player, node);
                if (result != null) {
                    return result;
                }
            } catch (Throwable t) {
                // The node is not registered with the active handler; fall through to vanilla.
                LOGGER.debug("PermissionAPI could not resolve {}; falling back to vanilla checks", permission, t);
            }
        }
        // 3. Vanilla operator/creative/cheat checks.
        return fallback.hasPermission(player, permission);
    }

    @Override
    public void registerPermission(String permission) {
        nodeFor(permission);
    }

    private PermissionNode<Boolean> nodeFor(String permission) {
        return nodes.computeIfAbsent(permission, this::createNode);
    }

    private PermissionNode<Boolean> createNode(String permission) {
        // NeoForge only exposes the (modId, nodeName) constructor publicly, which builds the
        // node name as "modId.nodeName". Splitting WorldEdit's dotted permission (e.g.
        // "worldedit.selection.pos") at the first dot reproduces the exact original string.
        int dot = permission.indexOf('.');
        String modId = dot >= 0 ? permission.substring(0, dot) : NeoForgeWorldEdit.MOD_ID;
        String nodeName = dot >= 0 ? permission.substring(dot + 1) : permission;
        return new PermissionNode<>(
            modId,
            nodeName,
            PermissionTypes.BOOLEAN,
            (player, playerUUID, context) -> player != null && fallback.hasPermission(player, permission)
        );
    }

    /**
     * Registers WorldEdit's collected permission nodes with NeoForge's PermissionAPI so they can
     * be resolved through it and are discoverable in a permission manager's editor.
     */
    @SubscribeEvent
    public void onPermissionGather(PermissionGatherEvent.Nodes event) {
        EXTRA_PERMISSIONS.forEach(this::nodeFor);
        for (PermissionNode<Boolean> node : nodes.values()) {
            try {
                event.addNodes(node);
            } catch (IllegalArgumentException alreadyRegistered) {
                // A node with this name was already gathered (e.g. by another mod); ignore.
            }
        }
    }

    /**
     * Isolates all references to the LuckPerms API so its classes are only loaded when LuckPerms
     * is installed (guarded by {@link #LUCKPERMS_LOADED}).
     */
    private static final class LuckPermsResolver {

        // LuckPerms' PlayerAdapter is stable for the whole server run; cache it.
        private static volatile PlayerAdapter<ServerPlayer> adapterCache;

        private LuckPermsResolver() {
        }

        /**
         * {@return {@code TRUE} to allow or {@code FALSE} to deny - LuckPerms is authoritative, so an
         * unset permission is denied as well - or {@code null} only when LuckPerms cannot be queried
         * (not initialised or an error), in which case the caller falls back to the vanilla checks}
         */
        static Boolean check(ServerPlayer player, String permission) {
            try {
                PlayerAdapter<ServerPlayer> adapter = adapterCache;
                if (adapter == null) {
                    // Throws IllegalStateException if LuckPerms is not ready yet.
                    adapter = LuckPermsProvider.get().getPlayerAdapter(ServerPlayer.class);
                    adapterCache = adapter;
                }
                User user = adapter.getUser(player);
                QueryOptions options = adapter.getQueryOptions(player);
                Tristate result = user.getCachedData().getPermissionData(options).checkPermission(permission);
                // LuckPerms is authoritative: an unset (UNDEFINED) permission means "not granted",
                // so it is denied rather than falling back to operator/creative.
                return switch (result) {
                    case TRUE -> Boolean.TRUE;
                    case FALSE, UNDEFINED -> Boolean.FALSE;
                };
            } catch (IllegalStateException notReady) {
                return null;
            } catch (Throwable t) {
                adapterCache = null; // adapter may be stale - refresh it next time
                LOGGER.debug("LuckPerms permission check failed for {}; falling back", permission, t);
                return null;
            }
        }
    }
}
