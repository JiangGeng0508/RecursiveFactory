package com.zinzinc.recursivefactory.mixin;

import com.simibubi.create.content.schematics.cannon.LaunchedItem;
import com.zinzinc.recursivefactory.compat.RoomLaunchedItem;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A cannon shot that prints a factory's contents is aimed at the entrance block the player can see, the
 * way every other shot is aimed at the block it will place - the factory's room stands in another dimension
 * and an animation there would be invisible. When the shot arrives, the level and position it is placed at
 * are swapped for the room's, so what lands is the block the shot was really for.
 *
 * <p>Both halves are carried on the shot and written to NBT, so a shot saved in the air still lands in its
 * room after a reload instead of dropping a room block on top of the entrance.
 */
@Mixin(value = LaunchedItem.class, remap = false)
public abstract class LaunchedItemRoomMixin implements RoomLaunchedItem {
    @Shadow public BlockPos target;
    @Unique private BlockPos recursivefactory$roomTarget;
    @Unique private int recursivefactory$roomId;

    @Override
    public void recursivefactory$landInRoom(BlockPos roomTarget, int roomId) {
        recursivefactory$roomTarget = roomTarget;
        recursivefactory$roomId = roomId;
    }

    @Override
    public BlockPos recursivefactory$roomTarget() {
        return recursivefactory$roomTarget;
    }

    @Override
    public int recursivefactory$roomId() {
        return recursivefactory$roomId;
    }

    @ModifyArg(method = "update", at = @At(value = "INVOKE", target =
            "Lcom/simibubi/create/content/schematics/cannon/LaunchedItem;place(Lnet/minecraft/world/level/Level;)V"), index = 0)
    private Level recursivefactory$landInsideRoom(Level world) {
        if (recursivefactory$roomTarget == null || world.isClientSide) return world;
        ServerLevel room = world.getServer() == null ? null
                : world.getServer().getLevel(FactoryDimension.LEVEL_KEY);
        if (room == null) return world;
        if (recursivefactory$roomId > 0) FactoryDimension.keepLoaded(recursivefactory$roomId);
        target = recursivefactory$roomTarget;
        return room;
    }

    @Inject(method = "serializeNBT", at = @At("RETURN"))
    private void recursivefactory$writeRoom(HolderLookup.Provider registries, CallbackInfoReturnable<CompoundTag> cir) {
        if (recursivefactory$roomTarget == null) return;
        CompoundTag tag = cir.getReturnValue();
        tag.putLong("RecursiveFactoryRoom", recursivefactory$roomTarget.asLong());
        tag.putInt("RecursiveFactoryRoomId", recursivefactory$roomId);
    }

    @Inject(method = "readNBT", at = @At("RETURN"))
    private void recursivefactory$readRoom(CompoundTag tag, HolderLookup.Provider registries,
                                           HolderGetter<Block> holderGetter, CallbackInfo ci) {
        if (!tag.contains("RecursiveFactoryRoom")) return;
        recursivefactory$roomTarget = BlockPos.of(tag.getLong("RecursiveFactoryRoom"));
        recursivefactory$roomId = tag.getInt("RecursiveFactoryRoomId");
    }
}