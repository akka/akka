/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil.objects;

import akka.remote.artery.fastutil.Arrays;
import akka.remote.artery.fastutil.Hash;

import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;


import akka.remote.artery.fastutil.ints.IntArrays;

import java.util.Comparator;

/**
 * A class providing static methods and objects that do useful things with type-specific arrays.
 * <p>
 * In particular, the <code>ensureCapacity()</code>, <code>grow()</code>,
 * <code>trim()</code> and <code>setLength()</code> methods allow to handle
 * arrays much like array lists. This can be very useful when efficiency (or
 * syntactic simplicity) reasons make array lists unsuitable.
 * <p>
 * <P><strong>Warning:</strong> if your array is not of type {@code Object[]},
 * {@link #ensureCapacity(Object[], int, int)} and {@link #grow(Object[], int, int)}
 * will use {@linkplain java.lang.reflect.Array#newInstance(Class, int) reflection}
 * to preserve your array type. Reflection is <em>significantly slower</em> than using <code>new</code>.
 * This phenomenon is particularly
 * evident in the first growth phases of an array reallocated with doubling (or similar) logic.
 * <p>
 * <h2>Sorting</h2>
 * <p>
 * <p>There are several sorting methods available. The main theme is that of letting you choose
 * the sorting algorithm you prefer (i.e., trading stability of mergesort for no memory allocation in quicksort).
 * Several algorithms provide a parallel version, that will use the {@linkplain Runtime#availableProcessors() number of cores available}.
 * <p>
 * <p>All comparison-based algorithm have an implementation based on a type-specific comparator.
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

public class ObjectArrays {


  private ObjectArrays() {
  }

  /**
   * A static, final, empty array.
   */
  public final static Object[] EMPTY_ARRAY = {};


  /**
   * Creates a new array using a the given one as prototype.
   * <p>
   * <P>This method returns a new array of the given length whose element
   * are of the same class as of those of <code>prototype</code>. In case
   * of an empty array, it tries to return {@link #EMPTY_ARRAY}, if possible.
   *
   * @param prototype an array that will be used to type the new one.
   * @param length    the length of the new array.
   * @return a new array of given type and length.
   */

  @SuppressWarnings("unchecked")
  private static <K> K[] newArray(final K[] prototype, final int length) {
    final Class<?> klass = prototype.getClass();
    if (klass == Object[].class) {
      return (K[]) (length == 0 ? EMPTY_ARRAY : new Object[length]);
    }
    return (K[]) java.lang.reflect.Array.newInstance(klass.getComponentType(), length);
  }


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
  public static <K> K[] ensureCapacity(final K[] array, final int length) {
    if (length > array.length) {
      final K t[] =

        newArray(array, length);


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
  public static <K> K[] ensureCapacity(final K[] array, final int length, final int preserve) {
    if (length > array.length) {
      final K t[] =

        newArray(array, length);


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

  public static <K> K[] grow(final K[] array, final int length) {
    if (length > array.length) {
      final int newLength = (int) Math.max(Math.min(2L * array.length, Arrays.MAX_ARRAY_SIZE), length);
      final K t[] =

        newArray(array, newLength);


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

  public static <K> K[] grow(final K[] array, final int length, final int preserve) {

    if (length > array.length) {
      final int newLength = (int) Math.max(Math.min(2L * array.length, Arrays.MAX_ARRAY_SIZE), length);

      final K t[] =

        newArray(array, newLength);


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

  public static <K> K[] trim(final K[] array, final int length) {
    if (length >= array.length) {
      return array;
    }
    final K t[] =

      newArray(array, length);


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

  public static <K> K[] setLength(final K[] array, final int length) {
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

  public static <K> K[] copy(final K[] array, final int offset, final int length) {
    ensureOffsetLength(array, offset, length);
    final K[] a =

      newArray(array, length);


    System.arraycopy(array, offset, a, 0, length);
    return a;
  }

  /**
   * Returns a copy of an array.
   *
   * @param array an array.
   * @return a copy of <code>array</code>.
   */

  public static <K> K[] copy(final K[] array) {
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
  public static <K> void fill(final K[] array, final K value) {
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
  public static <K> void fill(final K[] array, final int from, int to, final K value) {
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
  public static <K> boolean equals(final K[] a1, final K a2[]) {
    int i = a1.length;
    if (i != a2.length) {
      return false;
    }
    while (i-- != 0) {
      if (!((a1[i]) == null ? (a2[i]) == null : (a1[i]).equals(a2[i]))) {
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
  public static <K> void ensureFromTo(final K[] a, final int from, final int to) {
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
  public static <K> void ensureOffsetLength(final K[] a, final int offset, final int length) {
    Arrays.ensureOffsetLength(a.length, offset, length);
  }

  /**
   * Ensures that two arrays are of the same length.
   *
   * @param a an array.
   * @param b another array.
   * @throws IllegalArgumentException if the two argument arrays are not of the same length.
   */
  public static <K> void ensureSameLength(final K[] a, final K[] b) {
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
  public static <K> void swap(final K x[], final int a, final int b) {
    final K t = x[a];
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
  public static <K> void swap(final K[] x, int a, int b, final int n) {
    for (int i = 0; i < n; i++, a++, b++) {
      swap(x, a, b);
    }
  }

  private static <K> int med3(final K x[], final int a, final int b, final int c, Comparator<K> comp) {
    final int ab = comp.compare(x[a], x[b]);
    final int ac = comp.compare(x[a], x[c]);
    final int bc = comp.compare(x[b], x[c]);
    return (ab < 0 ?
      (bc < 0 ? b : ac < 0 ? c : a) :
      (bc > 0 ? b : ac > 0 ? c : a));
  }

  private static <K> void selectionSort(final K[] a, final int from, final int to, final Comparator<K> comp) {
    for (int i = from; i < to - 1; i++) {
      int m = i;
      for (int j = i + 1; j < to; j++) {
        if (comp.compare(a[j], a[m]) < 0) {
          m = j;
        }
      }
      if (m != i) {
        final K u = a[i];
        a[i] = a[m];
        a[m] = u;
      }
    }
  }

  private static <K> void insertionSort(final K[] a, final int from, final int to, final Comparator<K> comp) {
    for (int i = from; ++i < to; ) {
      K t = a[i];
      int j = i;
      for (K u = a[j - 1]; comp.compare(t, u) < 0; u = a[--j - 1]) {
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
  public static <K> void quickSort(final K[] x, final int from, final int to, final Comparator<K> comp) {
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

    final K v = x[m];

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
  public static <K> void quickSort(final K[] x, final Comparator<K> comp) {
    quickSort(x, 0, x.length, comp);
  }


  protected static class ForkJoinQuickSortComp<K> extends RecursiveAction {
    private static final long serialVersionUID = 1L;
    private final int from;
    private final int to;
    private final K[] x;
    private final Comparator<K> comp;

    public ForkJoinQuickSortComp(final K[] x, final int from, final int to, final Comparator<K> comp) {
      this.from = from;
      this.to = to;
      this.x = x;
      this.comp = comp;
    }

    @Override
    protected void compute() {
      final K[] x = this.x;
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
      final K v = x[m];
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
        invokeAll(new ForkJoinQuickSortComp<K>(x, from, from + s, comp), new ForkJoinQuickSortComp<K>(x, to - t, to, comp));
      } else if (s > 1) {
        invokeAll(new ForkJoinQuickSortComp<K>(x, from, from + s, comp));
      } else {
        invokeAll(new ForkJoinQuickSortComp<K>(x, to - t, to, comp));
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
  public static <K> void parallelQuickSort(final K[] x, final int from, final int to, final Comparator<K> comp) {
    if (to - from < PARALLEL_QUICKSORT_NO_FORK) {
      quickSort(x, from, to, comp);
    } else {
      final ForkJoinPool pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
      pool.invoke(new ForkJoinQuickSortComp<K>(x, from, to, comp));
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
  public static <K> void parallelQuickSort(final K[] x, final Comparator<K> comp) {
    parallelQuickSort(x, 0, x.length, comp);
  }


  @SuppressWarnings("unchecked")
  private static <K> int med3(final K x[], final int a, final int b, final int c) {
    final int ab = (((Comparable<K>) (x[a])).compareTo(x[b]));
    final int ac = (((Comparable<K>) (x[a])).compareTo(x[c]));
    final int bc = (((Comparable<K>) (x[b])).compareTo(x[c]));
    return (ab < 0 ?
      (bc < 0 ? b : ac < 0 ? c : a) :
      (bc > 0 ? b : ac > 0 ? c : a));
  }

  @SuppressWarnings("unchecked")
  private static <K> void selectionSort(final K[] a, final int from, final int to) {
    for (int i = from; i < to - 1; i++) {
      int m = i;
      for (int j = i + 1; j < to; j++) {
        if ((((Comparable<K>) (a[j])).compareTo(a[m]) < 0)) {
          m = j;
        }
      }
      if (m != i) {
        final K u = a[i];
        a[i] = a[m];
        a[m] = u;
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static <K> void insertionSort(final K[] a, final int from, final int to) {
    for (int i = from; ++i < to; ) {
      K t = a[i];
      int j = i;
      for (K u = a[j - 1]; (((Comparable<K>) (t)).compareTo(u) < 0); u = a[--j - 1]) {
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

  @SuppressWarnings("unchecked")
  public static <K> void quickSort(final K[] x, final int from, final int to) {
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

    final K v = x[m];

    // Establish Invariant: v* (<v)* (>v)* v*
    int a = from, b = a, c = to - 1, d = c;
    while (true) {
      int comparison;
      while (b <= c && (comparison = (((Comparable<K>) (x[b])).compareTo(v))) <= 0) {
        if (comparison == 0) {
          swap(x, a++, b);
        }
        b++;
      }
      while (c >= b && (comparison = (((Comparable<K>) (x[c])).compareTo(v))) >= 0) {
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
  public static <K> void quickSort(final K[] x) {
    quickSort(x, 0, x.length);
  }

  protected static class ForkJoinQuickSort<K> extends RecursiveAction {
    private static final long serialVersionUID = 1L;
    private final int from;
    private final int to;
    private final K[] x;

    public ForkJoinQuickSort(final K[] x, final int from, final int to) {
      this.from = from;
      this.to = to;
      this.x = x;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void compute() {
      final K[] x = this.x;
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
      final K v = x[m];
      // Establish Invariant: v* (<v)* (>v)* v*
      int a = from, b = a, c = to - 1, d = c;
      while (true) {
        int comparison;
        while (b <= c && (comparison = (((Comparable<K>) (x[b])).compareTo(v))) <= 0) {
          if (comparison == 0) {
            swap(x, a++, b);
          }
          b++;
        }
        while (c >= b && (comparison = (((Comparable<K>) (x[c])).compareTo(v))) >= 0) {
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
        invokeAll(new ForkJoinQuickSort<K>(x, from, from + s), new ForkJoinQuickSort<K>(x, to - t, to));
      } else if (s > 1) {
        invokeAll(new ForkJoinQuickSort<K>(x, from, from + s));
      } else {
        invokeAll(new ForkJoinQuickSort<K>(x, to - t, to));
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
  public static <K> void parallelQuickSort(final K[] x, final int from, final int to) {
    if (to - from < PARALLEL_QUICKSORT_NO_FORK) {
      quickSort(x, from, to);
    } else {
      final ForkJoinPool pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
      pool.invoke(new ForkJoinQuickSort<K>(x, from, to));
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
  public static <K> void parallelQuickSort(final K[] x) {
    parallelQuickSort(x, 0, x.length);
  }


  @SuppressWarnings("unchecked")
  private static <K> int med3Indirect(final int perm[], final K x[], final int a, final int b, final int c) {
    final K aa = x[perm[a]];
    final K bb = x[perm[b]];
    final K cc = x[perm[c]];
    final int ab = (((Comparable<K>) (aa)).compareTo(bb));
    final int ac = (((Comparable<K>) (aa)).compareTo(cc));
    final int bc = (((Comparable<K>) (bb)).compareTo(cc));
    return (ab < 0 ?
      (bc < 0 ? b : ac < 0 ? c : a) :
      (bc > 0 ? b : ac > 0 ? c : a));
  }

  @SuppressWarnings("unchecked")
  private static <K> void insertionSortIndirect(final int[] perm, final K[] a, final int from, final int to) {
    for (int i = from; ++i < to; ) {
      int t = perm[i];
      int j = i;
      for (int u = perm[j - 1]; (((Comparable<K>) (a[t])).compareTo(a[u]) < 0); u = perm[--j - 1]) {
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

  @SuppressWarnings("unchecked")
  public static <K> void quickSortIndirect(final int[] perm, final K[] x, final int from, final int to) {
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

    final K v = x[perm[m]];

    // Establish Invariant: v* (<v)* (>v)* v*
    int a = from, b = a, c = to - 1, d = c;
    while (true) {
      int comparison;
      while (b <= c && (comparison = (((Comparable<K>) (x[perm[b]])).compareTo(v))) <= 0) {
        if (comparison == 0) {
          IntArrays.swap(perm, a++, b);
        }
        b++;
      }
      while (c >= b && (comparison = (((Comparable<K>) (x[perm[c]])).compareTo(v))) >= 0) {
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
  public static <K> void quickSortIndirect(final int perm[], final K[] x) {
    quickSortIndirect(perm, x, 0, x.length);
  }

  protected static class ForkJoinQuickSortIndirect<K> extends RecursiveAction {
    private static final long serialVersionUID = 1L;
    private final int from;
    private final int to;
    private final int[] perm;
    private final K[] x;

    public ForkJoinQuickSortIndirect(final int perm[], final K[] x, final int from, final int to) {
      this.from = from;
      this.to = to;
      this.x = x;
      this.perm = perm;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void compute() {
      final K[] x = this.x;
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
      final K v = x[perm[m]];
      // Establish Invariant: v* (<v)* (>v)* v*
      int a = from, b = a, c = to - 1, d = c;
      while (true) {
        int comparison;
        while (b <= c && (comparison = (((Comparable<K>) (x[perm[b]])).compareTo(v))) <= 0) {
          if (comparison == 0) {
            IntArrays.swap(perm, a++, b);
          }
          b++;
        }
        while (c >= b && (comparison = (((Comparable<K>) (x[perm[c]])).compareTo(v))) >= 0) {
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
        invokeAll(new ForkJoinQuickSortIndirect<K>(perm, x, from, from + s), new ForkJoinQuickSortIndirect<K>(perm, x, to - t, to));
      } else if (s > 1) {
        invokeAll(new ForkJoinQuickSortIndirect<K>(perm, x, from, from + s));
      } else {
        invokeAll(new ForkJoinQuickSortIndirect<K>(perm, x, to - t, to));
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
  public static <K> void parallelQuickSortIndirect(final int[] perm, final K[] x, final int from, final int to) {
    if (to - from < PARALLEL_QUICKSORT_NO_FORK) {
      quickSortIndirect(perm, x, from, to);
    } else {
      final ForkJoinPool pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
      pool.invoke(new ForkJoinQuickSortIndirect<K>(perm, x, from, to));
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
  public static <K> void parallelQuickSortIndirect(final int perm[], final K[] x) {
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
  public static <K> void stabilize(final int perm[], final K[] x, final int from, final int to) {
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
  public static <K> void stabilize(final int perm[], final K[] x) {
    stabilize(perm, x, 0, perm.length);
  }

  @SuppressWarnings("unchecked")
  private static <K> int med3(final K x[], final K[] y, final int a, final int b, final int c) {
    int t;
    final int ab = (t = (((Comparable<K>) (x[a])).compareTo(x[b]))) == 0 ? (((Comparable<K>) (y[a])).compareTo(y[b])) : t;
    final int ac = (t = (((Comparable<K>) (x[a])).compareTo(x[c]))) == 0 ? (((Comparable<K>) (y[a])).compareTo(y[c])) : t;
    final int bc = (t = (((Comparable<K>) (x[b])).compareTo(x[c]))) == 0 ? (((Comparable<K>) (y[b])).compareTo(y[c])) : t;
    return (ab < 0 ?
      (bc < 0 ? b : ac < 0 ? c : a) :
      (bc > 0 ? b : ac > 0 ? c : a));
  }

  private static <K> void swap(final K x[], final K[] y, final int a, final int b) {
    final K t = x[a];
    final K u = y[a];
    x[a] = x[b];
    y[a] = y[b];
    x[b] = t;
    y[b] = u;
  }

  private static <K> void swap(final K[] x, final K[] y, int a, int b, final int n) {
    for (int i = 0; i < n; i++, a++, b++) {
      swap(x, y, a, b);
    }
  }

  @SuppressWarnings("unchecked")
  private static <K> void selectionSort(final K[] a, final K[] b, final int from, final int to) {
    for (int i = from; i < to - 1; i++) {
      int m = i, u;
      for (int j = i + 1; j < to; j++) {
        if ((u = (((Comparable<K>) (a[j])).compareTo(a[m]))) < 0 || u == 0 && (((Comparable<K>) (b[j])).compareTo(b[m]) < 0)) {
          m = j;
        }
      }

      if (m != i) {
        K t = a[i];
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

  @SuppressWarnings("unchecked")
  public static <K> void quickSort(final K[] x, final K[] y, final int from, final int to) {
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
    final K v = x[m], w = y[m];
    // Establish Invariant: v* (<v)* (>v)* v*
    int a = from, b = a, c = to - 1, d = c;
    while (true) {
      int comparison, t;
      while (b <= c && (comparison = (t = (((Comparable<K>) (x[b])).compareTo(v))) == 0 ? (((Comparable<K>) (y[b])).compareTo(w)) : t) <= 0) {
        if (comparison == 0) {
          swap(x, y, a++, b);
        }
        b++;
      }
      while (c >= b && (comparison = (t = (((Comparable<K>) (x[c])).compareTo(v))) == 0 ? (((Comparable<K>) (y[c])).compareTo(w)) : t) >= 0) {
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
  public static <K> void quickSort(final K[] x, final K[] y) {
    ensureSameLength(x, y);
    quickSort(x, y, 0, x.length);
  }

  protected static class ForkJoinQuickSort2<K> extends RecursiveAction {
    private static final long serialVersionUID = 1L;
    private final int from;
    private final int to;
    private final K[] x, y;

    public ForkJoinQuickSort2(final K[] x, final K[] y, final int from, final int to) {
      this.from = from;
      this.to = to;
      this.x = x;
      this.y = y;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void compute() {
      final K[] x = this.x;
      final K[] y = this.y;
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
      final K v = x[m], w = y[m];
      // Establish Invariant: v* (<v)* (>v)* v*
      int a = from, b = a, c = to - 1, d = c;
      while (true) {
        int comparison, t;
        while (b <= c && (comparison = (t = (((Comparable<K>) (x[b])).compareTo(v))) == 0 ? (((Comparable<K>) (y[b])).compareTo(w)) : t) <= 0) {
          if (comparison == 0) {
            swap(x, y, a++, b);
          }
          b++;
        }
        while (c >= b && (comparison = (t = (((Comparable<K>) (x[c])).compareTo(v))) == 0 ? (((Comparable<K>) (y[c])).compareTo(w)) : t) >= 0) {
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
        invokeAll(new ForkJoinQuickSort2<K>(x, y, from, from + s), new ForkJoinQuickSort2<K>(x, y, to - t, to));
      } else if (s > 1) {
        invokeAll(new ForkJoinQuickSort2<K>(x, y, from, from + s));
      } else {
        invokeAll(new ForkJoinQuickSort2<K>(x, y, to - t, to));
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
  public static <K> void parallelQuickSort(final K[] x, final K[] y, final int from, final int to) {
    if (to - from < PARALLEL_QUICKSORT_NO_FORK) {
      quickSort(x, y, from, to);
    }
    final ForkJoinPool pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
    pool.invoke(new ForkJoinQuickSort2<K>(x, y, from, to));
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
  public static <K> void parallelQuickSort(final K[] x, final K[] y) {
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

  @SuppressWarnings("unchecked")
  public static <K> void mergeSort(final K a[], final int from, final int to, final K supp[]) {
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
    if ((((Comparable<K>) (supp[mid - 1])).compareTo(supp[mid]) <= 0)) {
      System.arraycopy(supp, from, a, from, len);
      return;
    }

    // Merge sorted halves (now in supp) into a
    for (int i = from, p = from, q = mid; i < to; i++) {
      if (q >= to || p < mid && (((Comparable<K>) (supp[p])).compareTo(supp[q]) <= 0)) {
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
  public static <K> void mergeSort(final K a[], final int from, final int to) {
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
  public static <K> void mergeSort(final K a[]) {
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
  public static <K> void mergeSort(final K a[], final int from, final int to, Comparator<K> comp, final K supp[]) {
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
  public static <K> void mergeSort(final K a[], final int from, final int to, Comparator<K> comp) {
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
  public static <K> void mergeSort(final K a[], Comparator<K> comp) {
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
  @SuppressWarnings("unchecked")
  public static <K> int binarySearch(final K[] a, int from, int to, final K key) {
    K midVal;
    to--;
    while (from <= to) {
      final int mid = (from + to) >>> 1;
      midVal = a[mid];


      final int cmp = ((Comparable<? super K>) midVal).compareTo(key);
      if (cmp < 0) {
        from = mid + 1;
      } else if (cmp > 0) {
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
  public static <K> int binarySearch(final K[] a, final K key) {
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
  public static <K> int binarySearch(final K[] a, int from, int to, final K key, final Comparator<K> c) {
    K midVal;
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
  public static <K> int binarySearch(final K[] a, final K key, final Comparator<K> c) {
    return binarySearch(a, 0, a.length, key, c);
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
  public static <K> K[] shuffle(final K[] a, final int from, final int to, final Random random) {
    for (int i = to - from; i-- != 0; ) {
      final int p = random.nextInt(i + 1);
      final K t = a[from + i];
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
  public static <K> K[] shuffle(final K[] a, final Random random) {
    for (int i = a.length; i-- != 0; ) {
      final int p = random.nextInt(i + 1);
      final K t = a[i];
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
  public static <K> K[] reverse(final K[] a) {
    final int length = a.length;
    for (int i = length / 2; i-- != 0; ) {
      final K t = a[length - i - 1];
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
  public static <K> K[] reverse(final K[] a, final int from, final int to) {
    final int length = to - from;
    for (int i = length / 2; i-- != 0; ) {
      final K t = a[from + length - i - 1];
      a[from + length - i - 1] = a[from + i];
      a[from + i] = t;
    }
    return a;
  }

  /**
   * A type-specific content-based hash strategy for arrays.
   */

  private static final class ArrayHashStrategy<K> implements Hash.Strategy<K[]>, java.io.Serializable {
    private static final long serialVersionUID = -7046029254386353129L;

    public int hashCode(final K[] o) {
      return java.util.Arrays.hashCode(o);
    }

    public boolean equals(final K[] a, final K[] b) {
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


  @SuppressWarnings({"rawtypes"})
  public final static Hash.Strategy HASH_STRATEGY = new ArrayHashStrategy();


}

