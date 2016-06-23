/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress;

import akka.actor.Actor;
import akka.actor.ActorRef;

import java.io.UnsupportedEncodingException;
import java.util.Random;

/**
 * INTERNAL API: Count-Min Sketch datastructure.
 *  
 * An Improved Data Stream Summary: The Count-Min Sketch and its Applications
 * https://web.archive.org/web/20060907232042/http://www.eecs.harvard.edu/~michaelm/CS222/countmin.pdf
 * 
 * This implementation is mostly taken and adjusted from the Apache V2 licensed project `stream-lib`, located here:
 * https://github.com/clearspring/stream-lib/blob/master/src/main/java/com/clearspring/analytics/stream/frequency/CountMinSketch.java
 */
public class CountMinSketch {

  public static final long PRIME_MODULUS = (1L << 31) - 1;

  private int depth;
  private int width;
  private long[][] table;
  private long[] hashA;
  private long size;
  private double eps;
  private double confidence;

  public CountMinSketch(int depth, int width, int seed) {
    this.depth = depth;
    this.width = width;
    this.eps = 2.0 / width;
    this.confidence = 1 - 1 / Math.pow(2, depth);
    initTablesWith(depth, width, seed);
  }

  public CountMinSketch(double epsOfTotalCount, double confidence, int seed) {
    // 2/w = eps ; w = 2/eps
    // 1/2^depth <= 1-confidence ; depth >= -log2 (1-confidence)
    this.eps = epsOfTotalCount;
    this.confidence = confidence;
    this.width = (int) Math.ceil(2 / epsOfTotalCount);
    this.depth = (int) Math.ceil(-Math.log(1 - confidence) / Math.log(2));
    initTablesWith(depth, width, seed);
  }

  CountMinSketch(int depth, int width, int size, long[] hashA, long[][] table) {
    this.depth = depth;
    this.width = width;
    this.eps = 2.0 / width;
    this.confidence = 1 - 1 / Math.pow(2, depth);
    this.hashA = hashA;
    this.table = table;
    this.size = size;
  }

  private void initTablesWith(int depth, int width, int seed) {
    this.table = new long[depth][width];
    this.hashA = new long[depth];
    Random r = new Random(seed);
    // We're using a linear hash functions
    // of the form (a*x+b) mod p.
    // a,b are chosen independently for each hash function.
    // However we can set b = 0 as all it does is shift the results
    // without compromising their uniformity or independence with
    // the other hashes.
    for (int i = 0; i < depth; ++i) {
      hashA[i] = r.nextInt(Integer.MAX_VALUE);
    }
  }

  /** Referred to as {@code epsilon} in the whitepaper */
  public double getRelativeError() {
    return eps;
  }

  public double getConfidence() {
    return confidence;
  }

  private int hash(long item, int i) {
    long hash = hashA[i] * item;
    // A super fast way of computing x mod 2^p-1
    // See http://www.cs.princeton.edu/courses/archive/fall09/cos521/Handouts/universalclasses.pdf
    // page 149, right after Proposition 7.
    hash += hash >> 32;
    hash &= PRIME_MODULUS;
    // Doing "%" after (int) conversion is ~2x faster than %'ing longs.
    return ((int) hash) % width;
  }

  public void add(long item, long count) {
    if (count < 0) {
      // Actually for negative increments we'll need to use the median
      // instead of minimum, and accuracy will suffer somewhat.
      // Probably makes sense to add an "allow negative increments"
      // parameter to constructor.
      throw new IllegalArgumentException("Negative increments not implemented");
    }
    for (int i = 0; i < depth; ++i) {
      table[i][hash(item, i)] += count;
    }
    size += count;
  }

