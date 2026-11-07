package com.zinzinc.recursivefactory.probe;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.deployer.DeployerApplicationRecipe;
import com.simibubi.create.content.kinetics.press.PressingRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.data.FactoryColors;
import com.zinzinc.recursivefactory.data.ModDataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/** Uses registered recipes and Create's own lookup, progression and result rolling. */
final class AssemblyChecks {
    static void run(ServerLevel level, BiConsumer<Boolean, String> check) {
        var recipes = level.getRecipeManager();
        check.accept(recipes.getAllRecipesFor(RecipeType.CRAFTING).stream().noneMatch(holder ->
                        holder.value().getResultItem(level.registryAccess()).is(ModBlocks.RECURSIVE_FACTORY_ITEM.get())),
                "entrances cannot bypass assembly through direct crafting");
        for (DyeColor dye : DyeColor.values()) {
            int color = dye.getId();
            String label = dye.getName();
            var concrete = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(label + "_concrete")));
            var obsidian = new ItemStack(Items.OBSIDIAN);
            var grid = CraftingInput.of(3, 3, List.of(concrete, ItemStack.EMPTY, concrete,
                    obsidian, ItemStack.EMPTY, obsidian, concrete, obsidian, concrete));
            var crafting = recipes.getRecipeFor(RecipeType.CRAFTING, grid, level).orElseThrow();
            ItemStack frame = crafting.value().assemble(grid, level.registryAccess());
            check.accept(frame.is(ModBlocks.FACTORY_FRAME_ITEM.get()) && frame.getCount() == 1
                            && FactoryColors.colorOf(frame) == color,
                    label + " frame crafts from four concrete and three obsidian");
            var sequenceId = ResourceLocation.fromNamespaceAndPath("recursivefactory", "recursive_factory_" + label);
            var assembly = (SequencedAssemblyRecipe) recipes.byKey(sequenceId).orElseThrow().value();
            boolean exclusive = true;
            for (DyeColor other : DyeColor.values()) {
                ItemStack candidate = frame.copy();
                candidate.set(ModDataComponents.COLOR.get(), other.getId());
                exclusive &= assembly.getIngredient().test(candidate) == (other == dye);
            }
            check.accept(exclusive && assembly.getLoops() == 1 && assembly.getSequence().size() == 3
                            && assembly.getOutputChance() == 1 && assembly.resultPool.size() == 1,
                    label + " sequence has exclusive colour input, three steps, one loop and no scrap");
            check.accept(deploy(level, frame, new ItemStack(Items.ENDER_EYE)).isEmpty()
                            && press(level, frame).isEmpty(),
                    label + " cannot skip the precision mechanism step");
            ItemStack work = frame;
            for (int step = 0; step < 2; step++) {
                ItemStack held = step == 0 ? AllItems.PRECISION_MECHANISM.asStack() : new ItemStack(Items.ENDER_EYE);
                var recipe = deploy(level, work, held).orElseThrow();
                check.accept(recipe.id().equals(sequenceId) && !recipe.value().shouldKeepHeldItem(),
                        label + " deployer step " + step + " selects the correct recipe and consumes its material");
                var outputs = recipe.value().rollResults(level.random);
                work = outputs.getFirst();
                check.accept(outputs.size() == 1 && work.getCount() == 1 && work.is(ModBlocks.INCOMPLETE_FACTORY_ITEM.get())
                                && FactoryColors.colorOf(work) == color && work.has(AllDataComponents.SEQUENCED_ASSEMBLY),
                        label + " deployer step " + step + " produces one coloured intermediate with native progress");
                ItemStack restored = ItemStack.parse(level.registryAccess(), work.saveOptional(level.registryAccess())).orElseThrow();
                check.accept(ItemStack.isSameItemSameComponents(work, restored),
                        label + " intermediate step " + step + " survives item serialization");
                work = restored;
                check.accept(deploy(level, work, AllItems.PRECISION_MECHANISM.asStack()).isEmpty()
                                && (step == 0 ? press(level, work).isEmpty()
                                : deploy(level, work, new ItemStack(Items.ENDER_EYE)).isEmpty()),
                        label + " resumed step " + step + " rejects repeated or out-of-order operations");
            }
            var outputs = press(level, work).orElseThrow().value().rollResults(level.random);
            ItemStack result = outputs.getFirst();
            check.accept(outputs.size() == 1 && result.getCount() == 1 && result.is(ModBlocks.RECURSIVE_FACTORY_ITEM.get())
                            && FactoryColors.colorOf(result) == color && !result.has(AllDataComponents.SEQUENCED_ASSEMBLY),
                    label + " final press produces exactly one finished entrance of the original colour");
        }
    }

    private static Optional<RecipeHolder<DeployerApplicationRecipe>> deploy(ServerLevel level, ItemStack input, ItemStack held) {
        var inventory = new ItemStackHandler(2);
        inventory.setStackInSlot(0, input);
        inventory.setStackInSlot(1, held);
        return SequencedAssemblyRecipe.getRecipe(level, new RecipeWrapper(inventory),
                AllRecipeTypes.DEPLOYING.getType(), DeployerApplicationRecipe.class);
    }

    private static Optional<RecipeHolder<PressingRecipe>> press(ServerLevel level, ItemStack input) {
        return SequencedAssemblyRecipe.getRecipe(level, input, AllRecipeTypes.PRESSING.getType(), PressingRecipe.class);
    }
}
