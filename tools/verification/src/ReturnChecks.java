package com.zinzinc.recursivefactory.probe;

import com.mojang.authlib.GameProfile;
import com.zinzinc.recursivefactory.data.ModAttachments;
import com.zinzinc.recursivefactory.data.ReturnStackData;
import com.zinzinc.recursivefactory.world.FactoryTeleporter;
import com.zinzinc.recursivefactory.world.FactoryReturnTarget;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

final class ReturnChecks {
    static void run(ServerLevel outside, ServerLevel inside, BiConsumer<Boolean, String> check) {
        BlockPos landing = new BlockPos(192, 160, 192);
        for (BlockPos floor : BlockPos.betweenClosed(landing.offset(-10, -1, -10), landing.offset(10, -1, 10)))
            outside.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        final ServerLevel[] sentLevel = {null};
        final Vec3[] sent = {null};
        FakePlayer player = new FakePlayer(inside, new GameProfile(UUID.randomUUID(), "rf-return")) {
            @Override public void teleportTo(ServerLevel target, double x, double y, double z, float yaw, float pitch) {
                sentLevel[0] = target; sent[0] = new Vec3(x, y, z);
            }
        };
        player.setPos(100000, 80, 100000);
        var point = new ReturnStackData.ReturnPoint(outside.dimension().location(), 192.5, 160, 192.5, 70, 20);
        var valid = ReturnStackData.empty().push(point);
        player.setData(ModAttachments.RETURN_STACK.get(), valid);
        check.accept(FactoryTeleporter.exitFromEdge(player) && sentLevel[0] == outside
                && sent[0].equals(new Vec3(192.5, 160, 192.5))
                && player.getData(ModAttachments.RETURN_STACK.get()).isEmpty(),
                "orphaned player uses saved return point without an existing room");
        var missing = new ReturnStackData.ReturnPoint(ResourceLocation.parse("recursivefactory:missing"), 0, 80, 0, 0, 0);
        var deleted = new ReturnStackData.ReturnPoint(inside.dimension().location(), 100000, 80, 100000, 0, 0);
        player.setData(ModAttachments.RETURN_STACK.get(), valid.push(missing).push(deleted));
        check.accept(FactoryTeleporter.exit(player, -1) && sentLevel[0] == outside
                && player.getData(ModAttachments.RETURN_STACK.get()).isEmpty(),
                "deleted parent room and missing dimension are skipped in the return stack");
        player.setData(ModAttachments.RETURN_STACK.get(), valid.push(point));
        check.accept(FactoryTeleporter.exit(player, -1)
                && player.getData(ModAttachments.RETURN_STACK.get()).points().size() == 1,
                "ordinary nested return consumes only the selected return point");
        outside.setBlockAndUpdate(landing, Blocks.STONE.defaultBlockState());
        var adjacent = FactoryReturnTarget.near(player, outside, new Vec3(192.5, 160, 192.5), 70, 20);
        check.accept(adjacent != null && !adjacent.position().equals(new Vec3(192.5, 160, 192.5))
                && adjacent.yaw() == 70 && adjacent.pitch() == 20,
                "obstructed return point resolves to a safe nearby landing and preserves orientation");
        check.accept(FactoryReturnTarget.near(player, inside, new Vec3(100000, 80, 100000), 0, 0) == null,
                "positions in deleted factory rooms cannot be selected as landings");
        player.setData(ModAttachments.RETURN_STACK.get(), ReturnStackData.empty());
        check.accept(FactoryTeleporter.exitFromEdge(player) && sentLevel[0] == outside,
                "empty return stack falls back to a safe overworld spawn");
        check.accept(FactoryReturnTarget.near(player, outside, new Vec3(Double.NaN, 160, 192), 0, 0) == null,
                "non-finite return coordinates are rejected");
    }
}
