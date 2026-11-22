package org.junit;
import java.util.Arrays;
public final class Assert {
    private Assert() {}
    public interface ThrowingRunnable { void run() throws Throwable; }
    private static void fail0(String m) { throw new AssertionError(m); }
    public static void fail(String m) { fail0(m); }
    public static void assertTrue(boolean c) { if (!c) fail0("expected true"); }
    public static void assertTrue(String m, boolean c) { if (!c) fail0(m); }
    public static void assertFalse(boolean c) { if (c) fail0("expected false"); }
    public static void assertFalse(String m, boolean c) { if (c) fail0(m); }
    public static void assertNull(Object o) { if (o != null) fail0("expected null but was " + o); }
    public static void assertNotNull(Object o) { if (o == null) fail0("expected non-null"); }
    public static void assertNotNull(String m, Object o) { if (o == null) fail0(m); }
    public static void assertEquals(Object e, Object a) { if (!(e == null ? a == null : e.equals(a))) fail0("expected <" + e + "> but was <" + a + ">"); }
    public static void assertEquals(String m, Object e, Object a) { if (!(e == null ? a == null : e.equals(a))) fail0(m + ": expected <" + e + "> but was <" + a + ">"); }
    public static void assertEquals(long e, long a) { if (e != a) fail0("expected <" + e + "> but was <" + a + ">"); }
    public static void assertEquals(String m, long e, long a) { if (e != a) fail0(m + ": expected <" + e + "> but was <" + a + ">"); }
    public static void assertEquals(float e, float a, float d) { if (Math.abs(e - a) > d) fail0("expected <" + e + "> but was <" + a + ">"); }
    public static void assertEquals(double e, double a, double d) { if (Math.abs(e - a) > d) fail0("expected <" + e + "> but was <" + a + ">"); }
    public static void assertArrayEquals(boolean[] e, boolean[] a) { if (!Arrays.equals(e, a)) fail0("arrays differ"); }
    public static void assertArrayEquals(String m, boolean[] e, boolean[] a) { if (!Arrays.equals(e, a)) fail0(m); }
    public static void assertArrayEquals(float[] e, float[] a, float d) {
        if (e.length != a.length) fail0("array sizes differ");
        for (int i = 0; i < e.length; i++) if (Math.abs(e[i] - a[i]) > d) fail0("arrays differ at " + i);
    }
    public static <T extends Throwable> T assertThrows(Class<T> type, ThrowingRunnable r) {
        try { r.run(); } catch (Throwable t) {
            if (type.isInstance(t)) return type.cast(t);
            throw new AssertionError("expected " + type.getName() + " but got " + t);
        }
        throw new AssertionError("expected " + type.getName() + " but nothing was thrown");
    }
}
