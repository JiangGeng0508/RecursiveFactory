package com.zinzinc.recursivefactory.client;

import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.network.FactoryDiagnosticPackets;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;

/** Face-specific readout; all settings and buffer facts are supplied by the server. */
public final class FactoryGoggles {
    private static final Map<EndpointBlockEntity, View> VIEWS = new WeakHashMap<>();

    public static boolean append(EndpointBlockEntity endpoint, List<Component> tooltip) {
        var minecraft = Minecraft.getInstance();
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || !hit.getBlockPos().equals(endpoint.getBlockPos())
                || endpoint.getLevel() == null) return false;
        View view = VIEWS.computeIfAbsent(endpoint, ignored -> new View());
        long now = endpoint.getLevel().getGameTime();
        if (now < view.requested || now - view.requested >= 10) {
            PacketDistributor.sendToServer(new FactoryDiagnosticPackets.Request(endpoint.getBlockPos(), hit.getDirection().ordinal()));
            view.requested = now;
        }
        tooltip.add(Component.translatable("goggles.recursivefactory.title").withStyle(ChatFormatting.GOLD));
        if (view.data == null || view.data.getInt("Face") != hit.getDirection().ordinal() || now - view.received > 40) {
            tooltip.add(Component.translatable("goggles.recursivefactory.waiting").withStyle(ChatFormatting.GRAY)); return true;
        }
        CompoundTag data = view.data;
        if (!data.getBoolean("Valid")) {
            tooltip.add(Component.translatable("goggles.recursivefactory.invalid").withStyle(ChatFormatting.GRAY)); return true;
        }
        Direction wall = Direction.values()[Math.floorMod(data.getInt("Wall"), 6)];
        tooltip.add(Component.translatable("goggles.recursivefactory.wall", direction(hit.getDirection()), direction(wall)));
        tooltip.add(Component.translatable("goggles.recursivefactory.redstone", enabled(data.getBoolean("Redstone"))));
        tooltip.add(Component.translatable("goggles.recursivefactory.transfer", enabled(data.getBoolean("Kinetic")),
                enabled(data.getBoolean("Items")), enabled(data.getBoolean("Fluid"))));
        tooltip.add(Component.translatable("goggles.recursivefactory.items", data.getInt("ItemCount"), bufferState(data, "Items", "ItemBlocked")));
        tooltip.add(Component.translatable("goggles.recursivefactory.fluid", data.getInt("FluidAmount"), bufferState(data, "Fluid", "FluidBlocked")));
        return true;
    }

    private static Component direction(Direction face) { return Component.translatable("goggles.recursivefactory.direction." + face.getName()); }
    private static Component enabled(boolean enabled) { return Component.translatable("goggles.recursivefactory." + (enabled ? "on" : "off")); }
    private static Component bufferState(CompoundTag data, String setting, String blocked) {
        return Component.translatable("goggles.recursivefactory." + (!data.getBoolean(setting) ? "disabled"
                : data.getBoolean(blocked) ? "blocked" : "ready"));
    }
    public static void receive(FactoryDiagnosticPackets.Sync packet) {
        var level = Minecraft.getInstance().level;
        if (level == null || !(level.getBlockEntity(packet.pos()) instanceof EndpointBlockEntity endpoint)) return;
        View view = VIEWS.computeIfAbsent(endpoint, ignored -> new View());
        view.data = packet.data().copy(); view.received = level.getGameTime();
    }
    public static void clear() { VIEWS.clear(); }
    private static final class View {
        long requested = Long.MIN_VALUE / 2, received;
        CompoundTag data;
    }
}
