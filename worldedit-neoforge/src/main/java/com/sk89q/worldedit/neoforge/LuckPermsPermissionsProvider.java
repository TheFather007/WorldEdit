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
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exposes WorldEdit's permissions to NeoForge's {@link PermissionAPI} and resolves
 * permission checks through it, so a permission manager such as LuckPerms (which
 * registers itself as the active NeoForge permission handler) can control them.
 *
 * <p>WorldEdit's permissions are registered as {@link PermissionNode}s during
 * {@link PermissionGatherEvent.Nodes}: the command permissions (collected via
 * {@link #registerPermission}) plus the {@link #EXTRA_PERMISSIONS} that WorldEdit
 * checks in code rather than as command conditions. Checks are then resolved with
 * {@link PermissionAPI#getPermission}, which dispatches to the active handler.</p>
 *
 * <p>Each node's default resolver falls back to the vanilla op/creative/cheat
 * checks, so behaviour is unchanged when no permission manager is installed, while
 * an installed manager takes precedence whenever it defines the permission.</p>
 *
 * <p>Note: {@link PermissionAPI#getPermission} only works for nodes registered
 * during the gather event. WorldEdit's statically-known permissions are all
 * registered, but truly dynamic ones (e.g. {@code worldedit.scripting.execute.<file>},
 * whose set is unbounded) cannot be pre-registered and fall back to the vanilla
 * checks. Resolving those would require calling the handler directly, which is not
 * possible through NeoForge's public API.</p>
 */
public class LuckPermsPermissionsProvider implements NeoForgePermissionsProvider {

    private static final Logger LOGGER = LogManagerCompat.getLogger();

    /**
     * Permissions WorldEdit checks in code rather than as command conditions. These
     * are registered so that {@link PermissionAPI#getPermission} can resolve them
     * (and so they appear in permission-manager editors).
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
    }

    @Override
    public boolean hasPermission(ServerPlayer player, String permission) {
        PermissionNode<Boolean> node = nodes.get(permission);
        if (node != null) {
            try {
                Boolean result = PermissionAPI.getPermission(player, node);
                if (result != null) {
                    return result;
                }
            } catch (Throwable t) {
                // The node is not registered with the active handler (e.g. a permission
                // registered after the gather event); fall back to the vanilla checks.
                LOGGER.debug("PermissionAPI could not resolve {}; falling back to vanilla checks", permission, t);
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
        // "worldedit.selection.pos") at the first dot reproduces the exact original string,
        // which is what PermissionNode#getNodeName returns and what LuckPerms matches against.
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
     * Registers all of WorldEdit's collected permission nodes with NeoForge's
     * {@link PermissionAPI} so that {@link PermissionAPI#getPermission} can resolve
     * them and they appear in permission-manager editors.
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
