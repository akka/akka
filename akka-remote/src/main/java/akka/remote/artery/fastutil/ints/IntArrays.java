/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil.ints;

import akka.remote.artery.fastutil.Arrays;
import akka.remote.artery.fastutil.Hash;

import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * A class providing static methods and objects that do useful things with type-specific arrays.
 * <p>
 * <p>In particular, the <code>ensureCapacity()</code>, <code>grow()</code>,
 * <code>trim()</code> and <code>setLength()</code> methods allow to handle
 * arrays much like array lists. This can be very useful when efficiency (or
 * syntactic simplicity) reasons make array lists unsuitable.
 * <p>
 * <P>Note that {@link akka.remote.artery.fastutil.io.BinIO} and {@link akka.remote.artery.fastutil.io.TextIO}
 * contain several methods make it possible to load and save arrays of primitive types as sequences
 * of elements in {@link java.io.DataInput} format (i.e., not as objects) or as sequences of lines of text.
 * <p>
 * <h2>Sorting</h2>
 * <p>
 * <p>There are several sorting methods available. The main theme is that of letting you choose
 * the sorting algorithm you prefer (i.e., trading stability of mergesort for no memory allocation in quicksort).
 * Several algorithms provide a parallel version, that will use the {@linkplain Runtime#availableProcessors() number of cores available}.
 * Some algorithms also provide an explicit <em>indirect</em> sorting facility, which makes it possible
 * to sort an array using the values in another array as comparator.
 * <p>
 * <p>All comparison-based algorithm have an implementation based on a type-specific comparator.
 * <p>
 * <p>As a general rule, sequential radix sort is significantly faster than quicksort or mergesort, in particular
 * on random-looking data. In
 * the parallel case, up to a few cores parallel radix sort is still the fastest, but at some point quicksort
 * exploits parallelism better.
 * <p>
 * <p>If you are fine with not knowing exactly which algorithm will be run (in particular, not knowing exactly whether a support array will be allocated),
 * the dual-pivot parallel sorts in {@link java.util.Arrays}
 * are about 50% faster than the classical single-pivot implementation used here.
 * <p>
 * <p>In any case, if sorting time is important I suggest that you benchmark your sorting load
 * with your data distribution and on your architecture.
 *
 * @see java.util.Arrays
 */

public class IntArrays {
  private IntArrays() {
  }

  /**
   * A static, final, empty array.
   */
  public final static int[] EMPTY_ARRAY = {};

  /**
   * Ensures that an array can contain the given number of entries.
   * <p>
   * <P>If you cannot foresee whether this array will need again to be
   * enlarged, you should probably use <code>grow()</code> instead.
   *
   * @param array  an array.
   * @param length the new minimum length for this array.
   * @return <code>array</code>, if it contains <code>length</code> entries or more; otherwise,
   * an array with <code>length</code> entries whose first <code>array.length</code>
   * entries are the same as those of <code>array</code>.
   */
  public static int[] ensureCapacity(final int[] array, final int length) {
    if (length > array.length) {
      final int t[] = new int[length];

      System.arraycopy(array, 0, t, 0, array.length);
      return t;
    }
    return array;
  }

  /**
   * Ensures that an array can contain the given number of entries, preserving just a part of the array.
   *
   * @param array    an array.
   * @param length   the new minimum length for this array.
   * @param preserve the number of elements of the array that must be preserved in case a new allocation is necessary.
   * @return <code>array</code>, if it can contain <code>length</code> entries or more; otherwise,
   * an array with <code>length</code> entries whose first <code>preserve</code>
   * entries are the same as those of <code>array</code>.
   */
  public static int[] ensureCapacity(final int[] array, final int length, final int preserve) {
    if (length > array.length) {
      final int t[] =


        new int[length];

      System.arraycopy(array, 0, t, 0, preserve);
      return t;
    }
    return array;
  }

  /**
   * Grows the given array to the maximum between the given length and
   * the current length multiplied by two, provided that the given
   * length is larger than the current length.
   * <p>
   * <P>If you want complete control on the array growth, you
   * should probably use <code>ensureCapacity()</code> instead.
   *
   * @param array  an array.
   * @param length the new minimum length for this array.
   * @return <code>array</code>, if it can contain <code>length</code>
   * entries; otherwise, an array with
   * max(<code>length</code>,<code>array.length</code>/&phi;) entries whose first
   * <code>array.length</code> entries are the same as those of <code>array</code>.
   */

  public static int[] grow(final int[] array, final int length) {
    if (length > array.length) {
      final int newLength = (int) Math.max(Math.min(2L * array.length, Arrays.MAX_ARRAY_SIZE), length);
      final int t[] =


        new int[newLength];

      System.arraycopy(array, 0, t, 0, array.length);
      return t;
    }
    return array;
  }

  /**
   * Grows the given array to the maximum between the given length and
   * the current length multiplied by two, provided that the given
   * length is larger than the current length, preserving just a part of the array.
   * <p>
   * <P>If you want complete control on the array growth, you
   * should probably use <code>ensureCapacity()</code> instead.
   *
   * @param array    an array.
   * @param length   the new minimum length for this array.
   * @param preserve the number of elements of the array that must be preserved in case a new allocation is necessary.
   * @return <code>array</code>, if it can contain <code>length</code>
   * entries; otherwise, an array with
   * max(<code>length</code>,<code>array.length</code>/&phi;) entries whose first
   * <code>preserve</code> entries are the same as those of <code>array</code>.
   */

  public static int[] grow(final int[] array, final int length, final int preserve) {

    if (length > array.length) {
      final int newLength = (int) Math.max(Math.min(2L * array.length, Arrays.MAX_ARRAY_SIZE), length);

      final int t[] =


        new int[newLength];

      System.arraycopy(array, 0, t, 0, preserve);

      return t;
    }
    return array;

  }

  /**
   * Trims the given array to the given length.
   *
   * @param array  an array.
   * @param length the new maximum length for the array.
   * @return <code>array</code>, if it contains <code>length</code>
   * entries or less; otherwise, an array with
   * <code>length</code> entries whose entries are the same as
   * the first <code>length</code> entries of <code>array</code>.
   */

  public static int[] trim(final int[] array, final int length) {
    if (length >= array.length) {
      return array;
    }
    final int t[] =


      length == 0 ? EMPTY_ARRAY : new int[length];

    System.arraycopy(array, 0, t, 0, length);
    return t;
  }

  /**
   * Sets the length of the given array.
   *
   * @param array  an array.
   * @param length the new length for the array.
   * @return <code>array</code>, if it contains exactly <code>length</code>
   * entries; otherwise, if it contains <em>more</em> than
   * <code>length</code> entries, an array with <code>length</code> entries
   * whose entries are the same as the first <code>length</code> entries of
   * <code>array</code>; otherwise, an array with <code>length</code> entries
   * whose first <code>array.length</code> entries are the same as those of
   * <code>array</code>.
   */

  public static int[] setLength(final int[] array, final int length) {
    if (length == array.length) {
      return array;
    }
    if (length < array.length) {
      return trim(array, length);
    }
    return ensureCapacity(array, length);
  }

  /**
   * Returns a copy of a portion of an array.
   *
   * @param array  an array.
   * @param offset the first element to copy.
   * @param length the number of elements to copy.
   * @return a new array containing <code>length</code> elements of <code>array</code> starting at <code>offset</code>.
   */

  public static int[] copy(final int[] array, final int offset, final int length) {
    ensureOffsetLength(array, offset, length);
    final int[] a =


      length == 0 ? EMPTY_ARRAY : new int[length];

    System.arraycopy(array, offset, a, 0, length);
    return a;
  }

  /**
   * Returns a copy of an array.
   *
   * @param array an array.
   * @return a copy of <code>array</code>.
   */

  public static int[] copy(final int[] array) {
    return array.clone();
  }

  /**
   * Fills the given array with the given value.
   *
   * @param array an array.
   * @param value the new value for all elements of the array.
   * @deprecated Please use the corresponding {@link java.util.Arrays} method.
   */

  @Deprecated
  public static void fill(final int[] array, final int value) {
    int i = array.length;
    while (i-- != 0) {
      array[i] = value;
    }
  }

  /**
   * Fills a portion of the given array with the given value.
   *
   * @param array an array.
   * @param from  the starting index of the portion to fill (inclusive).
   * @param to    the end index of the portion to fill (exclusive).
   * @param value the new value for all elements of the specified portion of the array.
   * @deprecated Please use the corresponding {@link java.util.Arrays} method.
   */

  @Deprecated
  public static void fill(final int[] array, final int from, int to, final int value) {
    ensureFromTo(array, from, to);
    if (from == 0) {
      while (to-- != 0) {
        array[to] = value;
      }
    } else {
      for (int i = from; i < to; i++) {
        array[i] = value;
      }
    }
  }


  /**
   * Returns true if the two arrays are elementwise equal.
   *
   * @param a1 an array.
   * @param a2 another array.
   * @return true if the two arrays are of the same length, and their elements are equal.
   * @deprecated Please use the corresponding {@link java.util.Arrays} method, which is intrinsified in recent JVMs.
   */

  @Deprecated
  public static boolean equals(final int[] a1, final int a2[]) {
    int i = a1.length;
    if (i != a2.length) {
      return false;
    }
    while (i-- != 0) {
      if (!((a1[i]) == (a2[i]))) {
        return false;
      }
    }
    return true;
  }


  /**
   * Ensures that a range given by its first (inclusive) and last (exclusive) elements fits an array.
   * <p>
   * <P>This method may be used whenever an array range check is needed.
   *
   * @param a    an array.
   * @param from a start index (inclusive).
   * @param to   an end index (exclusive).
   * @throws IllegalArgumentException       if <code>from</code> is greater than <code>to</code>.
   * @throws ArrayIndexOutOfBoundsException if <code>from</code> or <code>to</code> are greater than the array length or negative.
   */
  public static void ensureFromTo(final int[] a, final int from, final int to) {
    Arrays.ensureFromTo(a.length, from, to);
  }

  /**
   * Ensures that a range given by an offset and a length fits an array.
   * <p>
   * <P>This method may be used whenever an array range check is needed.
   *
   * @param a      an array.
   * @param offset a start index.
   * @param length a length (the number of elements in the range).
   * @throws IllegalArgumentException       if <code>length</code> is negative.
   * @throws ArrayIndexOutOfBoundsException if <code>offset</code> is negative or <code>offset</code>+<code>length</code> is greater than the array length.
   */
  public static void ensureOffsetLength(final int[] a, final int offset, final int length) {
    Arrays.ensureOffsetLength(a.length, offset, length);
  }

  /**
   * Ensures that two arrays are of the same length.
   *
   * @param a an array.
   * @param b another array.
   * @throws IllegalArgumentException if the two argument arrays are not of the same length.
   */
  public static void ensureSameLength(final int[] a, final int[] b) {
    if (a.length != b.length) {
      throw new IllegalArgumentException("Array size mismatch: " + a.length + " != " + b.length);
    }
  }

  private static final int QUICKSORT_NO_REC = 16;
  private static final int PARALLEL_QUICKSORT_NO_FORK = 8192;
  private static final int QUICKSORT_MEDIAN_OF_9 = 128;
  private static final int MERGESORT_NO_REC = 16;

  /**
   * Swaps two elements of an anrray.
   *
   * @param x an array.
   * @param a a position in {@code x}.
   * @param b another position in {@code x}.
   */
  public static void swap(final int x[], final int a, final int b) {
    final int t = x[a];
    x[a] = x[b];
    x[b] = t;
  }

  /**
   * Swaps two sequences of elements of an array.
   *
   * @param x an array.
   * @param a a position in {@code x}.
   * @param b another position in {@code x}.
   * @param n the number of elements to exchange starting at {@code a} and {@code b}.
   */
  public static void swap(final int[] x, int a, int b, final int n) {
    for (int i = 0; i < n; i++, a++, b++) {
      swap(x, a, b);
    }
  }

  private static int med3(final int x[], final int a, final int b, final int c, IntComparator comp) {
    final int ab = comp.compare(x[a], x[b]);
    final int ac = comp.compare(x[a], x[c]);
    final int bc = comp.compare(x[b], x[c]);
    return (ab < 0 ?
      (bc < 0 ? b : ac < 0 ? c : a) :
      (bc > 0 ? b : ac > 0 ? c : a));
  }

  private static void selectionSort(final int[] a, final int from, final int to, final IntComparator comp) {
    for (int i = from; i < to - 1; i++) {
      int m = i;
      for (int j = i + 1; j < to; j++) {
        if (comp.compare(a[j], a[m]) < 0) {
          m = j;
        }
      }
      if (m != i) {
        final int u = a[i];
        a[i] = a[m];
        a[m] = u;
      }
    }
  }

  private static void insertionSort(final int[] a, final int from, final int to, final IntComparator comp) {
    for (int i = from; ++i < to; ) {
      int t = a[i];
      int j = i;
      for (int u = a[j - 1]; comp.compare(t, u) < 0; u = a[--j - 1]) {
        a[j] = u;
        if (from == j - 1) {
          --j;
          break;
        }
      }
      a[j] = t;
    }
  }


  /**
   * Sorts the specified range of elements according to the order induced by the specified
   * comparator using quicksort.
   * <p>
   * <p>The sorting algorithm is a tuned quicksort adapted from Jon L. Bentley and M. Douglas
   * McIlroy, &ldquo;Engineering a Sort Function&rdquo;, <i>Software: Practice and Experience</i>, 23(11), pages
   * 1249&minus;1265, 1993.
   * <p>
   * <p>Note that this implementation does not allocate any object, contrarily to the implementation
   * used to sort primitive types in {@link java.util.Arrays}, which switches to mergesort on large inputs.
   *
   * @param x    the array to be sorted.
   * @param from the index of the first element (inclusive) to be sorted.
   * @param to   the index of the last element (exclusive) to be sorted.
   * @param comp the comparator to determine the sorting order.
   */
  public static void quickSort(final int[] x, final int from, final int to, final IntComparator comp) {
    final int len = to - from;
    // Selection sort on smallest arrays
    if (len < QUICKSORT_NO_REC) {
      selectionSort(x, from, to, comp);
      return;
    }

    // Choose a partition element, v
    int m = from + len / 2;
    int l = from;
    int n = to - 1;
    if (len > QUICKSORT_MEDIAN_OF_9) { // Big arrays, pseudomedian of 9
      int s = len / 8;
      l = med3(x, l, l + s, l + 2 * s, comp);
      m = med3(x, m - s, m, m + s, comp);
      n = med3(x, n - 2 * s, n - s, n, comp);
    }
    m = med3(x, l, m, n, comp); // Mid-size, med of 3

    final int v = x[m];

    // Establish Invariant: v* (<v)* (>v)* v*
    int a = from, b = a, c = to - 1, d = c;
    while (true) {
      int comparison;
      while (b <= c && (comparison = comp.compare(x[b], v)) <= 0) {
        if (comparison == 0) {
          swap(x, a++, b);
        }
        b++;
      }
      while (c >= b && (comparison = comp.compare(x[c], v)) >= 0) {
        if (comparison == 0) {
          swap(x, c, d--);
        }
        c--;
      }
      if (b > c) {
        break;
      }
      swap(x, b++, c--);
    }

    // Swap partition elements back to middle
    int s;
    s = Math.min(a - from, b - a);
    swap(x, from, b - s, s);
    s = Math.min(d - c, to - d - 1);
    swap(x, b, to - s, s);

    // Recursively sort non-partition-elements
    if ((s = b - a) > 1) {
      quickSort(x, from, from + s, comp);
    }
    if ((s = d - c) > 1) {
      quickSort(x, to - s, to, comp);
    }
  }

  /**
   * Sorts an array according to the order induced by the specified
   * comparator using quicksort.
   * <p>
   * <p>The sorting algorithm is a tuned quicksort adapted from Jon L. Bentley and M. Douglas
   * McIlroy, &ldquo;Engineering a Sort Function&rdquo;, <i>Software: Practice and Experience</i>, 23(11), pages
   * 1249&minus;1265, 1993.
   * <p>
   * <p>Note that this implementation does not allocate any object, contrarily to the implementation
   * used to sort primitive types in {@link java.util.Arrays}, which switches to mergesort on large inputs.
   *
   * @param x    the array to be sorted.
   * @param comp the comparator to determine the sorting order.
   */
  public static void quickSort(final int[] x, final IntComparator comp) {
    quickSort(x, 0, x.length, comp);
  }


  protected static class ForkJoinQuickSortComp extends RecursiveAction {
    private static final long serialVersionUID = 1L;
    private final int from;
    private final int to;
    private final int[] x;
    private final IntComparator comp;

    public ForkJoinQuickSortComp(final int[] x, final int from, final int to, final IntComparator comp) {
      this.from = from;
      this.to = to;
      this.x = x;
      this.comp = comp;
    }

    @Override
    protected void compute() {
      final int[] x = this.x;
      final int len = to - from;
      if (len < PARALLEL_QUICKSORT_NO_FORK) {
        quickSort(x, from, to, comp);
        return;
      }
      // Choose a partition element, v
      int m = from + len / 2;
      int l = from;
      int n = to - 1;
      int s = len / 8;
      l = med3(x, l, l + s, l + 2 * s, comp);
      m = med3(x, m - s, m, m + s, comp);
      n = med3(x, n - 2 * s, n - s, n, comp);
      m = med3(x, l, m, n, comp);
      final int v = x[m];
      // Establish Invariant: v* (<v)* (>v)* v*
      int a = from, b = a, c = to - 1, d = c;
      while (true) {
        int comparison;
        while (b <= c && (comparison = comp.compare(x[b], v)) <= 0) {
          if (comparison == 0) {
            swap(x, a++, b);
          }
          b++;
        }
        while (c >= b && (comparison = comp.compare(x[c], v)) >= 0) {
          if (comparison == 0) {
            swap(x, c, d--);
          }
          c--;
        }
        if (b > c) {
          break;
        }
        swap(x, b++, c--);
      }
      // Swap partition elements back to middle
      int t;
      s = Math.min(a - from, b - a);
      swap(x, from, b - s, s);
      s = Math.min(d - c, to - d - 1);
      swap(x, b, to - s, s);
      // Recursively sort non-partition-elements
      s = b - a;
      t = d - c;
      if (s > 1 && t > 1) {
        invokeAll(new ForkJoinQuickSortComp(x, from, from + s, comp), new ForkJoinQuickSortComp(x, to - t, to, comp));
      } else if (s > 1) {
        invokeAll(new ForkJoinQuickSortComp(x, from, from + s, comp));
      } else {
        invokeAll(new ForkJoinQuickSortComp(x, to - t, to, comp));
      }
    }
  }

  /**
   * Sorts the specified range of elements according to the order induced by the specified
   * comparator using a parallel quicksort.
   * <p>
   * <p>The sorting algorithm is a tuned quicksort adapted from Jon L. Bentley and M. Douglas
   * McIlroy, &ldquo;Engineering a Sort Function&rdquo;, <i>Software: Practice and Experience</i>, 23(11), pages
   * 1249&minus;1265, 1993.
   * <p>
   * <p>This implementation uses a {@link ForkJoinPool} executor service with
   * {@link Runtime#availableProcessors()} parallel threads.
   *
   * @param x    the array to be sorted.
   * @param from the index of the first element (inclusive) to be sorted.
   * @param to   the index of the last element (exclusive) to be sorted.
   * @param comp the comparator to determine the sorting order.
   */
  public static void parallelQuickSort(final int[] x, final int from, final int to, final IntComparator comp) {
    if (to - from < PARALLEL_QUICKSORT_NO_FORK) {
      quickSort(x, from, to, comp);
    } else {
      final ForkJoinPool pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
      pool.invoke(new ForkJoinQuickSortComp(x, from, to, comp));
      pool.shutdown();
    }
  }

  /**
   * Sorts an array according to the order induced by the specified
   * comparator using a parallel quicksort.
   * <p>
   * <p>The sorting algorithm is a tuned quicksort adapted from Jon L. Bentley and M. Douglas
   * McIlroy, &ldquo;Engineering a Sort Function&rdquo;, <i>Software: Practice and Experience</i>, 23(11), pages
   * 1249&minus;1265, 1993.
   * <p>
   * <p>This implementation uses a {@link ForkJoinPool} executor service with
   * {@link Runtime#availableProcessors()} parallel threads.
   *
   * @param x    the array to be sorted.
   * @param comp the comparator to determine the sorting order.
   */
  public static void parallelQuickSort(final int[] x, final IntComparator comp) {
    parallelQuickSort(x, 0, x.length, comp);
  }


  private static int med3(final int x[], final int a, final int b, final int c) {
    final int ab = (Integer.compare((x[a]), (x[b])));
    final int ac = (Integer.compare((x[a]), (x[c])));
    final int bc = (Integer.compare((x[b]), (x[c])));
    return (ab < 0 ?
      (bc < 0 ? b : ac < 0 ? c : a) :
      (bc > 0 ? b : ac > 0 ? c : a));
  }


  private static void selectionSort(final int[] a, final int from, final int to) {
    for (int i = from; i < to - 1; i++) {
      int m = i;
      for (int j = i + 1; j < to; j++) {
        if (((a[j]) < (a[m]))) {
          m = j;
        }
      }
      if (m != i) {
        final int u = a[i];
        a[i] = a[m];
        a[m] = u;
      }
    }
  }


  private static void insertionSort(final int[] a, final int from, final int to) {
    for (int i = from; ++i < to; ) {
      int t = a[i];
      int j = i;
      for (int u = a[j - 1]; ((t) < (u)); u = a[--j - 1]) {
        a[j] = u;
        if (from == j - 1) {
          --j;
          break;
        }
      }
      a[j] = t;
    }
  }

  /**
   * Sorts the specified range of elements according to the natural ascending order using quicksort.
   * <p>
   * <p>The sorting algorithm is a tuned quicksort adapted from Jon L. Bentley and M. Douglas
   * McIlroy, &ldquo;Engineering a Sort Function&rdquo;, <i>Software: Practice and Experience</i>, 23(11), pages
   * 1249&minus;1265, 1993.
   * <p>
   * <p>Note that this implementation does not allocate any object, contrarily to the implementation
   * used to sort primitive types in {@link java.util.Arrays}, which switches to mergesort on large inputs.
   *
   * @param x    the array to be sorted.
   * @param from the index of the first element (inclusive) to be sorted.
   * @param to   the index of the last element (exclusive) to be sorted.
   */


  public static void quickSort(final int[] x, final int from, final int to) {
    final int len = to - from;
    // Selection sort on smallest arrays
    if (len < QUICKSORT_NO_REC) {
      selectionSort(x, from, to);
      return;
    }

    // Choose a partition element, v
    int m = from + len / 2;
    int l = from;
    int n = to - 1;
    if (len > QUICKSORT_MEDIAN_OF_9) { // Big arrays, pseudomedian of 9
      int s = len / 8;
      l = med3(x, l, l + s, l + 2 * s);
      m = med3(x, m - s, m, m + s);
      n = med3(x, n - 2 * s, n - s, n);
    }
    m = med3(x, l, m, n); // Mid-size, med of 3

    final int v = x[m];

    // Establish Invariant: v* (<v)* (>v)* v*
    int a = from, b = a, c = to - 1, d = c;
    while (true) {
      int comparison;
      while (b <= c && (comparison = (Integer.compare((x[b]), (v)))) <= 0) {
        if (comparison == 0) {
          swap(x, a++, b);
        }
        b++;
      }
      while (c >= b && (comparison = (Integer.compare((x[c]), (v)))) >= 0) {
        if (comparison == 0) {
          swap(x, c, d--);
        }
        c--;
      }
      if (b > c) {
        break;
      }
      swap(x, b++, c--);
    }

    // Swap partition elements back to middle
    int s;
    s = Math.min(a - from, b - a);
    swap(x, from, b - s, s);
    s = Math.min(d - c, to - d - 1);
    swap(x, b, to - s, s);

    // Recursively sort non-partition-elements
    if ((s = b - a) > 1) {
      quickSort(x, from, from + s);
    }
    if ((s = d - c) > 1) {
      quickSort(x, to - s, to);
    }
  }

  /**
   * Sorts an array according to the natural ascending order using quicksort.
   * <p>
   * <p>The sorting algorithm is a tuned quicksort adapted from Jon L. Bentley and M. Douglas
   * McIlroy, &ldquo;Engineering a Sort Function&rdquo;, <i>Software: Practice and Experience</i>, 23(11), pages
   * 1249&minus;1265, 1993.
   * <p>
   * <p>Note that this implementation does not allocate any object, contrarily to the implementation
   * used to sort primitive types in {@link java.util.Arrays}, which switches to mergesort on large inputs.
   *
   * @param x the array to be sorted.
   */
  public static void quickSort(final int[] x) {
    quickSort(x, 0, x.length);
  }

  protected static class ForkJoinQuickSort extends RecursiveAction {
    private static final long serialVersionUID = 1L;
    private final int from;
    private final int to;
    private final int[] x;

    public ForkJoinQuickSort(final int[] x, final int from, final int to) {
      this.from = from;
      this.to = to;
      this.x = x;
    }

    @Override

    protected void compute() {
      final int[] x = this.x;
      final int len = to - from;
      if (len < PARALLEL_QUICKSORT_NO_FORK) {
        quickSort(x, from, to);
        return;
      }
      // Choose a partition element, v
      int m = from + len / 2;
      int l = from;
      int n = to - 1;
      int s = len / 8;
      l = med3(x, l, l + s, l + 2 * s);
      m = med3(x, m - s, m, m + s);
      n = med3(x, n - 2 * s, n - s, n);
      m = med3(x, l, m, n);
      final int v = x[m];
      // Establish Invariant: v* (<v)* (>v)* v*
      int a = from, b = a, c = to - 1, d = c;
      while (true) {
        int comparison;
        while (b <= c && (comparison = (Integer.compare((x[b]), (v)))) <= 0) {
          if (comparison == 0) {
            swap(x, a++, b);
          }
          b++;
        }
        while (c >= b && (comparison = (Integer.compare((x[c]), (v)))) >= 0) {
          if (comparison == 0) {
            swap(x, c, d--);
          }
          c--;
        }
        if (b > c) {
          break;
        }
        swap(x, b++, c--);
      }
      // Swap partition elements back to middle
      int t;
      s = Math.min(a - from, b - a);
      swap(x, from, b - s, s);
      s = Math.min(d - c, to - d - 1);
      swap(x, b, to - s, s);
      // Recursively sort non-partition-elements
      s = b - a;
      t = d - c;
      if (s > 1 && t > 1) {
        invokeAll(new ForkJoinQuickSort(x, from, from + s), new ForkJoinQuickSort(x, to - t, to));
      } else if (s > 1) {
        invokeAll(new ForkJoinQuickSort(x, from, from + s));
      } else {
        invokeAll(new ForkJoinQuickSort(x, to - t, to));
      }
    }
  }

  /**
   * Sorts the specified range of elements according to the natural ascending order using a parallel quicksort.
   * <p>
   * <p>The sorting algorithm is a tuned quicksort adapted from Jon L. Bentley and M. Douglas
   * McIlroy, &ldquo;Engineering a Sort Function&rdquo;, <i>Software: Practice and Experience</i>, 23(11), pages
   * 1249&minus;1265, 1993.
   * <p>
   * <p>This implementation uses a {@link ForkJoinPool} executor service with
   * {@link Runtime#availableProcessors()} parallel threads.
   *
   * @param x    the array to be sorted.
   * @param from the index of the first element (inclusive) to be sorted.
   * @param to   the index of the last element (exclusive) to be sorted.
   */
  public static void parallelQuickSort(final int[] x, final int from, final int to) {
    if (to - from < PARALLEL_QUICKSORT_NO_FORK) {
      quickSort(x, from, to);
    } else {
      final ForkJoinPool pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
      pool.invoke(new ForkJoinQuickSort(x, from, to));
      pool.shutdown();
    }
  }

  /**
   * Sorts an array according to the natural ascending order using a parallel quicksort.
   * <p>
   * <p>The sorting algorithm is a tuned quicksort adapted from Jon L. Bentley and M. Douglas
   * McIlroy, &ldquo;Engineering a Sort Function&rdquo;, <i>Software: Practice and Experience</i>, 23(11), pages
   * 1249&minus;1265, 1993.
   * <p>
   * <p>This implementation uses a {@link ForkJoinPool} executor service with
   * {@link Runtime#availableProcessors()} parallel threads.
   *
   * @param x the array to be sorted.
   */
  public static void parallelQuickSort(final int[] x) {
    parallelQuickSort(x, 0, x.length);
  }


  private static int med3Indirect(final int perm[], final int x[], final int a, final int b, final int c) {
    final int aa = x[perm[a]];
    final int bb = x[perm[b]];
    final int cc = x[perm[c]];
    final int ab = (Integer.compare((aa), (bb)));
    final int ac = (Integer.compare((aa), (cc)));
    final int bc = (Integer.compare((bb), (cc)));
    return (ab < 0 ?
      (bc < 0 ? b : ac < 0 ? c : a) :
      (bc > 0 ? b : ac > 0 ? c : a));
  }


  private static void insertionSortIndirect(final int[] perm, final int[] a, final int from, final int to) {
    for (int i = from; ++i < to; ) {
      int t = perm[i];
      int j = i;
      for (int u = perm[j - 1]; ((a[t]) < (a[u])); u = perm[--j - 1]) {
        perm[j] = u;
        if (from == j - 1) {
          --j;
          break;
        }
      }
      perm[j] = t;
    }
  }

  /**
   * Sorts the specified range of elements according to the natural ascending order using indirect quicksort.
   * <p>
   * <p>The sorting algorithm is a tuned quicksort adapted from Jon L. Bentley and M. Douglas
   * McIlroy, &ldquo;Engineering a Sort Function&rdquo;, <i>Software: Practice and Experience</i>, 23(11), pages
   * 1249&minus;1265, 1993.
   * <p>
   * <p>This method implement an <em>indirect</em> sort. The elements of <code>perm</code> (which must
   * be exactly the numbers in the interval <code>[0..perm.length)</code>) will be permuted so that
   * <code>x[ perm[ i ] ] &le; x[ perm[ i + 1 ] ]</code>.
   * <p>
   * <p>Note that this implementation does not allocate any object, contrarily to the implementation
   * used to sort primitive types in {@link java.util.Arrays}, which switches to mergesort on large inputs.
   *
   * @param perm a permutation array indexing {@code x}.
   * @param x    the array to be sorted.
   * @param from the index of the first element (inclusive) to be sorted.
   * @param to   the index of the last element (exclusive) to be sorted.
   */


  public static void quickSortIndirect(final int[] perm, final int[] x, final int from, final int to) {
    final int len = to - from;
    // Selection sort on smallest arrays
    if (len < QUICKSORT_NO_REC) {
      insertionSortIndirect(perm, x, from, to);
      return;
    }

    // Choose a partition element, v
    int m = from + len / 2;
    int l = from;
    int n = to - 1;
    if (len > QUICKSORT_MEDIAN_OF_9) { // Big arrays, pseudomedian of 9
      int s = len / 8;
      l = med3Indirect(perm, x, l, l + s, l + 2 * s);
      m = med3Indirect(perm, x, m - s, m, m + s);
      n = med3Indirect(perm, x, n - 2 * s, n - s, n);
    }
    m = med3Indirect(perm, x, l, m, n); // Mid-size, med of 3

    final int v = x[perm[m]];

    // Establish Invariant: v* (<v)* (>v)* v*
    int a = from, b = a, c = to - 1, d = c;
    while (true) {
      int comparison;
      while (b <= c && (comparison = (Integer.compare((x[perm[b]]), (v)))) <= 0) {
        if (comparison == 0) {
          IntArrays.swap(perm, a++, b);
        }
        b++;
      }
      while (c >= b && (comparison = (Integer.compare((x[perm[c]]), (v)))) >= 0) {
        if (comparison == 0) {
          IntArrays.swap(perm, c, d--);
        }
        c--;
      }
      if (b > c) {
        break;
      }
      IntArrays.swap(perm, b++, c--);
    }

    // Swap partition elements back to middle
    int s;
    s = Math.min(a - from, b - a);
    IntArrays.swap(perm, from, b - s, s);
    s = Math.min(d - c, to - d - 1);
    IntArrays.swap(perm, b, to - s, s);

    // Recursively sort non-partition-elements
    if ((s = b - a) > 1) {
      quickSortIndirect(perm, x, from, from + s);
    }
    if ((s = d - c) > 1) {
      quickSortIndirect(perm, x, to - s, to);
    }
  }

  /**
   * Sorts an array according to the natural ascending order using indirect quicksort.
   * <p>
   * <p>The sorting algorithm is a tuned quicksort adapted from Jon L. Bentley and M. Douglas
   * McIlroy, &ldquo;Engineering a Sort Function&rdquo;, <i>Software: Practice and Experience</i>, 23(11), pages
   * 1249&minus;1265, 1993.
   * <p>
   * <p>This method implement an <em>indirect</em> sort. The elements of <code>perm</code> (which must
   * be exactly the numbers in the interval <code>[0..perm.length)</code>) will be permuted so that
   * <code>x[ perm[ i ] ] &le; x[ perm[ i + 1 ] ]</code>.
   * <p>
   * <p>Note that this implementation does not allocate any object, contrarily to the implementation
   * used to sort primitive types in {@link java.util.Arrays}, which switches to mergesort on large inputs.
   *
   * @param perm a permutation array indexing {@code x}.
   * @param x    the array to be sorted.
   */
  public static void quickSortIndirect(final int perm[], final int[] x) {
    quickSortIndirect(perm, x, 0, x.length);
  }

  protected static class ForkJoinQuickSortIndirect extends RecursiveAction {
    private static final long serialVersionUID = 1L;
    private final int from;
    private final int to;
    private final int[] perm;
    private final int[] x;

    public ForkJoinQuickSortIndirect(final int perm[], final int[] x, final int from, final int to) {
      this.from = from;
      this.to = to;
      this.x = x;
      this.perm = perm;
    }

    @Override

    protected void compute() {
      final int[] x = this.x;
      final int len = to - from;
      if (len < PARALLEL_QUICKSORT_NO_FORK) {
        quickSortIndirect(perm, x, from, to);
        return;
      }
      // Choose a partition element, v
      int m = from + len / 2;
      int l = from;
      int n = to - 1;
      int s = len / 8;
      l = med3Indirect(perm, x, l, l + s, l + 2 * s);
      m = med3Indirect(perm, x, m - s, m, m + s);
      n = med3Indirect(perm, x, n - 2 * s, n - s, n);
      m = med3Indirect(perm, x, l, m, n);
      final int v = x[perm[m]];
      // Establish Invariant: v* (<v)* (>v)* v*
      int a = from, b = a, c = to - 1, d = c;
      while (true) {
        int comparison;
        while (b <= c && (comparison = (Integer.compare((x[perm[b]]), (v)))) <= 0) {
          if (comparison == 0) {
            IntArrays.swap(perm, a++, b);
          }
          b++;
        }
        while (c >= b && (comparison = (Integer.compare((x[perm[c]]), (v)))) >= 0) {
          if (comparison == 0) {
            IntArrays.swap(perm, c, d--);
          }
          c--;
        }
        if (b > c) {
          break;
        }
        IntArrays.swap(perm, b++, c--);
      }
      // Swap partition elements back to middle
      int t;
      s = Math.min(a - from, b - a);
      IntArrays.swap(perm, from, b - s, s);
      s = Math.min(d - c, to - d - 1);
      IntArrays.swap(perm, b, to - s, s);
      // Recursively sort non-partition-elements
      s = b - a;
      t = d - c;
      if (s > 1 && t > 1) {
        invokeAll(new ForkJoinQuickSortIndirect(perm, x, from, from + s), new ForkJoinQuickSortIndirect(perm, x, to - t, to));
      } else if (s > 1) {
        invokeAll(new ForkJoinQuickSortIndirect(perm, x, from, from + s));
      } else {
        invokeAll(new ForkJoinQuickSortIndirect(perm, x, to - t, to));
      }
    }
  }

  /**
   * Sorts the specified range of elements according to the natural ascending order using a parallel indirect quicksort.
   * <p>
   * <p>The sorting algorithm is a tuned quicksort adapted from Jon L. Bentley and M. Douglas
   * McIlroy, &ldquo;Engineering a Sort Function&rdquo;, <i>Software: Practice and Experience</i>, 23(11), pages
   * 1249&minus;1265, 1993.
   * <p>
   * <p>This method implement an <em>indirect</em> sort. The elements of <code>perm</code> (which must
   * be exactly the numbers in the interval <code>[0..perm.length)</code>) will be permuted so that
   * <code>x[ perm[ i ] ] &le; x[ perm[ i + 1 ] ]</code>.
   * <p>
   * <p>This implementation uses a {@link ForkJoinPool} executor service with
   * {@link Runtime#availableProcessors()} parallel threads.
   *
   * @param perm a permutation array indexing {@code x}.
   * @param x    the array to be sorted.
   * @param from the index of the first element (inclusive) to be sorted.
   * @param to   the index of the last element (exclusive) to be sorted.
   */
  public static void parallelQuickSortIndirect(final int[] perm, final int[] x, final int from, final int to) {
    if (to - from < PARALLEL_QUICKSORT_NO_FORK) {
      quickSortIndirect(perm, x, from, to);
    } else {
      final ForkJoinPool pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
      pool.invoke(new ForkJoinQuickSortIndirect(perm, x, from, to));
      pool.shutdown();
    }
  }

  /**
   * Sorts an array according to the natural ascending order using a parallel indirect quicksort.
   * <p>
   * <p>The sorting algorithm is a tuned quicksort adapted from Jon L. Bentley and M. Douglas
   * McIlroy, &ldquo;Engineering a Sort Function&rdquo;, <i>Software: Practice and Experience</i>, 23(11), pages
   * 1249&minus;1265, 1993.
   * <p>
   * <p>This method implement an <em>indirect</em> sort. The elements of <code>perm</code> (which must
   * be exactly the numbers in the interval <code>[0..perm.length)</code>) will be permuted so that
   * <code>x[ perm[ i ] ] &le; x[ perm[ i + 1 ] ]</code>.
   * <p>
   * <p>This implementation uses a {@link ForkJoinPool} executor service with
   * {@link Runtime#availableProcessors()} parallel threads.
   *
   * @param perm a permutation array indexing {@code x}.
   * @param x    the array to be sorted.
   */
  public static void parallelQuickSortIndirect(final int perm[], final int[] x) {
    parallelQuickSortIndirect(perm, x, 0, x.length);
  }

  /**
   * Stabilizes a permutation.
   * <p>
   * <p>This method can be used to stabilize the permutation generated by an indirect sorting, assuming that
   * initially the permutation array was in ascending order (e.g., the identity, as usually happens). This method
   * scans the permutation, and for each non-singleton block of elements with the same associated values in {@code x},
   * permutes them in ascending order. The resulting permutation corresponds to a stable sort.
   * <p>
   * <p>Usually combining an unstable indirect sort and this method is more efficient than using a stable sort,
   * as most stable sort algorithms require a support array.
   * <p>
   * <p>More precisely, assuming that <code>x[ perm[ i ] ] &le; x[ perm[ i + 1 ] ]</code>, after
   * stabilization we will also have that <code>x[ perm[ i ] ] = x[ perm[ i + 1 ] ]</code> implies
   * <code>perm[ i ] &le; perm[ i + 1 ]</code>.
   *
   * @param perm a permutation array indexing {@code x} so that it is sorted.
   * @param x    the sorted array to be stabilized.
   * @param from the index of the first element (inclusive) to be stabilized.
   * @param to   the index of the last element (exclusive) to be stabilized.
   */
  public static void stabilize(final int perm[], final int[] x, final int from, final int to) {
    int curr = from;
    for (int i = from + 1; i < to; i++) {
      if (x[perm[i]] != x[perm[curr]]) {
        if (i - curr > 1) {
          IntArrays.parallelQuickSort(perm, curr, i);
        }
        curr = i;
      }
    }
    if (to - curr > 1) {
      IntArrays.parallelQuickSort(perm, curr, to);
    }
  }

  /**
   * Stabilizes a permutation.
   * <p>
   * <p>This method can be used to stabilize the permutation generated by an indirect sorting, assuming that
   * initially the permutation array was in ascending order (e.g., the identity, as usually happens). This method
   * scans the permutation, and for each non-singleton block of elements with the same associated values in {@code x},
   * permutes them in ascending order. The resulting permutation corresponds to a stable sort.
   * <p>
   * <p>Usually combining an unstable indirect sort and this method is more efficient than using a stable sort,
   * as most stable sort algorithms require a support array.
   * <p>
   * <p>More precisely, assuming that <code>x[ perm[ i ] ] &le; x[ perm[ i + 1 ] ]</code>, after
   * stabilization we will also have that <code>x[ perm[ i ] ] = x[ perm[ i + 1 ] ]</code> implies
   * <code>perm[ i ] &le; perm[ i + 1 ]</code>.
   *
   * @param perm a permutation array indexing {@code x} so that it is sorted.
   * @param x    the sorted array to be stabilized.
   */
  public static void stabilize(final int perm[], final int[] x) {
    stabilize(perm, x, 0, perm.length);
  }


  private static int med3(final int x[], final int[] y, final int a, final int b, final int c) {
    int t;
    final int ab = (t = (Integer.compare((x[a]), (x[b])))) == 0 ? (Integer.compare((y[a]), (y[b]))) : t;
    final int ac = (t = (Integer.compare((x[a]), (x[c])))) == 0 ? (Integer.compare((y[a]), (y[c]))) : t;
    final int bc = (t = (Integer.compare((x[b]), (x[c])))) == 0 ? (Integer.compare((y[b]), (y[c]))) : t;
    return (ab < 0 ?
      (bc < 0 ? b : ac < 0 ? c : a) :
      (bc > 0 ? b : ac > 0 ? c : a));
  }

  private static void swap(final int x[], final int[] y, final int a, final int b) {
    final int t = x[a];
    final int u = y[a];
    x[a] = x[b];
    y[a] = y[b];
    x[b] = t;
    y[b] = u;
  }

  private static void swap(final int[] x, final int[] y, int a, int b, final int n) {
    for (int i = 0; i < n; i++, a++, b++) {
      swap(x, y, a, b);
    }
  }


  private static void selectionSort(final int[] a, final int[] b, final int from, final int to) {
    for (int i = from; i < to - 1; i++) {
      int m = i, u;
      for (int j = i + 1; j < to; j++) {
        if ((u = (Integer.compare((a[j]), (a[m])))) < 0 || u == 0 && ((b[j]) < (b[m]))) {
          m = j;
        }
      }

      if (m != i) {
        int t = a[i];
        a[i] = a[m];
        a[m] = t;
        t = b[i];
        b[i] = b[m];
        b[m] = t;
      }
    }
  }

  /**
   * Sorts the specified range of elements of two arrays according to the natural lexicographical
   * ascending order using quicksort.
   * <p>
   * <p>The sorting algorithm is a tuned quicksort adapted from Jon L. Bentley and M. Douglas
   * McIlroy, &ldquo;Engineering a Sort Function&rdquo;, <i>Software: Practice and Experience</i>, 23(11), pages
   * 1249&minus;1265, 1993.
   * <p>
   * <p>This method implements a <em>lexicographical</em> sorting of the arguments. Pairs of
   * elements in the same position in the two provided arrays will be considered a single key, and
   * permuted accordingly. In the end, either <code>x[ i ] &lt; x[ i + 1 ]</code> or <code>x[ i ]
   * == x[ i + 1 ]</code> and <code>y[ i ] &le; y[ i + 1 ]</code>.
   *
   * @param x    the first array to be sorted.
   * @param y    the second array to be sorted.
   * @param from the index of the first element (inclusive) to be sorted.
   * @param to   the index of the last element (exclusive) to be sorted.
   */


  public static void quickSort(final int[] x, final int[] y, final int from, final int to) {
    final int len = to - from;
    if (len < QUICKSORT_NO_REC) {
      selectionSort(x, y, from, to);
      return;
    }
    // Choose a partition element, v
    int m = from + len / 2;
    int l = from;
    int n = to - 1;
    if (len > QUICKSORT_MEDIAN_OF_9) { // Big arrays, pseudomedian of 9
      int s = len / 8;
      l = med3(x, y, l, l + s, l + 2 * s);
      m = med3(x, y, m - s, m, m + s);
      n = med3(x, y, n - 2 * s, n - s, n);
    }
    m = med3(x, y, l, m, n); // Mid-size, med of 3
    final int v = x[m], w = y[m];
    // Establish Invariant: v* (<v)* (>v)* v*
    int a = from, b = a, c = to - 1, d = c;
    while (true) {
      int comparison, t;
      while (b <= c && (comparison = (t = (Integer.compare((x[b]), (v)))) == 0 ? (Integer.compare((y[b]), (w))) : t) <= 0) {
        if (comparison == 0) {
          swap(x, y, a++, b);
        }
        b++;
      }
      while (c >= b && (comparison = (t = (Integer.compare((x[c]), (v)))) == 0 ? (Integer.compare((y[c]), (w))) : t) >= 0) {
        if (comparison == 0) {
          swap(x, y, c, d--);
        }
        c--;
      }
      if (b > c) {
        break;
      }
      swap(x, y, b++, c--);
    }
    // Swap partition elements back to middle
    int s;
    s = Math.min(a - from, b - a);
    swap(x, y, from, b - s, s);
    s = Math.min(d - c, to - d - 1);
    swap(x, y, b, to - s, s);
    // Recursively sort non-partition-elements
    if ((s = b - a) > 1) {
      quickSort(x, y, from, from + s);
    }
    if ((s = d - c) > 1) {
      quickSort(x, y, to - s, to);
    }
  }

  /**
   * Sorts two arrays according to the natural lexicographical ascending order using quicksort.
   * <p>
   * <p>The sorting algorithm is a tuned quicksort adapted from Jon L. Bentley and M. Douglas
   * McIlroy, &ldquo;Engineering a Sort Function&rdquo;, <i>Software: Practice and Experience</i>, 23(11), pages
   * 1249&minus;1265, 1993.
   * <p>
   * <p>This method implements a <em>lexicographical</em> sorting of the arguments. Pairs of
   * elements in the same position in the two provided arrays will be considered a single key, and
   * permuted accordingly. In the end, either <code>x[ i ] &lt; x[ i + 1 ]</code> or <code>x[ i ]
   * == x[ i + 1 ]</code> and <code>y[ i ] &le; y[ i + 1 ]</code>.
   *
   * @param x the first array to be sorted.
   * @param y the second array to be sorted.
   */
  public static void quickSort(final int[] x, final int[] y) {
    ensureSameLength(x, y);
    quickSort(x, y, 0, x.length);
  }

  protected static class ForkJoinQuickSort2 extends RecursiveAction {
    private static final long serialVersionUID = 1L;
    private final int from;
    private final int to;
    private final int[] x, y;

    public ForkJoinQuickSort2(final int[] x, final int[] y, final int from, final int to) {
      this.from = from;
      this.to = to;
      this.x = x;
      this.y = y;
    }

    @Override

    protected void compute() {
      final int[] x = this.x;
      final int[] y = this.y;
      final int len = to - from;
      if (len < PARALLEL_QUICKSORT_NO_FORK) {
        quickSort(x, y, from, to);
        return;
      }
      // Choose a partition element, v
      int m = from + len / 2;
      int l = from;
      int n = to - 1;
      int s = len / 8;
      l = med3(x, y, l, l + s, l + 2 * s);
      m = med3(x, y, m - s, m, m + s);
      n = med3(x, y, n - 2 * s, n - s, n);
      m = med3(x, y, l, m, n);
      final int v = x[m], w = y[m];
      // Establish Invariant: v* (<v)* (>v)* v*
      int a = from, b = a, c = to - 1, d = c;
      while (true) {
        int comparison, t;
        while (b <= c && (comparison = (t = (Integer.compare((x[b]), (v)))) == 0 ? (Integer.compare((y[b]), (w))) : t) <= 0) {
          if (comparison == 0) {
            swap(x, y, a++, b);
          }
          b++;
        }
        while (c >= b && (comparison = (t = (Integer.compare((x[c]), (v)))) == 0 ? (Integer.compare((y[c]), (w))) : t) >= 0) {
          if (comparison == 0) {
            swap(x, y, c, d--);
          }
          c--;
        }
        if (b > c) {
          break;
        }
        swap(x, y, b++, c--);
      }
      // Swap partition elements back to middle
      int t;
      s = Math.min(a - from, b - a);
      swap(x, y, from, b - s, s);
      s = Math.min(d - c, to - d - 1);
      swap(x, y, b, to - s, s);
      s = b - a;
      t = d - c;
      // Recursively sort non-partition-elements
      if (s > 1 && t > 1) {
        invokeAll(new ForkJoinQuickSort2(x, y, from, from + s), new ForkJoinQuickSort2(x, y, to - t, to));
      } else if (s > 1) {
        invokeAll(new ForkJoinQuickSort2(x, y, from, from + s));
      } else {
        invokeAll(new ForkJoinQuickSort2(x, y, to - t, to));
      }
    }
  }

  /**
   * Sorts the specified range of elements of two arrays according to the natural lexicographical
   * ascending order using a parallel quicksort.
   * <p>
   * <p>The sorting algorithm is a tuned quicksort adapted from Jon L. Bentley and M. Douglas
   * McIlroy, &ldquo;Engineering a Sort Function&rdquo;, <i>Software: Practice and Experience</i>, 23(11), pages
   * 1249&minus;1265, 1993.
   * <p>
   * <p>This method implements a <em>lexicographical</em> sorting of the arguments. Pairs of
   * elements in the same position in the two provided arrays will be considered a single key, and
   * permuted accordingly. In the end, either <code>x[ i ] &lt; x[ i + 1 ]</code> or <code>x[ i ]
   * == x[ i + 1 ]</code> and <code>y[ i ] &le; y[ i + 1 ]</code>.
   * <p>
   * <p>This implementation uses a {@link ForkJoinPool} executor service with
   * {@link Runtime#availableProcessors()} parallel threads.
   *
   * @param x    the first array to be sorted.
   * @param y    the second array to be sorted.
   * @param from the index of the first element (inclusive) to be sorted.
   * @param to   the index of the last element (exclusive) to be sorted.
   */
  public static void parallelQuickSort(final int[] x, final int[] y, final int from, final int to) {
    if (to - from < PARALLEL_QUICKSORT_NO_FORK) {
      quickSort(x, y, from, to);
    }
    final ForkJoinPool pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
    pool.invoke(new ForkJoinQuickSort2(x, y, from, to));
    pool.shutdown();
  }

  /**
   * Sorts two arrays according to the natural lexicographical
   * ascending order using a parallel quicksort.
   * <p>
   * <p>The sorting algorithm is a tuned quicksort adapted from Jon L. Bentley and M. Douglas
   * McIlroy, &ldquo;Engineering a Sort Function&rdquo;, <i>Software: Practice and Experience</i>, 23(11), pages
   * 1249&minus;1265, 1993.
   * <p>
   * <p>This method implements a <em>lexicographical</em> sorting of the arguments. Pairs of
   * elements in the same position in the two provided arrays will be considered a single key, and
   * permuted accordingly. In the end, either <code>x[ i ] &lt; x[ i + 1 ]</code> or <code>x[ i ]
   * == x[ i + 1 ]</code> and <code>y[ i ] &le; y[ i + 1 ]</code>.
   * <p>
   * <p>This implementation uses a {@link ForkJoinPool} executor service with
   * {@link Runtime#availableProcessors()} parallel threads.
   *
   * @param x the first array to be sorted.
   * @param y the second array to be sorted.
   */
  public static void parallelQuickSort(final int[] x, final int[] y) {
    ensureSameLength(x, y);
    parallelQuickSort(x, y, 0, x.length);
  }


  /**
   * Sorts the specified range of elements according to the natural ascending order using mergesort, using a given pre-filled support array.
   * <p>
   * <p>This sort is guaranteed to be <i>stable</i>: equal elements will not be reordered as a result
   * of the sort. Moreover, no support arrays will be allocated.
   *
   * @param a    the array to be sorted.
   * @param from the index of the first element (inclusive) to be sorted.
   * @param to   the index of the last element (exclusive) to be sorted.
   * @param supp a support array containing at least <code>to</code> elements, and whose entries are identical to those
   *             of {@code a} in the specified range.
   */


  public static void mergeSort(final int a[], final int from, final int to, final int supp[]) {
    int len = to - from;

    // Insertion sort on smallest arrays
    if (len < MERGESORT_NO_REC) {
      insertionSort(a, from, to);
      return;
    }

    // Recursively sort halves of a into supp
    final int mid = (from + to) >>> 1;
    mergeSort(supp, from, mid, a);
    mergeSort(supp, mid, to, a);

    // If list is already sorted, just copy from supp to a.  This is an
    // optimization that results in faster sorts for nearly ordered lists.
    if (((supp[mid - 1]) <= (supp[mid]))) {
      System.arraycopy(supp, from, a, from, len);
      return;
    }

    // Merge sorted halves (now in supp) into a
    for (int i = from, p = from, q = mid; i < to; i++) {
      if (q >= to || p < mid && ((supp[p]) <= (supp[q]))) {
        a[i] = supp[p++];
      } else {
        a[i] = supp[q++];
      }
    }
  }

  /**
   * Sorts the specified range of elements according to the natural ascending order using mergesort.
   * <p>
   * <p>This sort is guaranteed to be <i>stable</i>: equal elements will not be reordered as a result
   * of the sort. An array as large as <code>a</code> will be allocated by this method.
   *
   * @param a    the array to be sorted.
   * @param from the index of the first element (inclusive) to be sorted.
   * @param to   the index of the last element (exclusive) to be sorted.
   */
  public static void mergeSort(final int a[], final int from, final int to) {
    mergeSort(a, from, to, a.clone());
  }

  /**
   * Sorts an array according to the natural ascending order using mergesort.
   * <p>
   * <p>This sort is guaranteed to be <i>stable</i>: equal elements will not be reordered as a result
   * of the sort. An array as large as <code>a</code> will be allocated by this method.
   *
   * @param a the array to be sorted.
   */
  public static void mergeSort(final int a[]) {
    mergeSort(a, 0, a.length);
  }

  /**
   * Sorts the specified range of elements according to the order induced by the specified
   * comparator using mergesort, using a given pre-filled support array.
   * <p>
   * <p>This sort is guaranteed to be <i>stable</i>: equal elements will not be reordered as a result
   * of the sort. Moreover, no support arrays will be allocated.
   *
   * @param a    the array to be sorted.
   * @param from the index of the first element (inclusive) to be sorted.
   * @param to   the index of the last element (exclusive) to be sorted.
   * @param comp the comparator to determine the sorting order.
   * @param supp a support array containing at least <code>to</code> elements, and whose entries are identical to those
   *             of {@code a} in the specified range.
   */
  public static void mergeSort(final int a[], final int from, final int to, IntComparator comp, final int supp[]) {
    int len = to - from;

    // Insertion sort on smallest arrays
    if (len < MERGESORT_NO_REC) {
      insertionSort(a, from, to, comp);
      return;
    }

    // Recursively sort halves of a into supp
    final int mid = (from + to) >>> 1;
    mergeSort(supp, from, mid, comp, a);
    mergeSort(supp, mid, to, comp, a);

    // If list is already sorted, just copy from supp to a.  This is an
    // optimization that results in faster sorts for nearly ordered lists.
    if (comp.compare(supp[mid - 1], supp[mid]) <= 0) {
      System.arraycopy(supp, from, a, from, len);
      return;
    }

    // Merge sorted halves (now in supp) into a
    for (int i = from, p = from, q = mid; i < to; i++) {
      if (q >= to || p < mid && comp.compare(supp[p], supp[q]) <= 0) {
        a[i] = supp[p++];
      } else {
        a[i] = supp[q++];
      }
    }
  }

  /**
   * Sorts the specified range of elements according to the order induced by the specified
   * comparator using mergesort.
   * <p>
   * <p>This sort is guaranteed to be <i>stable</i>: equal elements will not be reordered as a result
   * of the sort. An array as large as <code>a</code> will be allocated by this method.
   *
   * @param a    the array to be sorted.
   * @param from the index of the first element (inclusive) to be sorted.
   * @param to   the index of the last element (exclusive) to be sorted.
   * @param comp the comparator to determine the sorting order.
   */
  public static void mergeSort(final int a[], final int from, final int to, IntComparator comp) {
    mergeSort(a, from, to, comp, a.clone());
  }

  /**
   * Sorts an array according to the order induced by the specified
   * comparator using mergesort.
   * <p>
   * <p>This sort is guaranteed to be <i>stable</i>: equal elements will not be reordered as a result
   * of the sort.  An array as large as <code>a</code> will be allocated by this method.
   *
   * @param a    the array to be sorted.
   * @param comp the comparator to determine the sorting order.
   */
  public static void mergeSort(final int a[], IntComparator comp) {
    mergeSort(a, 0, a.length, comp);
  }


  /**
   * Searches a range of the specified array for the specified value using
   * the binary search algorithm. The range must be sorted prior to making this call.
   * If it is not sorted, the results are undefined. If the range contains multiple elements with
   * the specified value, there is no guarantee which one will be found.
   *
   * @param a    the array to be searched.
   * @param from the index of the first element (inclusive) to be searched.
   * @param to   the index of the last element (exclusive) to be searched.
   * @param key  the value to be searched for.
   * @return index of the search key, if it is contained in the array;
   * otherwise, <code>(-(<i>insertion point</i>) - 1)</code>.  The <i>insertion
   * point</i> is defined as the the point at which the value would
   * be inserted into the array: the index of the first
   * element greater than the key, or the length of the array, if all
   * elements in the array are less than the specified key.  Note
   * that this guarantees that the return value will be &ge; 0 if
   * and only if the key is found.
   * @see java.util.Arrays
   */

  public static int binarySearch(final int[] a, int from, int to, final int key) {
    int midVal;
    to--;
    while (from <= to) {
      final int mid = (from + to) >>> 1;
      midVal = a[mid];

      if (midVal < key) {
        from = mid + 1;
      } else if (midVal > key) {
        to = mid - 1;
      } else {
        return mid;
      }


    }
    return -(from + 1);
  }

  /**
   * Searches an array for the specified value using
   * the binary search algorithm. The range must be sorted prior to making this call.
   * If it is not sorted, the results are undefined. If the range contains multiple elements with
   * the specified value, there is no guarantee which one will be found.
   *
   * @param a   the array to be searched.
   * @param key the value to be searched for.
   * @return index of the search key, if it is contained in the array;
   * otherwise, <code>(-(<i>insertion point</i>) - 1)</code>.  The <i>insertion
   * point</i> is defined as the the point at which the value would
   * be inserted into the array: the index of the first
   * element greater than the key, or the length of the array, if all
   * elements in the array are less than the specified key.  Note
   * that this guarantees that the return value will be &ge; 0 if
   * and only if the key is found.
   * @see java.util.Arrays
   */
  public static int binarySearch(final int[] a, final int key) {
    return binarySearch(a, 0, a.length, key);
  }

  /**
   * Searches a range of the specified array for the specified value using
   * the binary search algorithm and a specified comparator. The range must be sorted following the comparator prior to making this call.
   * If it is not sorted, the results are undefined. If the range contains multiple elements with
   * the specified value, there is no guarantee which one will be found.
   *
   * @param a    the array to be searched.
   * @param from the index of the first element (inclusive) to be searched.
   * @param to   the index of the last element (exclusive) to be searched.
   * @param key  the value to be searched for.
   * @param c    a comparator.
   * @return index of the search key, if it is contained in the array;
   * otherwise, <code>(-(<i>insertion point</i>) - 1)</code>.  The <i>insertion
   * point</i> is defined as the the point at which the value would
   * be inserted into the array: the index of the first
   * element greater than the key, or the length of the array, if all
   * elements in the array are less than the specified key.  Note
   * that this guarantees that the return value will be &ge; 0 if
   * and only if the key is found.
   * @see java.util.Arrays
   */
  public static int binarySearch(final int[] a, int from, int to, final int key, final IntComparator c) {
    int midVal;
    to--;
    while (from <= to) {
      final int mid = (from + to) >>> 1;
      midVal = a[mid];
      final int cmp = c.compare(midVal, key);
      if (cmp < 0) {
        from = mid + 1;
      } else if (cmp > 0) {
        to = mid - 1;
      } else {
        return mid; // key found
      }
    }
    return -(from + 1);
  }

  /**
   * Searches an array for the specified value using
   * the binary search algorithm and a specified comparator. The range must be sorted following the comparator prior to making this call.
   * If it is not sorted, the results are undefined. If the range contains multiple elements with
   * the specified value, there is no guarantee which one will be found.
   *
   * @param a   the array to be searched.
   * @param key the value to be searched for.
   * @param c   a comparator.
   * @return index of the search key, if it is contained in the array;
   * otherwise, <code>(-(<i>insertion point</i>) - 1)</code>.  The <i>insertion
   * point</i> is defined as the the point at which the value would
   * be inserted into the array: the index of the first
   * element greater than the key, or the length of the array, if all
   * elements in the array are less than the specified key.  Note
   * that this guarantees that the return value will be &ge; 0 if
   * and only if the key is found.
   * @see java.util.Arrays
   */
  public static int binarySearch(final int[] a, final int key, final IntComparator c) {
    return binarySearch(a, 0, a.length, key, c);
  }


  /**
   * The size of a digit used during radix sort (must be a power of 2).
   */
  private static final int DIGIT_BITS = 8;
  /**
   * The mask to extract a digit of {@link #DIGIT_BITS} bits.
   */
  private static final int DIGIT_MASK = (1 << DIGIT_BITS) - 1;
  /**
   * The number of digits per element.
   */
  private static final int DIGITS_PER_ELEMENT = Integer.SIZE / DIGIT_BITS;
  private static final int RADIXSORT_NO_REC = 1024;
  private static final int PARALLEL_RADIXSORT_NO_FORK = 1024;

  /** This method fixes negative numbers so that the combination exponent/significand is lexicographically sorted. */
  /**
   * Sorts the specified array using radix sort.
   * <p>
   * <p>The sorting algorithm is a tuned radix sort adapted from Peter M. McIlroy, Keith Bostic and M. Douglas
   * McIlroy, &ldquo;Engineering radix sort&rdquo;, <i>Computing Systems</i>, 6(1), pages 5&minus;27 (1993).
   * <p>
   * <p>This implementation is significantly faster than quicksort
   * already at small sizes (say, more than 10000 elements), but it can only
   * sort in ascending order.
   *
   * @param a the array to be sorted.
   */
  public static void radixSort(final int[] a) {
    radixSort(a, 0, a.length);
  }

  /**
   * Sorts the specified range of an array using radix sort.
   * <p>
   * <p>The sorting algorithm is a tuned radix sort adapted from Peter M. McIlroy, Keith Bostic and M. Douglas
   * McIlroy, &ldquo;Engineering radix sort&rdquo;, <i>Computing Systems</i>, 6(1), pages 5&minus;27 (1993).
   * <p>
   * <p>This implementation is significantly faster than quicksort
   * already at small sizes (say, more than 10000 elements), but it can only
   * sort in ascending order.
   *
   * @param a    the array to be sorted.
   * @param from the index of the first element (inclusive) to be sorted.
   * @param to   the index of the last element (exclusive) to be sorted.
   */
  public static void radixSort(final int[] a, final int from, final int to) {
    if (to - from < RADIXSORT_NO_REC) {
      quickSort(a, from, to);
      return;
    }

    final int maxLevel = DIGITS_PER_ELEMENT - 1;

    final int stackSize = ((1 << DIGIT_BITS) - 1) * (DIGITS_PER_ELEMENT - 1) + 1;
    int stackPos = 0;
    final int[] offsetStack = new int[stackSize];
    final int[] lengthStack = new int[stackSize];
    final int[] levelStack = new int[stackSize];

    offsetStack[stackPos] = from;
    lengthStack[stackPos] = to - from;
    levelStack[stackPos++] = 0;

    final int[] count = new int[1 << DIGIT_BITS];
    final int[] pos = new int[1 << DIGIT_BITS];

    while (stackPos > 0) {
      final int first = offsetStack[--stackPos];
      final int length = lengthStack[stackPos];
      final int level = levelStack[stackPos];


      final int signMask = level % DIGITS_PER_ELEMENT == 0 ? 1 << DIGIT_BITS - 1 : 0;

      final int shift = (DIGITS_PER_ELEMENT - 1 - level % DIGITS_PER_ELEMENT) * DIGIT_BITS; // This is the shift that extract the right byte from a key

      // Count keys.
      for (int i = first + length; i-- != first; ) {
        count[((a[i]) >>> shift & DIGIT_MASK ^ signMask)]++;
      }
      // Compute cumulative distribution
      int lastUsed = -1;
      for (int i = 0, p = first; i < 1 << DIGIT_BITS; i++) {
        if (count[i] != 0) {
          lastUsed = i;
        }
        pos[i] = (p += count[i]);
      }

      final int end = first + length - count[lastUsed];
      // i moves through the start of each block
      for (int i = first, c = -1, d; i <= end; i += count[c], count[c] = 0) {
        int t = a[i];
        c = ((t) >>> shift & DIGIT_MASK ^ signMask);

        if (i < end) { // When all slots are OK, the last slot is necessarily OK.
          while ((d = --pos[c]) > i) {
            final int z = t;
            t = a[d];
            a[d] = z;
            c = ((t) >>> shift & DIGIT_MASK ^ signMask);
          }
          a[i] = t;
        }

        if (level < maxLevel && count[c] > 1) {
          if (count[c] < RADIXSORT_NO_REC) {
            quickSort(a, i, i + count[c]);
          } else {
            offsetStack[stackPos] = i;
            lengthStack[stackPos] = count[c];
            levelStack[stackPos++] = level + 1;
          }
        }
      }
    }
  }

  protected final static class Segment {
    protected final int offset, length, level;

    protected Segment(final int offset, final int length, final int level) {
      this.offset = offset;
      this.length = length;
      this.level = level;
    }

    @Override
    public String toString() {
      return "Segment [offset=" + offset + ", length=" + length + ", level=" + level + "]";
    }
  }

  protected final static Segment POISON_PILL = new Segment(-1, -1, -1);


  /**
   * Sorts the specified range of an array using parallel radix sort.
   * <p>
   * <p>The sorting algorithm is a tuned radix sort adapted from Peter M. McIlroy, Keith Bostic and M. Douglas
   * McIlroy, &ldquo;Engineering radix sort&rdquo;, <i>Computing Systems</i>, 6(1), pages 5&minus;27 (1993).
   * <p>
   * <p>This implementation uses a pool of {@link Runtime#availableProcessors()} threads.
   *
   * @param a    the array to be sorted.
   * @param from the index of the first element (inclusive) to be sorted.
   * @param to   the index of the last element (exclusive) to be sorted.
   */
  public static void parallelRadixSort(final int[] a, final int from, final int to) {
    if (to - from < PARALLEL_RADIXSORT_NO_FORK) {
      quickSort(a, from, to);
      return;
    }
    final int maxLevel = DIGITS_PER_ELEMENT - 1;
    final LinkedBlockingQueue<Segment> queue = new LinkedBlockingQueue<Segment>();
    queue.add(new Segment(from, to - from, 0));
    final AtomicInteger queueSize = new AtomicInteger(1);
    final int numberOfThreads = Runtime.getRuntime().availableProcessors();
    final ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads, Executors.defaultThreadFactory());
    final ExecutorCompletionService<Void> executorCompletionService = new ExecutorCompletionService<Void>(executorService);

    for (int i = numberOfThreads; i-- != 0; ) {
      executorCompletionService.submit(new Callable<Void>() {
        public Void call() throws Exception {
          final int[] count = new int[1 << DIGIT_BITS];
          final int[] pos = new int[1 << DIGIT_BITS];

          for (; ; ) {
            if (queueSize.get() == 0) {
              for (int i = numberOfThreads; i-- != 0; ) {
                queue.add(POISON_PILL);
              }
            }
            final Segment segment = queue.take();
            if (segment == POISON_PILL) {
              return null;
            }

            final int first = segment.offset;
            final int length = segment.length;
            final int level = segment.level;


            final int signMask = level % DIGITS_PER_ELEMENT == 0 ? 1 << DIGIT_BITS - 1 : 0;

            final int shift = (DIGITS_PER_ELEMENT - 1 - level % DIGITS_PER_ELEMENT) * DIGIT_BITS; // This is the shift that extract the right byte from a key

            // Count keys.
            for (int i = first + length; i-- != first; ) {
              count[((a[i]) >>> shift & DIGIT_MASK ^ signMask)]++;
            }
            // Compute cumulative distribution
            int lastUsed = -1;
            for (int i = 0, p = first; i < 1 << DIGIT_BITS; i++) {
              if (count[i] != 0) {
                lastUsed = i;
              }
              pos[i] = (p += count[i]);
            }

            final int end = first + length - count[lastUsed];
            // i moves through the start of each block
            for (int i = first, c = -1, d; i <= end; i += count[c], count[c] = 0) {
              int t = a[i];
              c = ((t) >>> shift & DIGIT_MASK ^ signMask);
              if (i < end) {
                while ((d = --pos[c]) > i) {
                  final int z = t;
                  t = a[d];
                  a[d] = z;
                  c = ((t) >>> shift & DIGIT_MASK ^ signMask);
                }
                a[i] = t;
              }

              if (level < maxLevel && count[c] > 1) {
                if (count[c] < PARALLEL_RADIXSORT_NO_FORK) {
                  quickSort(a, i, i + count[c]);
                } else {
                  queueSize.incrementAndGet();
                  queue.add(new Segment(i, count[c], level + 1));
                }
              }
            }
            queueSize.decrementAndGet();
          }
        }
      });
    }

    Throwable problem = null;
    for (int i = numberOfThreads; i-- != 0; ) {
      try {
        executorCompletionService.take().get();
      } catch (Exception e) {
        problem = e.getCause(); // We keep only the last one. They will be logged anyway.
      }
    }

    executorService.shutdown();
    if (problem != null) {
      throw (problem instanceof RuntimeException) ? (RuntimeException) problem : new RuntimeException(problem);
    }
  }

  /**
   * Sorts the specified array using parallel radix sort.
   * <p>
   * <p>The sorting algorithm is a tuned radix sort adapted from Peter M. McIlroy, Keith Bostic and M. Douglas
   * McIlroy, &ldquo;Engineering radix sort&rdquo;, <i>Computing Systems</i>, 6(1), pages 5&minus;27 (1993).
   * <p>
   * <p>This implementation uses a pool of {@link Runtime#availableProcessors()} threads.
   *
   * @param a the array to be sorted.
   */
  public static void parallelRadixSort(final int[] a) {
    parallelRadixSort(a, 0, a.length);
  }

  /**
   * Sorts the specified array using indirect radix sort.
   * <p>
   * <p>The sorting algorithm is a tuned radix sort adapted from Peter M. McIlroy, Keith Bostic and M. Douglas
   * McIlroy, &ldquo;Engineering radix sort&rdquo;, <i>Computing Systems</i>, 6(1), pages 5&minus;27 (1993).
   * <p>
   * <p>This method implement an <em>indirect</em> sort. The elements of <code>perm</code> (which must
   * be exactly the numbers in the interval <code>[0..perm.length)</code>) will be permuted so that
   * <code>a[ perm[ i ] ] &le; a[ perm[ i + 1 ] ]</code>.
   * <p>
   * <p>This implementation will allocate, in the stable case, a support array as large as <code>perm</code> (note that the stable
   * version is slightly faster).
   *
   * @param perm   a permutation array indexing <code>a</code>.
   * @param a      the array to be sorted.
   * @param stable whether the sorting algorithm should be stable.
   */
  public static void radixSortIndirect(final int[] perm, final int[] a, final boolean stable) {
    radixSortIndirect(perm, a, 0, perm.length, stable);
  }

  /**
   * Sorts the specified array using indirect radix sort.
   * <p>
   * <p>The sorting algorithm is a tuned radix sort adapted from Peter M. McIlroy, Keith Bostic and M. Douglas
   * McIlroy, &ldquo;Engineering radix sort&rdquo;, <i>Computing Systems</i>, 6(1), pages 5&minus;27 (1993).
   * <p>
   * <p>This method implement an <em>indirect</em> sort. The elements of <code>perm</code> (which must
   * be exactly the numbers in the interval <code>[0..perm.length)</code>) will be permuted so that
   * <code>a[ perm[ i ] ] &le; a[ perm[ i + 1 ] ]</code>.
   * <p>
   * <p>This implementation will allocate, in the stable case, a support array as large as <code>perm</code> (note that the stable
   * version is slightly faster).
   *
   * @param perm   a permutation array indexing <code>a</code>.
   * @param a      the array to be sorted.
   * @param from   the index of the first element of <code>perm</code> (inclusive) to be permuted.
   * @param to     the index of the last element of <code>perm</code> (exclusive) to be permuted.
   * @param stable whether the sorting algorithm should be stable.
   */
  public static void radixSortIndirect(final int[] perm, final int[] a, final int from, final int to, final boolean stable) {
    if (to - from < RADIXSORT_NO_REC) {
      insertionSortIndirect(perm, a, from, to);
      return;
    }

    final int maxLevel = DIGITS_PER_ELEMENT - 1;

    final int stackSize = ((1 << DIGIT_BITS) - 1) * (DIGITS_PER_ELEMENT - 1) + 1;
    int stackPos = 0;
    final int[] offsetStack = new int[stackSize];
    final int[] lengthStack = new int[stackSize];
    final int[] levelStack = new int[stackSize];

    offsetStack[stackPos] = from;
    lengthStack[stackPos] = to - from;
    levelStack[stackPos++] = 0;

    final int[] count = new int[1 << DIGIT_BITS];
    final int[] pos = new int[1 << DIGIT_BITS];
    final int[] support = stable ? new int[perm.length] : null;

    while (stackPos > 0) {
      final int first = offsetStack[--stackPos];
      final int length = lengthStack[stackPos];
      final int level = levelStack[stackPos];


      final int signMask = level % DIGITS_PER_ELEMENT == 0 ? 1 << DIGIT_BITS - 1 : 0;


      final int shift = (DIGITS_PER_ELEMENT - 1 - level % DIGITS_PER_ELEMENT) * DIGIT_BITS; // This is the shift that extract the right byte from a key

      // Count keys.
      for (int i = first + length; i-- != first; ) {
        count[((a[perm[i]]) >>> shift & DIGIT_MASK ^ signMask)]++;
      }
      // Compute cumulative distribution
      int lastUsed = -1;
      for (int i = 0, p = stable ? 0 : first; i < 1 << DIGIT_BITS; i++) {
        if (count[i] != 0) {
          lastUsed = i;
        }
        pos[i] = (p += count[i]);
      }

      if (stable) {
        for (int i = first + length; i-- != first; ) {
          support[--pos[((a[perm[i]]) >>> shift & DIGIT_MASK ^ signMask)]] = perm[i];
        }
        System.arraycopy(support, 0, perm, first, length);
        for (int i = 0, p = first; i <= lastUsed; i++) {
          if (level < maxLevel && count[i] > 1) {
            if (count[i] < RADIXSORT_NO_REC) {
              insertionSortIndirect(perm, a, p, p + count[i]);
            } else {
              offsetStack[stackPos] = p;
              lengthStack[stackPos] = count[i];
              levelStack[stackPos++] = level + 1;
            }
          }
          p += count[i];
        }
        java.util.Arrays.fill(count, 0);
      } else {
        final int end = first + length - count[lastUsed];
        // i moves through the start of each block
        for (int i = first, c = -1, d; i <= end; i += count[c], count[c] = 0) {
          int t = perm[i];
          c = ((a[t]) >>> shift & DIGIT_MASK ^ signMask);

          if (i < end) { // When all slots are OK, the last slot is necessarily OK.
            while ((d = --pos[c]) > i) {
              final int z = t;
              t = perm[d];
              perm[d] = z;
              c = ((a[t]) >>> shift & DIGIT_MASK ^ signMask);
            }
            perm[i] = t;
          }

          if (level < maxLevel && count[c] > 1) {
            if (count[c] < RADIXSORT_NO_REC) {
              insertionSortIndirect(perm, a, i, i + count[c]);
            } else {
              offsetStack[stackPos] = i;
              lengthStack[stackPos] = count[c];
              levelStack[stackPos++] = level + 1;
            }
          }
        }
      }
    }
  }

  /**
   * Sorts the specified range of an array using parallel indirect radix sort.
   * <p>
   * <p>The sorting algorithm is a tuned radix sort adapted from Peter M. McIlroy, Keith Bostic and M. Douglas
   * McIlroy, &ldquo;Engineering radix sort&rdquo;, <i>Computing Systems</i>, 6(1), pages 5&minus;27 (1993).
   * <p>
   * <p>This method implement an <em>indirect</em> sort. The elements of <code>perm</code> (which must
   * be exactly the numbers in the interval <code>[0..perm.length)</code>) will be permuted so that
   * <code>a[ perm[ i ] ] &le; a[ perm[ i + 1 ] ]</code>.
   * <p>
   * <p>This implementation uses a pool of {@link Runtime#availableProcessors()} threads.
   *
   * @param perm   a permutation array indexing <code>a</code>.
   * @param a      the array to be sorted.
   * @param from   the index of the first element (inclusive) to be sorted.
   * @param to     the index of the last element (exclusive) to be sorted.
   * @param stable whether the sorting algorithm should be stable.
   */
  public static void parallelRadixSortIndirect(final int perm[], final int[] a, final int from, final int to, final boolean stable) {
    if (to - from < PARALLEL_RADIXSORT_NO_FORK) {
      radixSortIndirect(perm, a, from, to, stable);
      return;
    }
    final int maxLevel = DIGITS_PER_ELEMENT - 1;
    final LinkedBlockingQueue<Segment> queue = new LinkedBlockingQueue<Segment>();
    queue.add(new Segment(from, to - from, 0));
    final AtomicInteger queueSize = new AtomicInteger(1);
    final int numberOfThreads = Runtime.getRuntime().availableProcessors();
    final ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads, Executors.defaultThreadFactory());
    final ExecutorCompletionService<Void> executorCompletionService = new ExecutorCompletionService<Void>(executorService);
    final int[] support = stable ? new int[perm.length] : null;

    for (int i = numberOfThreads; i-- != 0; ) {
      executorCompletionService.submit(new Callable<Void>() {
        public Void call() throws Exception {
          final int[] count = new int[1 << DIGIT_BITS];
          final int[] pos = new int[1 << DIGIT_BITS];

          for (; ; ) {
            if (queueSize.get() == 0) {
              for (int i = numberOfThreads; i-- != 0; ) {
                queue.add(POISON_PILL);
              }
            }
            final Segment segment = queue.take();
            if (segment == POISON_PILL) {
              return null;
            }

            final int first = segment.offset;
            final int length = segment.length;
            final int level = segment.level;


            final int signMask = level % DIGITS_PER_ELEMENT == 0 ? 1 << DIGIT_BITS - 1 : 0;

            final int shift = (DIGITS_PER_ELEMENT - 1 - level % DIGITS_PER_ELEMENT) * DIGIT_BITS; // This is the shift that extract the right byte from a key


            // Count keys.
            for (int i = first + length; i-- != first; ) {
              count[((a[perm[i]]) >>> shift & DIGIT_MASK ^ signMask)]++;
            }
            // Compute cumulative distribution
            int lastUsed = -1;
            for (int i = 0, p = first; i < 1 << DIGIT_BITS; i++) {
              if (count[i] != 0) {
                lastUsed = i;
              }
              pos[i] = (p += count[i]);
            }

            if (stable) {
              for (int i = first + length; i-- != first; ) {
                support[--pos[((a[perm[i]]) >>> shift & DIGIT_MASK ^ signMask)]] = perm[i];
              }
              System.arraycopy(support, first, perm, first, length);
              for (int i = 0, p = first; i <= lastUsed; i++) {
                if (level < maxLevel && count[i] > 1) {
                  if (count[i] < PARALLEL_RADIXSORT_NO_FORK) {
                    radixSortIndirect(perm, a, p, p + count[i], stable);
                  } else {
                    queueSize.incrementAndGet();
                    queue.add(new Segment(p, count[i], level + 1));
                  }
                }
                p += count[i];
              }
              java.util.Arrays.fill(count, 0);
            } else {
              final int end = first + length - count[lastUsed];
              // i moves through the start of each block
              for (int i = first, c = -1, d; i <= end; i += count[c], count[c] = 0) {
                int t = perm[i];
                c = ((a[t]) >>> shift & DIGIT_MASK ^ signMask);

                if (i < end) { // When all slots are OK, the last slot is necessarily OK.
                  while ((d = --pos[c]) > i) {
                    final int z = t;
                    t = perm[d];
                    perm[d] = z;
                    c = ((a[t]) >>> shift & DIGIT_MASK ^ signMask);
                  }
                  perm[i] = t;
                }

                if (level < maxLevel && count[c] > 1) {
                  if (count[c] < PARALLEL_RADIXSORT_NO_FORK) {
                    radixSortIndirect(perm, a, i, i + count[c], stable);
                  } else {
                    queueSize.incrementAndGet();
                    queue.add(new Segment(i, count[c], level + 1));
                  }
                }
              }
            }
            queueSize.decrementAndGet();
          }
        }
      });
    }

    Throwable problem = null;
    for (int i = numberOfThreads; i-- != 0; ) {
      try {
        executorCompletionService.take().get();
      } catch (Exception e) {
        problem = e.getCause(); // We keep only the last one. They will be logged anyway.
      }
    }

    executorService.shutdown();
    if (problem != null) {
      throw (problem instanceof RuntimeException) ? (RuntimeException) problem : new RuntimeException(problem);
    }
  }

  /**
   * Sorts the specified array using parallel indirect radix sort.
   * <p>
   * <p>The sorting algorithm is a tuned radix sort adapted from Peter M. McIlroy, Keith Bostic and M. Douglas
   * McIlroy, &ldquo;Engineering radix sort&rdquo;, <i>Computing Systems</i>, 6(1), pages 5&minus;27 (1993).
   * <p>
   * <p>This method implement an <em>indirect</em> sort. The elements of <code>perm</code> (which must
   * be exactly the numbers in the interval <code>[0..perm.length)</code>) will be permuted so that
   * <code>a[ perm[ i ] ] &le; a[ perm[ i + 1 ] ]</code>.
   * <p>
   * <p>This implementation uses a pool of {@link Runtime#availableProcessors()} threads.
   *
   * @param perm   a permutation array indexing <code>a</code>.
   * @param a      the array to be sorted.
   * @param stable whether the sorting algorithm should be stable.
   */
  public static void parallelRadixSortIndirect(final int perm[], final int[] a, final boolean stable) {
    parallelRadixSortIndirect(perm, a, 0, a.length, stable);
  }

  /**
   * Sorts the specified pair of arrays lexicographically using radix sort.
   * <p>The sorting algorithm is a tuned radix sort adapted from Peter M. McIlroy, Keith Bostic and M. Douglas
   * McIlroy, &ldquo;Engineering radix sort&rdquo;, <i>Computing Systems</i>, 6(1), pages 5&minus;27 (1993).
   * <p>
   * <p>This method implements a <em>lexicographical</em> sorting of the arguments. Pairs of elements
   * in the same position in the two provided arrays will be considered a single key, and permuted
   * accordingly. In the end, either <code>a[ i ] &lt; a[ i + 1 ]</code> or <code>a[ i ] == a[ i + 1 ]</code> and <code>b[ i ] &le; b[ i + 1 ]</code>.
   *
   * @param a the first array to be sorted.
   * @param b the second array to be sorted.
   */

  public static void radixSort(final int[] a, final int[] b) {
    ensureSameLength(a, b);
    radixSort(a, b, 0, a.length);
  }

  /**
   * Sorts the specified range of elements of two arrays using radix sort.
   * <p>
   * <p>The sorting algorithm is a tuned radix sort adapted from Peter M. McIlroy, Keith Bostic and M. Douglas
   * McIlroy, &ldquo;Engineering radix sort&rdquo;, <i>Computing Systems</i>, 6(1), pages 5&minus;27 (1993).
   * <p>
   * <p>This method implements a <em>lexicographical</em> sorting of the arguments. Pairs of elements
   * in the same position in the two provided arrays will be considered a single key, and permuted
   * accordingly. In the end, either <code>a[ i ] &lt; a[ i + 1 ]</code> or <code>a[ i ] == a[ i + 1 ]</code> and <code>b[ i ] &le; b[ i + 1 ]</code>.
   *
   * @param a    the first array to be sorted.
   * @param b    the second array to be sorted.
   * @param from the index of the first element (inclusive) to be sorted.
   * @param to   the index of the last element (exclusive) to be sorted.
   */
  public static void radixSort(final int[] a, final int[] b, final int from, final int to) {
    if (to - from < RADIXSORT_NO_REC) {
      selectionSort(a, b, from, to);
      return;
    }

    final int layers = 2;
    final int maxLevel = DIGITS_PER_ELEMENT * layers - 1;

    final int stackSize = ((1 << DIGIT_BITS) - 1) * (layers * DIGITS_PER_ELEMENT - 1) + 1;
    int stackPos = 0;
    final int[] offsetStack = new int[stackSize];
    final int[] lengthStack = new int[stackSize];
    final int[] levelStack = new int[stackSize];

    offsetStack[stackPos] = from;
    lengthStack[stackPos] = to - from;
    levelStack[stackPos++] = 0;

    final int[] count = new int[1 << DIGIT_BITS];
    final int[] pos = new int[1 << DIGIT_BITS];

    while (stackPos > 0) {
      final int first = offsetStack[--stackPos];
      final int length = lengthStack[stackPos];
      final int level = levelStack[stackPos];


      final int signMask = level % DIGITS_PER_ELEMENT == 0 ? 1 << DIGIT_BITS - 1 : 0;


      final int[] k = level < DIGITS_PER_ELEMENT ? a : b; // This is the key array
      final int shift = (DIGITS_PER_ELEMENT - 1 - level % DIGITS_PER_ELEMENT) * DIGIT_BITS; // This is the shift that extract the right byte from a key

      // Count keys.
      for (int i = first + length; i-- != first; ) {
        count[((k[i]) >>> shift & DIGIT_MASK ^ signMask)]++;
      }

      // Compute cumulative distribution
      int lastUsed = -1;
      for (int i = 0, p = first; i < 1 << DIGIT_BITS; i++) {
        if (count[i] != 0) {
          lastUsed = i;
        }
        pos[i] = (p += count[i]);
      }


      final int end = first + length - count[lastUsed];
      // i moves through the start of each block
      for (int i = first, c = -1, d; i <= end; i += count[c], count[c] = 0) {
        int t = a[i];
        int u = b[i];
        c = ((k[i]) >>> shift & DIGIT_MASK ^ signMask);

        if (i < end) { // When all slots are OK, the last slot is necessarily OK.
          while ((d = --pos[c]) > i) {
            c = ((k[d]) >>> shift & DIGIT_MASK ^ signMask);
            int z = t;
            t = a[d];
            a[d] = z;
            z = u;
            u = b[d];
            b[d] = z;
          }
          a[i] = t;
          b[i] = u;

        }

        if (level < maxLevel && count[c] > 1) {
          if (count[c] < RADIXSORT_NO_REC) {
            selectionSort(a, b, i, i + count[c]);
          } else {
            offsetStack[stackPos] = i;
            lengthStack[stackPos] = count[c];
            levelStack[stackPos++] = level + 1;
          }
        }
      }
    }
  }

  /**
   * Sorts the specified range of elements of two arrays using a parallel radix sort.
   * <p>
   * <p>The sorting algorithm is a tuned radix sort adapted from Peter M. McIlroy, Keith Bostic and M. Douglas
   * McIlroy, &ldquo;Engineering radix sort&rdquo;, <i>Computing Systems</i>, 6(1), pages 5&minus;27 (1993).
   * <p>
   * <p>This method implements a <em>lexicographical</em> sorting of the arguments. Pairs of elements
   * in the same position in the two provided arrays will be considered a single key, and permuted
   * accordingly. In the end, either <code>a[ i ] &lt; a[ i + 1 ]</code> or <code>a[ i ] == a[ i + 1 ]</code> and <code>b[ i ] &le; b[ i + 1 ]</code>.
   * <p>
   * <p>This implementation uses a pool of {@link Runtime#availableProcessors()} threads.
   *
   * @param a    the first array to be sorted.
   * @param b    the second array to be sorted.
   * @param from the index of the first element (inclusive) to be sorted.
   * @param to   the index of the last element (exclusive) to be sorted.
   */
  public static void parallelRadixSort(final int[] a, final int[] b, final int from, final int to) {
    if (to - from < PARALLEL_RADIXSORT_NO_FORK) {
      quickSort(a, b, from, to);
      return;
    }
    final int layers = 2;
    if (a.length != b.length) {
      throw new IllegalArgumentException("Array size mismatch.");
    }
    final int maxLevel = DIGITS_PER_ELEMENT * layers - 1;
    final LinkedBlockingQueue<Segment> queue = new LinkedBlockingQueue<Segment>();
    queue.add(new Segment(from, to - from, 0));
    final AtomicInteger queueSize = new AtomicInteger(1);
    final int numberOfThreads = Runtime.getRuntime().availableProcessors();
    final ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads, Executors.defaultThreadFactory());
    final ExecutorCompletionService<Void> executorCompletionService = new ExecutorCompletionService<Void>(executorService);
    for (int i = numberOfThreads; i-- != 0; ) {
      executorCompletionService.submit(new Callable<Void>() {
        public Void call() throws Exception {
          final int[] count = new int[1 << DIGIT_BITS];
          final int[] pos = new int[1 << DIGIT_BITS];
          for (; ; ) {
            if (queueSize.get() == 0) {
              for (int i = numberOfThreads; i-- != 0; ) {
                queue.add(POISON_PILL);
              }
            }
            final Segment segment = queue.take();
            if (segment == POISON_PILL) {
              return null;
            }
            final int first = segment.offset;
            final int length = segment.length;
            final int level = segment.level;
            final int signMask = level % DIGITS_PER_ELEMENT == 0 ? 1 << DIGIT_BITS - 1 : 0;
            final int[] k = level < DIGITS_PER_ELEMENT ? a : b; // This is the key array
            final int shift = (DIGITS_PER_ELEMENT - 1 - level % DIGITS_PER_ELEMENT) * DIGIT_BITS;
            // Count keys.
            for (int i = first + length; i-- != first; ) {
              count[((k[i]) >>> shift & DIGIT_MASK ^ signMask)]++;
            }
            // Compute cumulative distribution
            int lastUsed = -1;
            for (int i = 0, p = first; i < 1 << DIGIT_BITS; i++) {
              if (count[i] != 0) {
                lastUsed = i;
              }
              pos[i] = (p += count[i]);
            }
            final int end = first + length - count[lastUsed];
            for (int i = first, c = -1, d; i <= end; i += count[c], count[c] = 0) {
              int t = a[i];
              int u = b[i];
              c = ((k[i]) >>> shift & DIGIT_MASK ^ signMask);
              if (i < end) { // When all slots are OK, the last slot is necessarily OK.
                while ((d = --pos[c]) > i) {
                  c = ((k[d]) >>> shift & DIGIT_MASK ^ signMask);
                  final int z = t;
                  final int w = u;
                  t = a[d];
                  u = b[d];
                  a[d] = z;
                  b[d] = w;
                }
                a[i] = t;
                b[i] = u;
              }
              if (level < maxLevel && count[c] > 1) {
                if (count[c] < PARALLEL_RADIXSORT_NO_FORK) {
                  quickSort(a, b, i, i + count[c]);
                } else {
                  queueSize.incrementAndGet();
                  queue.add(new Segment(i, count[c], level + 1));
                }
              }
            }
            queueSize.decrementAndGet();
          }
        }
      });
    }
    Throwable problem = null;
    for (int i = numberOfThreads; i-- != 0; ) {
      try {
        executorCompletionService.take().get();
      } catch (Exception e) {
        problem = e.getCause(); // We keep only the last one. They will be logged anyway.
      }
    }
    executorService.shutdown();
    if (problem != null) {
      throw (problem instanceof RuntimeException) ? (RuntimeException) problem : new RuntimeException(problem);
    }
  }


  /**
   * Sorts two arrays using a parallel radix sort.
   * <p>
   * <p>The sorting algorithm is a tuned radix sort adapted from Peter M. McIlroy, Keith Bostic and M. Douglas
   * McIlroy, &ldquo;Engineering radix sort&rdquo;, <i>Computing Systems</i>, 6(1), pages 5&minus;27 (1993).
   * <p>
   * <p>This method implements a <em>lexicographical</em> sorting of the arguments. Pairs of elements
   * in the same position in the two provided arrays will be considered a single key, and permuted
   * accordingly. In the end, either <code>a[ i ] &lt; a[ i + 1 ]</code> or <code>a[ i ] == a[ i + 1 ]</code> and <code>b[ i ] &le; b[ i + 1 ]</code>.
   * <p>
   * <p>This implementation uses a pool of {@link Runtime#availableProcessors()} threads.
   *
   * @param a the first array to be sorted.
   * @param b the second array to be sorted.
   */
  public static void parallelRadixSort(final int[] a, final int[] b) {
    ensureSameLength(a, b);
    parallelRadixSort(a, b, 0, a.length);
  }


  private static void insertionSortIndirect(final int[] perm, final int[] a, final int[] b, final int from, final int to) {
    for (int i = from; ++i < to; ) {
      int t = perm[i];
      int j = i;
      for (int u = perm[j - 1]; ((a[t]) < (a[u])) || ((a[t]) == (a[u])) && ((b[t]) < (b[u])); u = perm[--j - 1]) {
        perm[j] = u;
        if (from == j - 1) {
          --j;
          break;
        }
      }
      perm[j] = t;
    }
  }

  /**
   * Sorts the specified pair of arrays lexicographically using indirect radix sort.
   * <p>
   * <p>The sorting algorithm is a tuned radix sort adapted from Peter M. McIlroy, Keith Bostic and M. Douglas
   * McIlroy, &ldquo;Engineering radix sort&rdquo;, <i>Computing Systems</i>, 6(1), pages 5&minus;27 (1993).
   * <p>
   * <p>This method implement an <em>indirect</em> sort. The elements of <code>perm</code> (which must
   * be exactly the numbers in the interval <code>[0..perm.length)</code>) will be permuted so that
   * <code>a[ perm[ i ] ] &le; a[ perm[ i + 1 ] ]</code>.
   * <p>
   * <p>This implementation will allocate, in the stable case, a further support array as large as <code>perm</code> (note that the stable
   * version is slightly faster).
   *
   * @param perm   a permutation array indexing <code>a</code>.
   * @param a      the array to be sorted.
   * @param b      the second array to be sorted.
   * @param stable whether the sorting algorithm should be stable.
   */
  public static void radixSortIndirect(final int[] perm, final int[] a, final int[] b, final boolean stable) {
    ensureSameLength(a, b);
    radixSortIndirect(perm, a, b, 0, a.length, stable);
  }

  /**
   * Sorts the specified pair of arrays lexicographically using indirect radix sort.
   * <p>
   * <p>The sorting algorithm is a tuned radix sort adapted from Peter M. McIlroy, Keith Bostic and M. Douglas
   * McIlroy, &ldquo;Engineering radix sort&rdquo;, <i>Computing Systems</i>, 6(1), pages 5&minus;27 (1993).
   * <p>
   * <p>This method implement an <em>indirect</em> sort. The elements of <code>perm</code> (which must
   * be exactly the numbers in the interval <code>[0..perm.length)</code>) will be permuted so that
   * <code>a[ perm[ i ] ] &le; a[ perm[ i + 1 ] ]</code>.
   * <p>
   * <p>This implementation will allocate, in the stable case, a further support array as large as <code>perm</code> (note that the stable
   * version is slightly faster).
   *
   * @param perm   a permutation array indexing <code>a</code>.
   * @param a      the array to be sorted.
   * @param b      the second array to be sorted.
   * @param from   the index of the first element of <code>perm</code> (inclusive) to be permuted.
   * @param to     the index of the last element of <code>perm</code> (exclusive) to be permuted.
   * @param stable whether the sorting algorithm should be stable.
   */
  public static void radixSortIndirect(final int[] perm, final int[] a, final int[] b, final int from, final int to, final boolean stable) {
    if (to - from < RADIXSORT_NO_REC) {
      insertionSortIndirect(perm, a, b, from, to);
      return;
    }

    final int layers = 2;
    final int maxLevel = DIGITS_PER_ELEMENT * layers - 1;

    final int stackSize = ((1 << DIGIT_BITS) - 1) * (layers * DIGITS_PER_ELEMENT - 1) + 1;
    int stackPos = 0;
    final int[] offsetStack = new int[stackSize];
    final int[] lengthStack = new int[stackSize];
    final int[] levelStack = new int[stackSize];

    offsetStack[stackPos] = from;
    lengthStack[stackPos] = to - from;
    levelStack[stackPos++] = 0;

    final int[] count = new int[1 << DIGIT_BITS];
    final int[] pos = new int[1 << DIGIT_BITS];
    final int[] support = stable ? new int[perm.length] : null;

    while (stackPos > 0) {
      final int first = offsetStack[--stackPos];
      final int length = lengthStack[stackPos];
      final int level = levelStack[stackPos];


      final int signMask = level % DIGITS_PER_ELEMENT == 0 ? 1 << DIGIT_BITS - 1 : 0;


      final int[] k = level < DIGITS_PER_ELEMENT ? a : b; // This is the key array
      final int shift = (DIGITS_PER_ELEMENT - 1 - level % DIGITS_PER_ELEMENT) * DIGIT_BITS; // This is the shift that extract the right byte from a key

      // Count keys.
      for (int i = first + length; i-- != first; ) {
        count[((k[perm[i]]) >>> shift & DIGIT_MASK ^ signMask)]++;
      }

      // Compute cumulative distribution
      int lastUsed = -1;
      for (int i = 0, p = stable ? 0 : first; i < 1 << DIGIT_BITS; i++) {
        if (count[i] != 0) {
          lastUsed = i;
        }
        pos[i] = (p += count[i]);
      }

      if (stable) {
        for (int i = first + length; i-- != first; ) {
          support[--pos[((k[perm[i]]) >>> shift & DIGIT_MASK ^ signMask)]] = perm[i];
        }
        System.arraycopy(support, 0, perm, first, length);
        for (int i = 0, p = first; i < 1 << DIGIT_BITS; i++) {
          if (level < maxLevel && count[i] > 1) {
            if (count[i] < RADIXSORT_NO_REC) {
              insertionSortIndirect(perm, a, b, p, p + count[i]);
            } else {
              offsetStack[stackPos] = p;
              lengthStack[stackPos] = count[i];
              levelStack[stackPos++] = level + 1;
            }
          }
          p += count[i];
        }
        java.util.Arrays.fill(count, 0);
      } else {
        final int end = first + length - count[lastUsed];
        // i moves through the start of each block
        for (int i = first, c = -1, d; i <= end; i += count[c], count[c] = 0) {
          int t = perm[i];
          c = ((k[t]) >>> shift & DIGIT_MASK ^ signMask);

          if (i < end) { // When all slots are OK, the last slot is necessarily OK.
            while ((d = --pos[c]) > i) {
              final int z = t;
              t = perm[d];
              perm[d] = z;
              c = ((k[t]) >>> shift & DIGIT_MASK ^ signMask);
            }
            perm[i] = t;
          }


          if (level < maxLevel && count[c] > 1) {
            if (count[c] < RADIXSORT_NO_REC) {
              insertionSortIndirect(perm, a, b, i, i + count[c]);
            } else {
              offsetStack[stackPos] = i;
              lengthStack[stackPos] = count[c];
              levelStack[stackPos++] = level + 1;
            }
          }
        }
      }
    }
  }


  private static void selectionSort(final int[][] a, final int from, final int to, final int level) {
    final int layers = a.length;
    final int firstLayer = level / DIGITS_PER_ELEMENT;

    for (int i = from; i < to - 1; i++) {
      int m = i;
      for (int j = i + 1; j < to; j++) {
        for (int p = firstLayer; p < layers; p++) {
          if (a[p][j] < a[p][m]) {
            m = j;
            break;
          } else if (a[p][j] > a[p][m]) {
            break;
          }
        }
      }
      if (m != i) {
        for (int p = layers; p-- != 0; ) {
          final int u = a[p][i];
          a[p][i] = a[p][m];
          a[p][m] = u;
        }
      }
    }
  }


  /**
   * Sorts the specified array of arrays lexicographically using radix sort.
   * <p>
   * <p>The sorting algorithm is a tuned radix sort adapted from Peter M. McIlroy, Keith Bostic and M. Douglas
   * McIlroy, &ldquo;Engineering radix sort&rdquo;, <i>Computing Systems</i>, 6(1), pages 5&minus;27 (1993).
   * <p>
   * <p>This method implements a <em>lexicographical</em> sorting of the provided arrays. Tuples of elements
   * in the same position will be considered a single key, and permuted
   * accordingly.
   *
   * @param a an array containing arrays of equal length to be sorted lexicographically in parallel.
   */
  public static void radixSort(final int[][] a) {
    radixSort(a, 0, a[0].length);
  }

  /**
   * Sorts the specified array of arrays lexicographically using radix sort.
   * <p>
   * <p>The sorting algorithm is a tuned radix sort adapted from Peter M. McIlroy, Keith Bostic and M. Douglas
   * McIlroy, &ldquo;Engineering radix sort&rdquo;, <i>Computing Systems</i>, 6(1), pages 5&minus;27 (1993).
   * <p>
   * <p>This method implements a <em>lexicographical</em> sorting of the provided arrays. Tuples of elements
   * in the same position will be considered a single key, and permuted
   * accordingly.
   *
   * @param a    an array containing arrays of equal length to be sorted lexicographically in parallel.
   * @param from the index of the first element (inclusive) to be sorted.
   * @param to   the index of the last element (exclusive) to be sorted.
   */
  public static void radixSort(final int[][] a, final int from, final int to) {
    if (to - from < RADIXSORT_NO_REC) {
      selectionSort(a, from, to, 0);
      return;
    }

    final int layers = a.length;
    final int maxLevel = DIGITS_PER_ELEMENT * layers - 1;
    for (int p = layers, l = a[0].length; p-- != 0; ) {
      if (a[p].length != l) {
        throw new IllegalArgumentException("The array of index " + p + " has not the same length of the array of index 0.");
      }
    }

    final int stackSize = ((1 << DIGIT_BITS) - 1) * (layers * DIGITS_PER_ELEMENT - 1) + 1;
    int stackPos = 0;
    final int[] offsetStack = new int[stackSize];
    final int[] lengthStack = new int[stackSize];
    final int[] levelStack = new int[stackSize];

    offsetStack[stackPos] = from;
    lengthStack[stackPos] = to - from;
    levelStack[stackPos++] = 0;

    final int[] count = new int[1 << DIGIT_BITS];
    final int[] pos = new int[1 << DIGIT_BITS];
    final int[] t = new int[layers];

    while (stackPos > 0) {
      final int first = offsetStack[--stackPos];
      final int length = lengthStack[stackPos];
      final int level = levelStack[stackPos];


      final int signMask = level % DIGITS_PER_ELEMENT == 0 ? 1 << DIGIT_BITS - 1 : 0;


      final int[] k = a[level / DIGITS_PER_ELEMENT]; // This is the key array
      final int shift = (DIGITS_PER_ELEMENT - 1 - level % DIGITS_PER_ELEMENT) * DIGIT_BITS; // This is the shift that extract the right byte from a key

      // Count keys.
      for (int i = first + length; i-- != first; ) {
        count[((k[i]) >>> shift & DIGIT_MASK ^ signMask)]++;
      }

      // Compute cumulative distribution
      int lastUsed = -1;
      for (int i = 0, p = first; i < 1 << DIGIT_BITS; i++) {
        if (count[i] != 0) {
          lastUsed = i;
        }
        pos[i] = (p += count[i]);
      }

      final int end = first + length - count[lastUsed];
      // i moves through the start of each block
      for (int i = first, c = -1, d; i <= end; i += count[c], count[c] = 0) {
        for (int p = layers; p-- != 0; ) {
          t[p] = a[p][i];
        }
        c = ((k[i]) >>> shift & DIGIT_MASK ^ signMask);

        if (i < end) { // When all slots are OK, the last slot is necessarily OK.
          while ((d = --pos[c]) > i) {
            c = ((k[d]) >>> shift & DIGIT_MASK ^ signMask);
            for (int p = layers; p-- != 0; ) {
              final int u = t[p];
              t[p] = a[p][d];
              a[p][d] = u;
            }
          }
          for (int p = layers; p-- != 0; ) {
            a[p][i] = t[p];
          }
        }

        if (level < maxLevel && count[c] > 1) {
          if (count[c] < RADIXSORT_NO_REC) {
            selectionSort(a, i, i + count[c], level + 1);
          } else {
            offsetStack[stackPos] = i;
            lengthStack[stackPos] = count[c];
            levelStack[stackPos++] = level + 1;
          }
        }
      }
    }
  }


  /**
   * Shuffles the specified array fragment using the specified pseudorandom number generator.
   *
   * @param a      the array to be shuffled.
   * @param from   the index of the first element (inclusive) to be shuffled.
   * @param to     the index of the last element (exclusive) to be shuffled.
   * @param random a pseudorandom number generator (please use a <a href="http://dsiutils.dsi.unimi.it/docs/it/unimi/dsi/util/XorShiftStarRandom.html">XorShift*</a> generator).
   * @return <code>a</code>.
   */
  public static int[] shuffle(final int[] a, final int from, final int to, final Random random) {
    for (int i = to - from; i-- != 0; ) {
      final int p = random.nextInt(i + 1);
      final int t = a[from + i];
      a[from + i] = a[from + p];
      a[from + p] = t;
    }
    return a;
  }

  /**
   * Shuffles the specified array using the specified pseudorandom number generator.
   *
   * @param a      the array to be shuffled.
   * @param random a pseudorandom number generator (please use a <a href="http://dsiutils.dsi.unimi.it/docs/it/unimi/dsi/util/XorShiftStarRandom.html">XorShift*</a> generator).
   * @return <code>a</code>.
   */
  public static int[] shuffle(final int[] a, final Random random) {
    for (int i = a.length; i-- != 0; ) {
      final int p = random.nextInt(i + 1);
      final int t = a[i];
      a[i] = a[p];
      a[p] = t;
    }
    return a;
  }

  /**
   * Reverses the order of the elements in the specified array.
   *
   * @param a the array to be reversed.
   * @return <code>a</code>.
   */
  public static int[] reverse(final int[] a) {
    final int length = a.length;
    for (int i = length / 2; i-- != 0; ) {
      final int t = a[length - i - 1];
      a[length - i - 1] = a[i];
      a[i] = t;
    }
    return a;
  }

  /**
   * Reverses the order of the elements in the specified array fragment.
   *
   * @param a    the array to be reversed.
   * @param from the index of the first element (inclusive) to be reversed.
   * @param to   the index of the last element (exclusive) to be reversed.
   * @return <code>a</code>.
   */
  public static int[] reverse(final int[] a, final int from, final int to) {
    final int length = to - from;
    for (int i = length / 2; i-- != 0; ) {
      final int t = a[from + length - i - 1];
      a[from + length - i - 1] = a[from + i];
      a[from + i] = t;
    }
    return a;
  }

  /**
   * A type-specific content-based hash strategy for arrays.
   */

  private static final class ArrayHashStrategy implements Hash.Strategy<int[]>, java.io.Serializable {
    private static final long serialVersionUID = -7046029254386353129L;

    public int hashCode(final int[] o) {
      return java.util.Arrays.hashCode(o);
    }

    public boolean equals(final int[] a, final int[] b) {
      return java.util.Arrays.equals(a, b);
    }
  }

  /**
   * A type-specific content-based hash strategy for arrays.
   * <p>
   * <P>This hash strategy may be used in custom hash collections whenever keys are
   * arrays, and they must be considered equal by content. This strategy
   * will handle <code>null</code> correctly, and it is serializable.
   */


  public final static Hash.Strategy<int[]> HASH_STRATEGY = new ArrayHashStrategy();


}

