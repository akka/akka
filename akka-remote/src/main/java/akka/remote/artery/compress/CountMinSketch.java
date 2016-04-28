/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 * Copyright Apache v2, code adjusted from https://github.com/addthis/stream-lib/blob/master/src/main/java/com/clearspring/analytics/stream/frequency/CountMinSketch.java
 */

package akka.remote.artery.compress;

import java.util.Random;

/**
 * Count-Min Sketch datastructure.
 * An Improved Data Stream Summary: The Count-Min Sketch and its Applications
 * https://web.archive.org/web/20060907232042/http://www.eecs.harvard.edu/~michaelm/CS222/countmin.pdf
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
    final int[] buckets = MurmurHashTwo.hashBuckets(item, depth, width);
    for (int i = 0; i < depth; ++i) {
      table[i][buckets[i]] += count;
    }
    size += count;
  }

  public long size() {
    return size;
  }
  
  /**
   * The estimate is correct within 'epsilon' * (total item count),
   * with probability 'confidence'.
   */
  public long estimateCount(long item) {
    long res = Long.MAX_VALUE;
    for (int i = 0; i < depth; ++i) {
      res = Math.min(res, table[i][hash(item, i)]);
    }
    return res;
  }

  public long estimateCount(String item) {
    long res = Long.MAX_VALUE;
    int[] buckets = MurmurHashTwo.hashBuckets(item, depth, width);
    for (int i = 0; i < depth; ++i) {
      res = Math.min(res, table[i][buckets[i]]);
    }
    return res;
  }
}
