package com.zinzinc.recursivefactory.preview;

import java.util.HashSet;
import java.util.Set;

/** A nested payload must never include a previous snapshot of an ancestor. */
public final class PreviewRecursion {
    public static final int MAX_DEPTH = 8;
    private static final ThreadLocal<Set<Object>> ACTIVE = ThreadLocal.withInitial(HashSet::new);

    public static boolean allows(Object source) {
        return ACTIVE.get().size() < MAX_DEPTH && !ACTIVE.get().contains(source);
    }

    public static boolean enter(Object source) {
        return allows(source) && ACTIVE.get().add(source);
    }

    public static void leave(Object source) {
        Set<Object> active = ACTIVE.get();
        active.remove(source);
        if (active.isEmpty()) ACTIVE.remove();
    }
}
