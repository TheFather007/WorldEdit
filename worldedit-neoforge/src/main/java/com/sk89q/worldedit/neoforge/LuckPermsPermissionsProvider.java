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
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.handler.IPermissionHandler;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes WorldEdit's permission checks through NeoForge's active permission
 * handler, so that a permission manager such as LuckPerms (which registers
 * itself as that handler) can see and control <em>all</em> of WorldEdit's
 * permissions.
 *
 * <h2>Why not just use {@link PermissionAPI#getPermission}?</h2>
 *
 * <p>NeoForge's public {@link PermissionAPI#getPermission} throws for any
 * {@link PermissionNode} that was not registered during
 * {@link PermissionGatherEvent.Nodes}. WorldEdit, however, checks permissions
 * as plain strings throughout its code base, including in-code checks that are
 * not command conditions (e.g. {@code worldedit.anyblock},
 * {@code worldedit.override.bedrock}) and unbounded dynamic ones (e.g.
 * {@code worldedit.scripting.execute.<filename>}). These can never be fully
 * pre-registered, so relying on {@code getPermission} would gate only a subset
 * of checks and silently fall back to operator/creative for the rest -
 * inconsistent and misleading.</p>
 *
 * <p>Instead we resolve every check against the active handler directly. Each
 * permission becomes a {@link PermissionNode} whose default resolver delegates
 * to the vanilla operator/creative {@code fallback}. As a result:</p>
 * <ul>
 *   <li>with LuckPerms (or any other handler) installed, it resolves the
 *       permission by node name - exactly like the string-based Fabric
 *       Permissions API integration - and uses the vanilla fallback only when
 *       the manager leaves the permission undefined;</li>
 *   <li>with no manager installed, NeoForge's default handler simply invokes
 *       the same vanilla fallback, so behaviour is unchanged.</li>
 * </ul>
 *
 * <p>The active handler is not exposed publicly, so it is accessed reflectively.
 * If that ever fails, every check degrades gracefully to the vanilla fallback.</p>
 */
public class LuckPermsPermissionsProvider implements NeoForgePermissionsProvider {

    private static final Logger LOGGER = LogManagerCompat.getLogger();

    /**
     * The active handler backing {@link PermissionAPI}. It is private with no
     * public accessor ({@link PermissionAPI#getActivePermissionHandler()} only
     * returns its identifier), so it is read reflectively. {@code null} if it
     * could not be accessed, in which case checks fall back to vanilla.
     */
    private static final Field ACTIVE_HANDLER_FIELD = resolveActiveHandlerField();

    private static Field resolveActiveHandlerField() {
        try {
            Field field = PermissionAPI.class.getDeclaredField("activeHandler");
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            LOGGER.warn("Could not access NeoForge's permission handler; WorldEdit permissions "
                + "will fall back to operator/creative checks and ignore permission managers", t);
            return null;
        }
    }

    /**
     * Permissions WorldEdit checks in code rather than as command conditions.
     * These are registered with the permission manager purely for discoverability
     * (so they appear in editors/completion); resolution does not depend on this
     * list, so a permission missing from it is still resolved correctly.
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
    }

    @Override
    public boolean hasPermission(ServerPlayer player, String permission) {
        IPermissionHandler handler = activeHandler();
        if (handler != null) {
            try {
                Boolean result = handler.getPermission(player, nodeFor(permission));
                if (result != null) {
                    return result;
                }
            } catch (Throwable t) {
                LOGGER.debug("Permission handler could not resolve {}; falling back to vanilla", permission, t);
            }
        }
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
        // "worldedit.region.set") at the first dot reproduces the exact original string, which
        // is what PermissionNode#getNodeName returns and what LuckPerms matches against.
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

    private static IPermissionHandler activeHandler() {
        if (ACTIVE_HANDLER_FIELD == null) {
            return null;
        }
        try {
            return (IPermissionHandler) ACTIVE_HANDLER_FIELD.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Registers the statically-known permissions with the active handler so they
     * are discoverable in permission-manager editors. This is cosmetic only -
     * {@link #hasPermission} resolves against the handler directly and does not
     * require a permission to be registered here.
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
}
