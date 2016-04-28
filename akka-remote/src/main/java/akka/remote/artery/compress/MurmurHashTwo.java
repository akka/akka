/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress;


/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

import java.io.UnsupportedEncodingException;

/**
 * TODO we already have murmur, extend those facilities?
 * <p>
 * This is copied from https://github.com/addthis/stream-lib/blob/master/src/main/java/com/clearspring/analytics/hash/MurmurHash.java
 * Which is Apache V2 licensed.
 * <p></p>
 * This is a very fast, non-cryptographic hash suitable for general hash-based
 * lookup. See http://murmurhash.googlepages.com/ for more details.
 * <p/>
 * <p>
 * The C version of MurmurHash 2.0 found at that site was ported to Java by
 * Andrzej Bialecki (ab at getopt org).
 * </p>
 */
@Deprecated
public class MurmurHashTwo {

  public static int hash(Object o) {
    if (o == null) {
      return 0;
    }
    if (o instanceof Long) {
      return hashLong((Long) o);
    }
    if (o instanceof Integer) {
      return hashLong((Integer) o);
    }
    if (o instanceof Double) {
      return hashLong(Double.doubleToRawLongBits((Double) o));
    }
    if (o instanceof Float) {
      return hashLong(Float.floatToRawIntBits((Float) o));
    }
    if (o instanceof String) {
      return hash(((String) o).getBytes());
    }
    if (o instanceof byte[]) {
      return hash((byte[]) o);
    }
    return hash(o.toString());
  }

  public static int hash(byte[] data) {
    return hash(data, data.length, -1);
  }

  public static int hash(byte[] data, int seed) {
    return hash(data, data.length, seed);
  }

  public static int hash(byte[] data, int length, int seed) {
    int m = 0x5bd1e995;
    int r = 24;

    int h = seed ^ length;

    int len_4 = length >> 2;

    for (int i = 0; i < len_4; i++) {
      int i_4 = i << 2;
      int k = data[i_4 + 3];
      k = k << 8;
      k = k | (data[i_4 + 2] & 0xff);
      k = k << 8;
      k = k | (data[i_4 + 1] & 0xff);
      k = k << 8;
      k = k | (data[i_4 + 0] & 0xff);
      k *= m;
      k ^= k >>> r;
      k *= m;
      h *= m;
      h ^= k;
    }

    // avoid calculating modulo
    int len_m = len_4 << 2;
    int left = length - len_m;

    if (left != 0) {
      if (left >= 3) {
        h ^= (int) data[length - 3] << 16;
      }
      if (left >= 2) {
        h ^= (int) data[length - 2] << 8;
      }
      if (left >= 1) {
        h ^= (int) data[length - 1];
      }

      h *= m;
    }

    h ^= h >>> 13;
    h *= m;
    h ^= h >>> 15;

    return h;
  }

  public static int hashLong(long data) {
    int m = 0x5bd1e995;
    int r = 24;

    int h = 0;

    int k = (int) data * m;
    k ^= k >>> r;
    h ^= k * m;

    k = (int) (data >> 32) * m;
    k ^= k >>> r;
    h *= m;
    h ^= k * m;

    h ^= h >>> 13;
    h *= m;
    h ^= h >>> 15;

    return h;
  }

  /**
   * Generates 64 bit hash from byte array with default seed value.
   *
   * @param data   byte array to hash
   * @param length length of the array to hash
   * @return 64 bit hash of the given string
   */
  public static long hash64(final byte[] data, int length) {
    return hash64(data, length, 0xe17a1465);
  }


  /**
   * Generates 64 bit hash from byte array of the given length and seed.
   *
   * @param data   byte array to hash
   * @param length length of the array to hash
   * @param seed   initial seed value
   * @return 64 bit hash of the given array
   */
  public static long hash64(final byte[] data, int length, int seed) {
    final long m = 0xc6a4a7935bd1e995L;
    final int r = 47;

    long h = (seed & 0xffffffffl) ^ (length * m);

    int length8 = length / 8;

    for (int i = 0; i < length8; i++) {
      final int i8 = i * 8;
      long k = ((long) data[i8 + 0] & 0xff) + (((long) data[i8 + 1] & 0xff) << 8)
        + (((long) data[i8 + 2] & 0xff) << 16) + (((long) data[i8 + 3] & 0xff) << 24)
        + (((long) data[i8 + 4] & 0xff) << 32) + (((long) data[i8 + 5] & 0xff) << 40)
        + (((long) data[i8 + 6] & 0xff) << 48) + (((long) data[i8 + 7] & 0xff) << 56);

      k *= m;
      k ^= k >>> r;
      k *= m;

      h ^= k;
      h *= m;
    }

    switch (length % 8) {
      case 7:
        h ^= (long) (data[(length & ~7) + 6] & 0xff) << 48;
      case 6:
        h ^= (long) (data[(length & ~7) + 5] & 0xff) << 40;
      case 5:
        h ^= (long) (data[(length & ~7) + 4] & 0xff) << 32;
      case 4:
        h ^= (long) (data[(length & ~7) + 3] & 0xff) << 24;
      case 3:
        h ^= (long) (data[(length & ~7) + 2] & 0xff) << 16;
      case 2:
        h ^= (long) (data[(length & ~7) + 1] & 0xff) << 8;
      case 1:
        h ^= (long) (data[length & ~7] & 0xff);
        h *= m;
    }

    h ^= h >>> r;
    h *= m;
    h ^= h >>> r;

    return h;
  }

  // Murmur is faster than an SHA-based approach and provides as-good collision
  // resistance.  The combinatorial generation approach described in
  // http://www.eecs.harvard.edu/~kirsch/pubs/bbbf/esa06.pdf
  // does prove to work in actual tests, and is obviously faster
  // than performing further iterations of murmur.
  public static int[] hashBuckets(String key, int hashCount, int max) {
    byte[] b;
    try {
      b = key.getBytes("UTF-16");
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
    return hashBuckets(b, hashCount, max);
  }

  static int[] hashBuckets(byte[] b, int hashCount, int max) {
    int[] result = new int[hashCount];
    int hash1 = hash(b, b.length, 0);
    int hash2 = hash(b, b.length, hash1);
    for (int i = 0; i < hashCount; i++) {
      result[i] = Math.abs((hash1 + i * hash2) % max);
    }
    return result;
  }
}
