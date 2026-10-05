package com.zinzinc.recursivefactory.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.zinzinc.recursivefactory.compat.sable.SableAssembly;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.api.SubLevelAssemblyHelper", remap = false)
public abstract class SableAssemblyMixin {
    @WrapMethod(method = "moveBlocks")
    private static void recursivefactory$moveEntrances(ServerLevel level, @Coerce Object transform,
                                                       Iterable<BlockPos> blocks, Operation<Void> original) {
        try (SableAssembly move = new SableAssembly(level, transform, blocks)) {
            try {
                original.call(level, transform, blocks);
            } finally {
                move.finish();
            }
        }
    }
}
