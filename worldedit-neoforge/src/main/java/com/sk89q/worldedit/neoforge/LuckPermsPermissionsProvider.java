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

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A permissions provider that exposes WorldEdit's permissions to NeoForge's
 * {@link PermissionAPI}.
 *
 * <p>This is what allows permission managers such as LuckPerms - which register
 * themselves as the NeoForge permission handler - to see and control WorldEdit's
 * permission nodes. WorldEdit's command permissions (e.g. {@code worldedit.region.set})
 * are collected as {@link PermissionNode}s and registered during the
 * {@link PermissionGatherEvent.Nodes} event, which is when NeoForge gathers all
 * permission nodes for the active handler.</p>
 *
 * <p>Each node uses a default resolver backed by the {@code fallback} provider
 * (vanilla op/creative/cheat-mode checks). This means that when no permission
 * manager is installed, behaviour is identical to the vanilla provider, while an
 * installed manager (LuckPerms, etc.) takes precedence whenever it defines a value
 * for the node.</p>
 */
public class LuckPermsPermissionsProvider implements NeoForgePermissionsProvider {

    private final NeoForgePermissionsProvider fallback;
    private final Map<String, PermissionNode<Boolean>> nodes = new ConcurrentHashMap<>();
    private final Set<PermissionNode<Boolean>> gathered = ConcurrentHashMap.newKeySet();

    public LuckPermsPermissionsProvider(NeoForgePermissionsProvider fallback) {
        this.fallback = fallback;
    }

    @Override
    public boolean hasPermission(ServerPlayer player, String permission) {
        PermissionNode<Boolean> node = nodes.get(permission);
        // Only query the PermissionAPI for nodes that were actually registered with it
        // during the gather event. Otherwise the API throws, so fall back to vanilla.
        if (node != null && gathered.contains(node)) {
            return PermissionAPI.getPermission(player, node);
        }
        return fallback.hasPermission(player, permission);
    }

    @Override
    public void registerPermission(String permission) {
        nodes.computeIfAbsent(permission, this::createNode);
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

    /**
     * Registers all of WorldEdit's collected permission nodes with NeoForge's
     * {@link PermissionAPI} so that the active permission handler can resolve them.
     *
     * <p>Listens on the {@link net.neoforged.neoforge.common.NeoForge#EVENT_BUS} and
     * is fired after commands (and therefore their permissions) have been registered.</p>
     */
    @SubscribeEvent
    public void onPermissionGather(PermissionGatherEvent.Nodes event) {
        for (PermissionNode<Boolean> node : nodes.values()) {
            if (gathered.add(node)) {
                event.addNodes(node);
            }
        }
    }
}
