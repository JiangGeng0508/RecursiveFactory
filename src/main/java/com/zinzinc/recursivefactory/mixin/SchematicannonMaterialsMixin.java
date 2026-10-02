package com.zinzinc.recursivefactory.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.zinzinc.recursivefactory.compat.FactorySchematicMaterials;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SchematicannonBlockEntity.class, remap = false)
public abstract class SchematicannonMaterialsMixin {
    @Shadow private boolean blockSkipped;
    @Unique private boolean recursivefactory$materialsPaid;

    @Inject(method = "tickPrinter", at = @At("HEAD"))
    private void recursivefactory$resetPayment(CallbackInfo ci) {
        recursivefactory$materialsPaid = false;
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
