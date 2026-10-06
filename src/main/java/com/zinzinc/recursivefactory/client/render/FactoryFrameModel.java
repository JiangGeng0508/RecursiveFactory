package com.zinzinc.recursivefactory.client.render;

import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.block.ModBlocks;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

/** Adds the inside corners that the six connection properties alone cannot describe. */
public final class FactoryFrameModel extends BakedModelWrapper<BakedModel> {
    private static final ModelProperty<Integer> CONCAVE_EDGES = new ModelProperty<>();
    private static final List<Edge> EDGES = createEdges();
    private final List<BakedModel> bars;

    private FactoryFrameModel(BakedModel original, List<BakedModel> bars) {
        super(original);
        this.bars = bars;
    }

    public static void registerModels(ModelEvent.RegisterAdditional event) {
        for (Edge edge : EDGES) event.register(edge.model());
    }

    public static void wrapModels(ModelEvent.ModifyBakingResult event) {
        List<BakedModel> bars = EDGES.stream().map(edge -> event.getModels().get(edge.model())).toList();
        Map<BakedModel, FactoryFrameModel> wrappers = new IdentityHashMap<>();
        for (BlockState state : ModBlocks.RECURSIVE_FACTORY.get().getStateDefinition().getPossibleStates()) {
            ModelResourceLocation location = BlockModelShaper.stateToModelLocation(state);
            event.getModels().computeIfPresent(location,
                    (key, model) -> wrappers.computeIfAbsent(model, original -> new FactoryFrameModel(original, bars)));
        }
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
        BlockState[] neighbours = new BlockState[6];
        for (Direction side : Direction.values()) neighbours[side.ordinal()] = level.getBlockState(pos.relative(side));
        int mask = 0;
        for (int i = 0; i < EDGES.size(); i++) {
            Edge edge = EDGES.get(i);
            boolean a = connected(state, edge.a());
            boolean b = connected(state, edge.b());
            // A joined neighbour's connection describes the diagonal cell without querying its BE.
            // The server already keeps these connections separate for different factories.
            boolean diagonal = a && connected(neighbours[edge.a().ordinal()], edge.b())
                    || b && connected(neighbours[edge.b().ordinal()], edge.a());
            boolean horizontal = edge.a().getAxis() == Direction.Axis.Y || edge.b().getAxis() == Direction.Axis.Y;
            // A horizontal inside edge needs only the elbow cell's bar. Adding the two adjoining
            // cells' bars as well makes it two pixels wide. Vertical corners still wrap both walls.
            if (isConcave(a, b, diagonal) && (!horizontal || a && b)) mask |= 1 << i;
        }
        return super.getModelData(level, pos, state, data).derive().with(CONCAVE_EDGES, mask).build();
    }

    /** Three of the four cells around an edge form an inside corner; this cell is always present. */
    private static boolean isConcave(boolean a, boolean b, boolean diagonal) {
        return (a ? 1 : 0) + (b ? 1 : 0) + (diagonal ? 1 : 0) == 2;
    }

    private static boolean connected(BlockState state, Direction side) {
        return state.getOptionalValue(PipeBlock.PROPERTY_BY_DIRECTION.get(side)).orElse(false);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random,
                                   ModelData data, @Nullable RenderType renderType) {
        List<BakedQuad> original = super.getQuads(state, side, random, data, renderType);
        int mask = data.has(CONCAVE_EDGES) ? data.get(CONCAVE_EDGES) : 0;
        if (mask == 0) return original;
        List<BakedQuad> result = new ArrayList<>(original);
        for (int i = 0; i < bars.size(); i++) {
            if ((mask & (1 << i)) == 0) continue;
            BakedModel bar = bars.get(i);
            if (renderType == null || state != null && bar.getRenderTypes(state, random, ModelData.EMPTY).contains(renderType)) {
                result.addAll(bar.getQuads(state, side, random, ModelData.EMPTY, renderType));
            }
        }
        return result;
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
        ChunkRenderTypeSet original = super.getRenderTypes(state, random, data);
        if (!data.has(CONCAVE_EDGES) || data.get(CONCAVE_EDGES) == 0) return original;
        // A cell can have all six neighbours but still border a diagonal hole. Its multipart is empty.
        return ChunkRenderTypeSet.union(original, bars.get(0).getRenderTypes(state, random, ModelData.EMPTY));
    }

    private static List<Edge> createEdges() {
        List<Edge> edges = new ArrayList<>();
        for (Direction a : Direction.values()) {
            for (Direction b : Direction.values()) {
                if (a.ordinal() < b.ordinal() && a.getAxis() != b.getAxis()) edges.add(new Edge(a, b));
            }
        }
        return List.copyOf(edges);
    }

    private record Edge(Direction a, Direction b) {
        ModelResourceLocation model() {
            return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(RecursiveFactory.MODID,
                    "block/frame_edge_" + a.getSerializedName() + "_" + b.getSerializedName()));
        }
    }
}
