package com.zinzinc.recursivefactory.world;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The factories whose contents are being read out at this moment.
 *
 * <p>A copy is taken from a room that is standing still: a blueprint item reads a room out block for block.
 * A player walking into the room in the middle of that would be walking into a snapshot that is already
 * being taken, so the room is locked for as long as the read lasts and the entrance refuses to open (see
 * {@link FactoryTeleporter#enter}).
 *
 * <p>Only the read itself holds a lock, and only for the instant it takes, so a factory is never shut for
 * longer than the copy being taken of it. Printing from a blueprint is <em>not</em> a lock: a printer builds
 * in a room of its own and never touches the room the blueprint was taken from.
 *
 * <p>The lock is a count rather than a flag: two readers working over the same factory hold it twice, and
 * the factory only opens up again when the last of them lets go. Nothing about it is written to disk -
 * copying is something that happens while the server is running.
 */
public final class FactoryLocks {
    private static final Map<Integer, Integer> COPYING = new HashMap<>();

    private FactoryLocks() {
    }

    /** Notes that one more read of this factory is under way. */
    public static void lockCopy(int factoryId) {
        COPYING.merge(factoryId, 1, Integer::sum);
    }

    /** Lets go of one read of this factory; the last one lets the factory be entered again. */
    public static void unlockCopy(int factoryId) {
        COPYING.computeIfPresent(factoryId, (id, count) -> count <= 1 ? null : count - 1);
    }

    /** Runs something with a factory locked, and lets go of the lock again however it ends. */
    public static <T> T whileLocked(int factoryId, Supplier<T> work) {
        lockCopy(factoryId);
        try {
            return work.get();
        } finally {
            unlockCopy(factoryId);
        }
    }

    /** True while a factory's contents are being read out into a blueprint. */
    public static boolean isBeingCopied(int factoryId) {
        return COPYING.containsKey(factoryId);
    }
}