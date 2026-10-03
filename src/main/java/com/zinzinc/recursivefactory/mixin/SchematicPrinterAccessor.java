package com.zinzinc.recursivefactory.mixin;

import com.simibubi.create.content.schematics.SchematicPrinter;
import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SchematicPrinter.class, remap = false)
public interface SchematicPrinterAccessor {
    @Accessor("blockReader") SchematicLevel recursivefactory$reader();
    @Accessor("blockReader") void recursivefactory$reader(SchematicLevel value);
    @Accessor("schematicAnchor") void recursivefactory$anchor(BlockPos value);
    @Accessor("currentPos") void recursivefactory$position(BlockPos value);
    @Accessor("schematicLoaded") void recursivefactory$loaded(boolean value);
}
