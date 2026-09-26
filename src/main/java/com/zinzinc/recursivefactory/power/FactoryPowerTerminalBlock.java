package com.zinzinc.recursivefactory.power;

import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import com.george_vi.electroenergetics.foundation.base.SimpleElectricalDeviceBlock;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * One end of a factory's electrical link: a stub with a wire terminal on the face it answers on.
 *
 * <p>Put it against an entrance block and it is the outside end of that face; put it against the wall of
 * a room and it is the room's end of the side it stands on. The two ends of one face are tied together
 * (see FactoryPowerLinks), so a wire run into the terminal outside and a wire run out of the terminal
 * inside are the same run.
 *
 * <p>The second node of the block is not for the player: the electricity mod drives every port against a
 * zero potential, and this terminal's own reference is that hidden node at the block's centre.
 */
public final class FactoryPowerTerminalBlock extends SimpleElectricalDeviceBlock<FactoryPowerTerminalDevice> {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    /** How far the wire node sticks out of the block, towards the face it answers on. */
    private static final double NODE_OFFSET = 0.3D;
    private static final Vec3 CENTER = new Vec3(0.5D, 0.5D, 0.5D);
    private static final VoxelShape SHAPE = Block.box(4.0D, 4.0D, 4.0D, 12.0D, 12.0D, 12.0D);

    public FactoryPowerTerminalBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public SimulatedDeviceType<FactoryPowerTerminalDevice> getDevice() {
        return FactoryPower.TERMINAL_DEVICE.get();
    }

    @Override
    public Map<Integer, Vec3> getNodePositions(Level level, BlockPos pos, BlockState state) {
        return Map.of(0, wireNode(state), 1, CENTER);
    }

    @Override
    public @Nullable Vec3 getNodePosition(Level level, BlockPos pos, BlockState state, int id) {
        if (id == 0) {
            return wireNode(state);
        }
        return id == 1 ? CENTER : null;
    }

    @Override
    public boolean isNodeAccessible(Level level, BlockPos pos, BlockState state, int id) {
        return id == 0;
    }

    @Override
    public MutableComponent getNodeLabel(Level level, BlockPos pos, BlockState state, int id) {
        return Component.translatable(id == 0
                ? "recursivefactory.nodes.power_terminal"
                : "recursivefactory.nodes.power_reference");
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.getRotation(state.getValue(FACING)).rotate(state.getValue(FACING)));
    }

    /** Where the terminal stands is what ties it to a face, so say so when it ties to nothing. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel server
                && placer instanceof Player player
                && FactoryPowerLinks.endOf(server, pos) == null) {
            player.displayClientMessage(
                    Component.translatable("message.recursivefactory.power.unlinked"), true);
        }
    }

    private static Vec3 wireNode(BlockState state) {
        Direction facing = state.getValue(FACING);
        return CENTER.add(
                facing.getStepX() * NODE_OFFSET,
                facing.getStepY() * NODE_OFFSET,
                facing.getStepZ() * NODE_OFFSET);
    }
}
