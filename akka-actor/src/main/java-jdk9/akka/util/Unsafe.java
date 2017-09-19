/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.util;

import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * INTERNAL API
 */
public final class Unsafe {
    public static final sun.misc.Unsafe instance;

    private static final long stringValueFieldOffset;

    static {
        try {
            sun.misc.Unsafe found = null;
            for (Field field : sun.misc.Unsafe.class.getDeclaredFields()) {
                if (field.getType() == sun.misc.Unsafe.class) {
                    field.setAccessible(true);
                    found = (sun.misc.Unsafe) field.get(null);
                    break;
                }
            }
            if (found == null) throw new IllegalStateException("Can't find instance of sun.misc.Unsafe");
            else instance = found;
            stringValueFieldOffset = instance.objectFieldOffset(String.class.getDeclaredField("value"));
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    public static void copyUSAsciiStrToBytes(String str, byte[] bytes) {
        final byte[] chars = (byte[]) instance.getObject(str, stringValueFieldOffset);
        System.arraycopy(chars, 0, bytes, 0, chars.length);
    }

    public static int fastHash(String str) {
        long s0 = 391408;
        long s1 = 601258;
        int i = 0;

        final byte[] chars = (byte[]) instance.getObject(str, stringValueFieldOffset);
        while (i < chars.length) {
            long x = s0 ^ (long)chars[i++]; // Mix character into PRNG state
            long y = s1;

            // Xorshift128+ round
            s0 = y;
            x ^= x << 23;
            y ^= y >>> 26;
            x ^= x >>> 17;
            s1 = x ^ y;
        }

        return (int)(s0 + s1);
    }
}
