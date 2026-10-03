package com.zinzinc.recursivefactory.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.zinzinc.recursivefactory.compat.FactorySchematicMaterials;
import com.zinzinc.recursivefactory.compat.FactoryCannonAccess;
import com.zinzinc.recursivefactory.compat.FactoryCannonPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SchematicannonBlockEntity.class, remap = false)
public abstract class SchematicannonMaterialsMixin implements FactoryCannonAccess {
    @Shadow private boolean blockSkipped;
    @Unique private boolean recursivefactory$materialsPaid;
    @Unique private FactoryCannonPlan recursivefactory$plan = new FactoryCannonPlan();
    @Unique private CompoundTag recursivefactory$cursor;
    @Unique private boolean recursivefactory$resume;

    @Accessor("printerCooldown") public abstract int recursivefactory$cooldown();
    @Accessor("printerCooldown") public abstract void recursivefactory$cooldown(int value);
    @Invoker("shouldIgnoreBlockState") public abstract boolean recursivefactory$ignore(BlockState state, BlockEntity entity);
    @Invoker("launchBlockOrBelt") public abstract void recursivefactory$launch(BlockPos target, ItemStack icon, BlockState state, BlockEntity entity);

    @Inject(method = "tickPrinter", at = @At("HEAD"), cancellable = true)
    private void recursivefactory$resetPayment(CallbackInfo ci) {
        recursivefactory$materialsPaid = false;
        if (recursivefactory$plan.tick((SchematicannonBlockEntity) (Object) this)) {
            blockSkipped = false;
            ci.cancel();
        }
    }

    @Inject(method = "initializePrinter", at = @At(value = "INVOKE", target =
            "Lcom/simibubi/create/content/schematics/cannon/SchematicannonBlockEntity;updateChecklist()V"))
    private void recursivefactory$snapshot(ItemStack stack, CallbackInfo ci) {
        SchematicannonBlockEntity cannon = (SchematicannonBlockEntity) (Object) this;
        recursivefactory$plan.initialize(cannon, stack);
        if (recursivefactory$cursor != null) {
            cannon.printer.fromTag(recursivefactory$cursor, false);
            recursivefactory$cursor = null;
        }
        if (recursivefactory$resume) {
            cannon.state = SchematicannonBlockEntity.State.RUNNING;
            recursivefactory$resume = false;
        }
    }

    @Inject(method = "updateChecklist", at = @At("RETURN"))
    private void recursivefactory$roomMaterials(CallbackInfo ci) {
        recursivefactory$plan.checklist((SchematicannonBlockEntity) (Object) this);
    }

    @ModifyVariable(method = "launchBlock", at = @At("HEAD"), argsOnly = true)
    private CompoundTag recursivefactory$bindPaidEntrance(CompoundTag data, BlockPos target, ItemStack stack,
                                                        BlockState state, CompoundTag original) {
        if (!(state.getBlock() instanceof com.zinzinc.recursivefactory.block.RecursiveFactoryBlock)) return data;
        return recursivefactory$plan.entranceData((SchematicannonBlockEntity) (Object) this, target, data);
    }

    @Inject(method = "read", at = @At("RETURN"))
    private void recursivefactory$readPlan(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (clientPacket) return;
        recursivefactory$plan.read(tag.getCompound("FactoryPrinting"));
        boolean hasFactories = !tag.getCompound("FactoryPrinting").getList("Factories", 10).isEmpty();
        recursivefactory$cursor = hasFactories ? tag.getCompound("Printer").copy() : null;
        recursivefactory$resume = hasFactories && tag.getString("State").equals("RUNNING");
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void recursivefactory$writePlan(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (!clientPacket) tag.put("FactoryPrinting", recursivefactory$plan.write((SchematicannonBlockEntity) (Object) this));
    }

    @Inject(method = "finishedPrinting", at = @At("RETURN"))
    private void recursivefactory$finished(CallbackInfo ci) {
        recursivefactory$plan = new FactoryCannonPlan();
        recursivefactory$cursor = null;
    }

    // This point is after Create checks both the target chunk and whether it should replace the block.
    @Inject(method = "tickPrinter", at = @At(value = "INVOKE", target =
            "Lcom/simibubi/create/content/schematics/requirement/ItemRequirement;getRequiredItems()Ljava/util/List;"),
            cancellable = true)
    private void recursivefactory$payForRoom(CallbackInfo ci, @Local ItemRequirement requirement) {
        if (!(requirement instanceof FactorySchematicMaterials.Requirement)) return;
        SchematicannonBlockEntity cannon = (SchematicannonBlockEntity) (Object) this;
        ItemStack missing = cannon.hasCreativeCrate ? ItemStack.EMPTY
                : FactorySchematicMaterials.pay(requirement, cannon.attachedInventories, cannon.getLevel(), cannon.getBlockPos());
        if (missing.isEmpty()) {
            recursivefactory$materialsPaid = true;
            return;
        }
        if (cannon.skipMissing) {
            cannon.statusMsg = "skipping";
            blockSkipped = true;
            if (cannon.missingItem != null) {
                cannon.missingItem = null;
                cannon.state = SchematicannonBlockEntity.State.RUNNING;
            }
        } else {
            cannon.missingItem = missing;
            cannon.state = SchematicannonBlockEntity.State.PAUSED;
            cannon.statusMsg = "missingBlock";
        }
        cannon.sendUpdate = true;
        ci.cancel();
    }

    @WrapOperation(method = "tickPrinter", at = @At(value = "INVOKE", target =
            "Lcom/simibubi/create/content/schematics/cannon/SchematicannonBlockEntity;grabItemsFromAttachedInventories(Lcom/simibubi/create/content/schematics/requirement/ItemRequirement$StackRequirement;Z)Z"))
    private boolean recursivefactory$useRoomPayment(SchematicannonBlockEntity cannon,
            ItemRequirement.StackRequirement item, boolean simulate, Operation<Boolean> original) {
        return recursivefactory$materialsPaid || original.call(cannon, item, simulate);
    }
}
