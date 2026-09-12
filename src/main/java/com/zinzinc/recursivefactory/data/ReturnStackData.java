package com.zinzinc.recursivefactory.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

public record ReturnStackData(List<ReturnPoint> points) {
    public static final Codec<ReturnStackData> CODEC = ReturnPoint.CODEC.listOf()
            .xmap(ReturnStackData::new, ReturnStackData::points);

    public ReturnStackData {
        points = List.copyOf(points);
    }

    public static ReturnStackData empty() {
        return new ReturnStackData(List.of());
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    public ReturnStackData push(ReturnPoint point) {
        List<ReturnPoint> updated = new ArrayList<>(points);
        updated.add(point);
        return new ReturnStackData(updated);
    }

    public ReturnPoint peek() {
        if (points.isEmpty()) {
            throw new IllegalStateException("Return stack is empty");
        }
        return points.get(points.size() - 1);
    }

    public ReturnStackData pop() {
        if (points.isEmpty()) {
            return this;
        }
        List<ReturnPoint> updated = new ArrayList<>(points.subList(0, points.size() - 1));
        return new ReturnStackData(updated);
    }

    public record ReturnPoint(
            ResourceLocation dimension,
            double x,
            double y,
            double z,
            float yRot,
            float xRot
    ) {
        public static final Codec<ReturnPoint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("dimension").forGetter(ReturnPoint::dimension),
                Codec.DOUBLE.fieldOf("x").forGetter(ReturnPoint::x),
                Codec.DOUBLE.fieldOf("y").forGetter(ReturnPoint::y),
                Codec.DOUBLE.fieldOf("z").forGetter(ReturnPoint::z),
                Codec.FLOAT.fieldOf("y_rot").forGetter(ReturnPoint::yRot),
                Codec.FLOAT.fieldOf("x_rot").forGetter(ReturnPoint::xRot)
        ).apply(instance, ReturnPoint::new));

        public static ReturnPoint capture(ServerPlayer player) {
            return new ReturnPoint(
                    player.level().dimension().location(),
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    player.getYRot(),
                    player.getXRot()
            );
        }

        public ResourceKey<Level> dimensionKey() {
            return ResourceKey.create(Registries.DIMENSION, dimension);
        }
    }
}
