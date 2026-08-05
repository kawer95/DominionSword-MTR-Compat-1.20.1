package com.arxyt.dominionsword.mtr.client;

public final class MtrPortraitRenderState {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private MtrPortraitRenderState() {}
    public static void enter() { DEPTH.set(DEPTH.get() + 1); }
    public static void leave() { DEPTH.set(Math.max(0, DEPTH.get() - 1)); }
    public static boolean active() { return DEPTH.get() > 0; }
}
