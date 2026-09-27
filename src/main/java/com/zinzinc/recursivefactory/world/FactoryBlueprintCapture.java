package com.zinzinc.recursivefactory.world;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.schematics.SchematicItem;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Taking a copy of a factory, done with Create's own blueprint: a blank blueprint clicked on an entrance
 * block is answered with a blueprint of the room that block leads into.
 *
 * <p>A factory's room stands in another dimension, which no blueprint tool can reach on its own - a Create
 * blueprint and quill reads the world the player is standing in, and the room is not in it. So the capture
 * is ours and the blueprint is Create's: what the player ends up holding is an ordinary Create blueprint,
 * written where Create keeps the blueprints it has been given ({@code schematics/uploaded/<player>}), and
 * the printer that builds from it reads it the same way Create's own machines do. What the file carries is
 * the factory's door - one entrance block, with the rooms the factory is made of written beside it (see
 * {@link FactoryBlueprint}) - so putting the file down with Create's cannon, or deploying it in creative,
 * puts that one block down and lets the block build the factory behind it, the factories nested inside it
 * and all.
 *
 * <p>What is copied is the factory the clicked entrance block leads into - including the factories standing
 * inside it, each of which is copied with its own room (see {@link FactoryBlueprint}). Nothing limits how
 * far a factory has grown: a room of several cells is copied cell by cell along with the rest.
 */
@EventBusSubscriber
public final class FactoryBlueprintCapture {
    private FactoryBlueprintCapture() {
    }

    /** A blueprint that was just taken: the Create blueprint item it is handed out as, and what went in. */
    public record Taken(ItemStack stack, int blocks, int rooms) {
    }

    /**
     * A blank Create blueprint clicked on an entrance block is a factory being copied. The click is taken
     * here rather than by the block, so that it works the same whether the player is sneaking or not: the
     * block's own answer to a right click is to walk the player into the room, and a blueprint is not a
     * request to be let in.
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack held = event.getItemStack();
        if (!held.is(AllItems.EMPTY_SCHEMATIC.get())) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (!(level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity entrance)
                || !entrance.hasFactoryId()) {
            return;
        }
        // The click belongs to the factory from here on: no block is placed, and no item use runs after it.
        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
        if (!(level instanceof ServerLevel serverLevel) || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        InteractionHand hand = event.getHand();
        try {
            Taken taken = take(serverLevel, pos, player.getGameProfile().getName());
            ItemStack blank = player.getItemInHand(hand);
            if (!player.isCreative()) {
                blank.shrink(1);
            }
            if (!player.getInventory().add(taken.stack())) {
                player.drop(taken.stack(), false);
            }
            player.displayClientMessage(Component.translatable("message.recursivefactory.blueprint.taken",
                    taken.blocks(), taken.rooms()), true);
        } catch (FactoryBlueprint.Refusal refusal) {
            // Something standing in the room, or inside a factory standing in it, cannot be copied as it
            // stands: the capture says which, and nothing is written.
            player.displayClientMessage(refusal.reason(), true);
        }
    }

    /**
     * Reads the factory the entrance block at {@code pos} leads into out into a Create blueprint file in
     * the folder of the player it is taken by, answering the blueprint item that file is handed out as.
     *
     * @throws FactoryBlueprint.Refusal when there is nothing behind the block to read, when a factory
     *     standing inside the room cannot be copied as it stands, or when the file cannot be written
     */
    public static Taken take(ServerLevel level, BlockPos pos, String owner) {
        if (!(level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity entrance)
                || !entrance.hasFactoryId()) {
            throw FactoryBlueprint.Refusal.of(Component.translatable("message.recursivefactory.missing"));
        }
        MinecraftServer server = level.getServer();
        ServerLevel roomLevel = server == null ? null : server.getLevel(FactoryDimension.LEVEL_KEY);
        if (server == null || roomLevel == null) {
            throw FactoryBlueprint.Refusal.of(Component.translatable("message.recursivefactory.missing"));
        }
        FactoryData data = FactoryData.get(server);
        FactoryData.FactoryRecord record = data.factory(entrance.getFactoryId());
        FactoryData.FactoryRecord.Cell cell = record == null ? null : record.anchorCell();
        if (record == null || cell == null) {
            throw FactoryBlueprint.Refusal.of(Component.translatable("message.recursivefactory.missing"));
        }

        // Nothing walks into a room while its contents are being read out of it - including the rooms of
        // the factories nested inside it, which are read out with it.
        FactoryBlueprint blueprint = FactoryLocks.whileLocked(record.id(),
                () -> FactoryBlueprint.capture(roomLevel, data, record, cell,
                        owner + FactoryBlueprint.OWNER_SEPARATOR + FactoryBlueprint.newName()));
        if (!blueprint.write(server)) {
            throw FactoryBlueprint.Refusal.of(Component.translatable("message.recursivefactory.blueprint.failed"));
        }
        // What is handed out is Create's own blueprint item: it carries the file and whose folder it is
        // in, and works out the size of what it stands for for itself.
        ItemStack stack = SchematicItem.create(level, fileOf(blueprint.name()), owner);
        return new Taken(stack, blueprint.size(), blueprint.rooms().size());
    }

    /**
     * The blueprint a Create blueprint item stands for, as the name our files are kept under: the folder
     * of the player it belongs to, then the file. Null for an item that carries no blueprint at all.
     */
    public static @Nullable String nameOf(ItemStack stack) {
        if (!stack.is(AllItems.SCHEMATIC.get())) {
            return null;
        }
        String owner = stack.get(AllDataComponents.SCHEMATIC_OWNER);
        String file = stack.get(AllDataComponents.SCHEMATIC_FILE);
        if (owner == null || file == null || owner.isEmpty() || file.isEmpty()) {
            return null;
        }
        return owner + FactoryBlueprint.OWNER_SEPARATOR + file;
    }

    /** The file half of a blueprint's name: the name a Create blueprint item carries. */
    private static String fileOf(String name) {
        int separator = name.lastIndexOf(FactoryBlueprint.OWNER_SEPARATOR);
        return separator < 0 ? name : name.substring(separator + 1);
    }
}
