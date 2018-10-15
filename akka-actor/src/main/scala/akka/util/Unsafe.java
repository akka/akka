/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
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
    private static final boolean isJavaVersion9Plus = JavaVersion.majorVersion() > 8;

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
        if (isJavaVersion9Plus) {
            final byte[] chars = (byte[]) instance.getObject(str, stringValueFieldOffset);
            System.arraycopy(chars, 0, bytes, 0, chars.length);
        } else {
            final char[] chars = (char[]) instance.getObject(str, stringValueFieldOffset);
            int i = 0;
            while (i < str.length()) {
                bytes[i] = (byte) chars[i++];
            }
        }
    }

    public static int fastHash(String str) {
        long s0 = 391408;
        long s1 = 601258;
        int i = 0;

        if (isJavaVersion9Plus) {
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
        } else {
            final char[] chars = (char[]) instance.getObject(str, stringValueFieldOffset);
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
        }

        return (int)(s0 + s1);
    }
}
