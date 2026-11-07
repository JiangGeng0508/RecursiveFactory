package com.zinzinc.recursivefactory.client.ponder;

import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.block.ModBlocks;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

public final class FactoryPonderPlugin implements PonderPlugin {
    @Override public String getModId() { return RecursiveFactory.MODID; }
    @Override public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(ModBlocks.RECURSIVE_FACTORY.getId())
                .addStoryBoard("connections", FactoryScenes::connections)
                .addStoryBoard("expansion", FactoryScenes::expansion)
                .addStoryBoard("printing", FactoryScenes::printing);
        helper.forComponents(ModBlocks.FACTORY_BARRIER.getId()).addStoryBoard("connections", FactoryScenes::connections);
    }
}
