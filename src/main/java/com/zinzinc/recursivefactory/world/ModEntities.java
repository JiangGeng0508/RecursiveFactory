package com.zinzinc.recursivefactory.world;

import com.zinzinc.recursivefactory.RecursiveFactory;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registers the portal entity type the factories are built from. */
public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, RecursiveFactory.MODID);

    /**
     * Sized like the reference mod's portal, and tracked like Immersive Portals' own.
     *
     * <p>The plane's own size is set per portal and is what collision and rendering use, so the entity's box
     * only has to exist. The tracking range is the one number that must not be copied from the reference mod:
     * it tracks its portal six chunks out because its box is something you stand next to, while Immersive
     * Portals uses 96 for its own portals ({@code Portal.createPortalEntityType}, checked in the jar) - and a
     * portal entity that is out of tracking range is simply not on the client any more, so the doorway went
     * blank beyond 96 blocks no matter how far the view behind it reached.
     */
    private static final DeferredHolder<EntityType<?>, EntityType<?>> FACTORY_PORTAL =
            ENTITY_TYPES.register("factory_portal", () -> EntityType.Builder
                    .of(FactoryPortalEntity::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F)
                    .fireImmune()
                    .updateInterval(20)
                    .clientTrackingRange(96)
                    .build(RecursiveFactory.MODID + ":factory_portal"));

    private ModEntities() {
    }

    @SuppressWarnings("unchecked")
    public static EntityType<FactoryPortalEntity> factoryPortal() {
        return (EntityType<FactoryPortalEntity>) FACTORY_PORTAL.get();
    }

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
    }
}
