package com.zinzinc.recursivefactory.preview;

import com.zinzinc.recursivefactory.block.entity.FactoryPreviewSource;
import com.zinzinc.recursivefactory.network.EndpointPreviewPackets;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

/** Versioned stream state is separate from both block saves and render state. */
public final class PreviewTransport {
    private static final Map<FactoryPreviewSource, State> SERVER = new WeakHashMap<>();
    private static final Map<FactoryPreviewSource, State> CLIENT = new WeakHashMap<>();
    private static State state(FactoryPreviewSource source) { return SERVER.computeIfAbsent(source, ignored -> new State()); }

    public static void noteSample(FactoryPreviewSource source, long tick, int interval) {
        State state = state(source); state.tick = tick; state.interval = interval;
    }
    public static void copyTiming(FactoryPreviewSource from, FactoryPreviewSource to) {
        State state = state(from); noteSample(to, state.tick, state.interval);
    }
    public static int sampledInterval(FactoryPreviewSource source) { return state(source).interval; }
    public static void setClientInterval(FactoryPreviewSource source, int interval) {
        CLIENT.computeIfAbsent(source, ignored -> new State()).interval = Math.max(1, Math.min(40, interval));
    }
    public static int interval(FactoryPreviewSource source) {
        State state = CLIENT.get(source);
        return state == null ? 1 : state.interval;
    }
    public static boolean synchronizedWithServer(FactoryPreviewSource source) { return CLIENT.containsKey(source) && CLIENT.get(source).snapshot != null; }
    public static void clearClient() { CLIENT.clear(); }

    public static void broadcast(FactoryPreviewSource source) {
        if (!(source.getLevel() instanceof ServerLevel level)) return;
        State state = state(source);
        CompoundTag next = source.previewTag();
        if (next.equals(state.snapshot)) return;
        CompoundTag data = state.snapshot == null ? next : PreviewDelta.between(state.snapshot, next);
        boolean full = state.snapshot == null;
        long base = state.revision++;
        state.snapshot = next;
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(source.getBlockPos()),
                new EndpointPreviewPackets.Sync(source.getBlockPos(), envelope(state, full, base, data)));
    }

    public static CompoundTag full(FactoryPreviewSource source) {
        State state = state(source);
        CompoundTag snapshot = source.previewTag();
        if (!snapshot.equals(state.snapshot)) {
            // Bring existing viewers to the same base before admitting a new viewer.
            broadcast(source);
        }
        return envelope(state, true, state.revision, state.snapshot);
    }

    private static CompoundTag envelope(State state, boolean full, long base, CompoundTag data) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Full", full);
        tag.putLong("Base", base);
        tag.putLong("Revision", state.revision);
        tag.putLong("SampleTick", state.tick);
        tag.putInt("Interval", state.interval);
        tag.put("Data", data.copy());
        return tag;
    }

    /** Null means a full snapshot must be requested; never apply a patch to the wrong room state. */
    public static CompoundTag receive(FactoryPreviewSource source, CompoundTag packet) {
        State previous = CLIENT.get(source);
        if (!packet.getBoolean("Full") && (previous == null || previous.snapshot == null || previous.revision != packet.getLong("Base"))) {
            CLIENT.remove(source); return null;
        }
        try {
            CompoundTag next = packet.getBoolean("Full") ? packet.getCompound("Data").copy()
                    : (CompoundTag) PreviewDelta.apply(previous.snapshot, packet.getCompound("Data"));
            State state = new State();
            state.snapshot = next;
            state.revision = packet.getLong("Revision");
            state.tick = packet.getLong("SampleTick");
            state.interval = Math.max(1, Math.min(40, packet.getInt("Interval")));
            CLIENT.put(source, state);
            return next;
        } catch (RuntimeException badPatch) {
            CLIENT.remove(source); return null;
        }
    }

    private static final class State {
        CompoundTag snapshot;
        long revision, tick;
        int interval = 1;
    }
}
