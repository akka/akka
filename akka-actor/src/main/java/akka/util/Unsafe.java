/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util;

import akka.annotation.InternalApi;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/** INTERNAL API */
@InternalApi
public final class Unsafe {
  public static final sun.misc.Unsafe instance;

  private static final VarHandle STRING_VALUE_HANDLE;
  private static final int copyUSAsciiStrToBytesAlgorithm;

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

      VarHandle stringValueHandle;
      try {
        var valueField = String.class.getDeclaredField("value");
        valueField.setAccessible(true);
        // Note: requires JVM flag --add-opens=java.base/java.lang=ALL-UNNAMED
        // FIXME could it be named module instead 'akka-actor'?
        stringValueHandle =
            MethodHandles.privateLookupIn(String.class, MethodHandles.lookup())
                .unreflectVarHandle(valueField);
      } catch (NoSuchFieldException
          | IllegalAccessException
          | java.lang.reflect.InaccessibleObjectException ex) {
        // The platform's implementation of String doesn't have a 'value' field, or we are not
        // allowed
        // to touch it, so we have to use algorithm 0
        stringValueHandle = null;
      }
      STRING_VALUE_HANDLE = stringValueHandle;

      if (STRING_VALUE_HANDLE != null) {
        // Select optimization algorithm for `copyUSAciiBytesToStr`.
        // For example algorithm 1 will fail with JDK 11 on ARM32 (Raspberry Pi),
        // and therefore algorithm 0 is selected on that architecture.
        String testStr = "abc";
        if (testUSAsciiStrToBytesAlgorithm1(testStr)) copyUSAsciiStrToBytesAlgorithm = 1;
        else if (testUSAsciiStrToBytesAlgorithm2(testStr)) copyUSAsciiStrToBytesAlgorithm = 2;
        else copyUSAsciiStrToBytesAlgorithm = 0;
      } else
        // We know so little about the platform's String implementation that we have
        // no choice but to select algorithm 0
        copyUSAsciiStrToBytesAlgorithm = 0;
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }

  static boolean testUSAsciiStrToBytesAlgorithm0(String str) {
    try {
      byte[] bytes = new byte[str.length()];

      // copy of implementation in copyUSAciiBytesToStr
      byte[] strBytes = str.getBytes(StandardCharsets.US_ASCII);
      System.arraycopy(strBytes, 0, bytes, 0, str.length());
      // end copy

      String result = copyUSAciiBytesToStr(str.length(), bytes);
      return str.equals(result);
    } catch (Throwable all) {
      return false;
    }
  }

  static boolean testUSAsciiStrToBytesAlgorithm1(String str) {
    try {
      byte[] bytes = new byte[str.length()];

      // copy of implementation in copyUSAciiBytesToStr
      final byte[] chars = (byte[]) STRING_VALUE_HANDLE.get(str);
      System.arraycopy(chars, 0, bytes, 0, str.length());
      // end copy

      String result = copyUSAciiBytesToStr(str.length(), bytes);
      return str.equals(result);
    } catch (Throwable all) {
      return false;
    }
  }

  static boolean testUSAsciiStrToBytesAlgorithm2(String str) {
    try {
      byte[] bytes = new byte[str.length()];

      // copy of implementation in copyUSAciiBytesToStr
      final char[] chars = (char[]) STRING_VALUE_HANDLE.get(str);
      int i = 0;
      while (i < str.length()) {
        bytes[i] = (byte) chars[i++];
      }
      // end copy

      String result = copyUSAciiBytesToStr(str.length(), bytes);
      return str.equals(result);
    } catch (Throwable all) {
      return false;
    }
  }

  private static String copyUSAciiBytesToStr(int length, byte[] bytes) {
    char[] resultChars = new char[length];
    int i = 0;
    while (i < length) {
      // UsAscii
      resultChars[i] = (char) bytes[i];
      i += 1;
    }
    return String.valueOf(resultChars, 0, length);
  }

  public static void copyUSAsciiStrToBytes(String str, byte[] bytes) {
    if (copyUSAsciiStrToBytesAlgorithm == 1) {
      final byte[] chars = (byte[]) STRING_VALUE_HANDLE.get(str);
      System.arraycopy(chars, 0, bytes, 0, str.length());
    } else if (copyUSAsciiStrToBytesAlgorithm == 2) {
      final char[] chars = (char[]) STRING_VALUE_HANDLE.get(str);
      int i = 0;
      while (i < str.length()) {
        bytes[i] = (byte) chars[i++];
      }
    } else {
      byte[] strBytes = str.getBytes(StandardCharsets.US_ASCII);
      System.arraycopy(strBytes, 0, bytes, 0, str.length());
    }
  }

  public static int fastHash(String str) {
    long s0 = 391408;
    long s1 = 601258;
    int i = 0;

    if (copyUSAsciiStrToBytesAlgorithm == 1) {
      final byte[] chars = (byte[]) STRING_VALUE_HANDLE.get(str);
      while (i < str.length()) {
        long x = s0 ^ (long) chars[i++]; // Mix character into PRNG state
        long y = s1;

        // Xorshift128+ round
        s0 = y;
        x ^= x << 23;
        y ^= y >>> 26;
        x ^= x >>> 17;
        s1 = x ^ y;
      }
    } else if (copyUSAsciiStrToBytesAlgorithm == 2) {
      final char[] chars = (char[]) STRING_VALUE_HANDLE.get(str);
      while (i < str.length()) {
        long x = s0 ^ (long) chars[i++]; // Mix character into PRNG state
        long y = s1;

        // Xorshift128+ round
        s0 = y;
        x ^= x << 23;
        y ^= y >>> 26;
        x ^= x >>> 17;
        s1 = x ^ y;
      }
    } else {
      byte[] chars = str.getBytes(StandardCharsets.US_ASCII);
      while (i < str.length()) {
        long x = s0 ^ (long) chars[i++]; // Mix character into PRNG state
        long y = s1;

        // Xorshift128+ round
        s0 = y;
        x ^= x << 23;
        y ^= y >>> 26;
        x ^= x >>> 17;
        s1 = x ^ y;
      }
    }

    return (int) (s0 + s1);
  }
}
