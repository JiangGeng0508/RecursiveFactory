package com.zinzinc.recursivefactory.compat;

import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Pays for the whole room before a cannon launches its entrance block. */
public final class FactorySchematicMaterials {
    private FactorySchematicMaterials() {}

    /** Marks room requirements without changing Create's material checklist or projectile icon. */
    public static final class Requirement extends ItemRequirement {
        public Requirement(List<StackRequirement> items) {
            super(items);
        }
    }

    /** Returns the missing item, or EMPTY after all materials have been paid for. */
    public static ItemStack pay(ItemRequirement requirement, Iterable<IItemHandler> inventories,
                                Level level, BlockPos cannonPos) {
        List<Slot> slots = new ArrayList<>();
        Set<IItemHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (IItemHandler inventory : inventories) {
            if (inventory == null || !seen.add(inventory)) continue;
            for (int index = 0; index < inventory.getSlots(); index++) {
                ItemStack visible = inventory.getStackInSlot(index);
                if (visible.isEmpty()) continue;
                ItemStack available = inventory.extractItem(index, visible.getCount(), true);
                if (!available.isEmpty()) slots.add(new Slot(inventory, index, available.copy()));
            }
        }

        // Reserve in copies. Repeated stacks share the remaining inventory, and specific component
        // requirements get first choice so a generic requirement cannot spend their only match.
        List<ItemRequirement.StackRequirement> items = new ArrayList<>(requirement.getRequiredItems());
        items.sort(Comparator.comparingInt(item -> item instanceof ItemRequirement.StrictNbtStackRequirement ? 0 : 1));
        for (ItemRequirement.StackRequirement item : items) {
            int left = item.usage == ItemRequirement.ItemUseType.DAMAGE ? 1 : item.stack.getCount();
            for (Slot slot : slots) {
                if (slot.remaining.isEmpty() || !item.matches(slot.remaining)) continue;
                if (item.usage == ItemRequirement.ItemUseType.DAMAGE) {
                    if (!slot.remaining.isDamageableItem()) continue;
                    slot.remaining.setDamageValue(slot.remaining.getDamageValue() + 1);
                    // Match Create's tool-use semantics, including its break threshold.
                    if (slot.remaining.getDamageValue() > slot.remaining.getMaxDamage()) slot.remaining.shrink(1);
                    left = 0;
                } else {
                    int amount = Math.min(left, slot.remaining.getCount());
                    slot.remaining.shrink(amount);
                    left -= amount;
                }
                if (left == 0) break;
            }
            if (left > 0) return item.stack.copy();
        }

        // Keep withdrawals until every extraction agrees with the plan. A capability can change
        // between simulation and execution (or expose the same inventory through two wrappers).
        // In that case restore everything and let the cannon wait instead of launching for free.
        List<Withdrawal> withdrawals = new ArrayList<>();
        for (Slot slot : slots) {
            boolean changedTool = !slot.remaining.isEmpty()
                    && !ItemStack.isSameItemSameComponents(slot.original, slot.remaining);
            int amount = changedTool ? slot.original.getCount() : slot.original.getCount() - slot.remaining.getCount();
            if (amount == 0) continue;
            ItemStack extracted = slot.inventory.extractItem(slot.index, amount, false);
            if (!extracted.isEmpty()) withdrawals.add(new Withdrawal(slot, extracted.copy(),
                    changedTool ? slot.remaining.copy() : ItemStack.EMPTY));
            if (extracted.getCount() != amount || !ItemStack.isSameItemSameComponents(extracted, slot.original)) {
                for (int i = withdrawals.size() - 1; i >= 0; i--) {
                    Withdrawal withdrawal = withdrawals.get(i);
                    restore(withdrawal.slot, withdrawal.taken, inventories, level, cannonPos);
                }
                return slot.original.copyWithCount(amount);
            }
        }
        for (Withdrawal withdrawal : withdrawals) {
            if (!withdrawal.replacement.isEmpty()) restore(withdrawal.slot, withdrawal.replacement, inventories, level, cannonPos);
        }
        return ItemStack.EMPTY;
    }

    private static void restore(Slot slot, ItemStack stack, Iterable<IItemHandler> inventories,
                                Level level, BlockPos pos) {
        ItemStack remainder = slot.inventory.insertItem(slot.index, stack, false);
        for (IItemHandler inventory : inventories) {
            if (remainder.isEmpty()) return;
            if (inventory != null) remainder = ItemHandlerHelper.insertItem(inventory, remainder, false);
        }
        if (!remainder.isEmpty()) Block.popResource(level, pos, remainder);
    }

    private static final class Slot {
        final IItemHandler inventory;
        final int index;
        final ItemStack original;
        final ItemStack remaining;

        Slot(IItemHandler inventory, int index, ItemStack original) {
            this.inventory = inventory;
            this.index = index;
            this.original = original;
            this.remaining = original.copy();
        }
    }

    private record Withdrawal(Slot slot, ItemStack taken, ItemStack replacement) {}
}
