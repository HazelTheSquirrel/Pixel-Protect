package de.pixelprotect.util;

public final class ForensicGuard {
    private static final ThreadLocal<Integer> DEPTH=ThreadLocal.withInitial(()->0);
    private ForensicGuard(){}
    public static boolean active(){return DEPTH.get()>0;}
    public static Scope enter(){DEPTH.set(DEPTH.get()+1);return ()->DEPTH.set(Math.max(0,DEPTH.get()-1));}
    public interface Scope extends AutoCloseable{ @Override void close(); }
}