  public void add(String item, long count) {
    if (count < 0) {
      // Actually for negative increments we'll need to use the median
      // instead of minimum, and accuracy will suffer somewhat.
      // Probably makes sense to add an "allow negative increments"
      // parameter to constructor.
      throw new IllegalArgumentException("Negative increments not implemented");
    }
    // TODO we could reuse the arrays
    final int[] buckets = MurmurHash.hashBuckets(item, depth, width); // TODO replace with Scala's Murmur3, it's much faster
    for (int i = 0; i < depth; ++i) {
      table[i][buckets[i]] += count;
    }
    size += count;
  }

  /**
   * Similar to {@code add}, however we reuse the fact that the hask buckets have to be calculated for {@code add}
   * already, and a separate {@code estimateCount} operation would have to calculate them again, so we do it all in one go.
   */
  public long addAndEstimateCount(String item, long count) {
    if (count < 0) {
      // Actually for negative increments we'll need to use the median
      // instead of minimum, and accuracy will suffer somewhat.
      // Probably makes sense to add an "allow negative increments"
      // parameter to constructor.
      throw new IllegalArgumentException("Negative increments not implemented");
    }
    final int[] buckets = MurmurHash.hashBuckets(item, depth, width);
    for (int i = 0; i < depth; ++i) {
      table[i][buckets[i]] += count;
    }
    size += count;
    return estimateCount(buckets);
  }

  public long size() {
    return size;
  }
  
  /**
   * The estimate is correct within {@code 'epsilon' * (total item count)},
   * with probability {@code confidence}.
   */
  public long estimateCount(long item) {
    long res = Long.MAX_VALUE;
    for (int i = 0; i < depth; ++i) {
      res = Math.min(res, table[i][hash(item, i)]);
    }
    return res;
  }

  /**
   * The estimate is correct within {@code 'epsilon' * (total item count)},
   * with probability {@code confidence}.
   */
  public long estimateCount(String item) {
    int[] buckets = MurmurHash.hashBuckets(item, depth, width);
    return estimateCount(buckets);
  }

  /**
   * The estimate is correct within {@code 'epsilon' * (total item count)},
   * with probability {@code confidence}.
   * 
   * @param buckets the "indexes" of buckets from which we want to calculate the count
   */
  private long estimateCount(int[] buckets) {
    long res = Long.MAX_VALUE;
    for (int i = 0; i < depth; ++i) {
      res = Math.min(res, table[i][buckets[i]]);
    }
    return res;
  }
  
  /**
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
  // TODO replace with Scala's Murmur3, it's much faster
  private static class MurmurHash {
  
    public static int hash(Object o) {
      if (o == null) {
        return 0;
      }
      if (o instanceof String) {
        return hash(((String) o).getBytes());
      }
      // TODO consider calling hashCode on ActorRef here directly? It is just a random number though so possibly not as evenly distributed...?
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
  
    // Murmur is faster than an SHA-based approach and provides as-good collision
    // resistance.  The combinatorial generation approach described in
    // http://www.eecs.harvard.edu/~kirsch/pubs/bbbf/esa06.pdf
    // does prove to work in actual tests, and is obviously faster
    // than performing further iterations of murmur.
    public static int[] hashBuckets(String key, int hashCount, int max) {
      byte[] b;
      try {
        b = key.getBytes("UTF-16");// TODO Use the Unsafe trick @patriknw used to access the backing array directly -- via Endre
      } catch (UnsupportedEncodingException e) {
        throw new RuntimeException(e);
      }
      return hashBuckets(b, hashCount, max);
    }
  
    static int[] hashBuckets(byte[] b, int hashCount, int max) {
      // TODO we could reuse the arrays
      int[] result = new int[hashCount];
      int hash1 = hash(b, b.length, 0);
      int hash2 = hash(b, b.length, hash1);
      for (int i = 0; i < hashCount; i++) {
        result[i] = Math.abs((hash1 + i * hash2) % max);
      }
      return result;
    }
  }

  @Override
  public String toString() {
    return "CountMinSketch{" +
      "confidence=" + confidence +
      ", size=" + size +
      ", depth=" + depth +
      ", width=" + width +
      '}';
  }
}
