package com.zinzinc.recursivefactory.mixin;

import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.power.FactoryWireSchematics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

@Mixin(StructureTemplate.class)
public abstract class FactoryWireCaptureMixin {
    @Shadow @Final private List<StructureTemplate.Palette> palettes;

    @Inject(method = "fillFromWorld", at = @At("RETURN"))
    private void recursivefactory$captureConnections(Level level, BlockPos origin, Vec3i size,
                                                     boolean entities, Block ignored, CallbackInfo ci) {
        if (!RecursiveFactory.powerAvailable()) return;
        for (StructureTemplate.Palette palette : palettes)
            FactoryWireSchematics.capture(level, origin, palette.blocks());
    }
}
