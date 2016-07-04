/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */


package akka.remote.artery.fastutil.ints;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.RandomAccess;
import java.util.NoSuchElementException;


/**
 * A type-specific array-based list; provides some additional methods that use polymorphism to avoid (un)boxing.
 * <p>
 * <P>This class implements a lightweight, fast, open, optimized,
 * reuse-oriented version of array-based lists. Instances of this class
 * represent a list with an array that is enlarged as needed when new entries
 * are created (by doubling its current length), but is
 * <em>never</em> made smaller (even on a {@link #clear()}). A family of
 * {@linkplain #trim() trimming methods} lets you control the size of the
 * backing array; this is particularly useful if you reuse instances of this class.
 * Range checks are equivalent to those of {@link java.util}'s classes, but
 * they are delayed as much as possible. The backing array is exposed by the
 * {@link #elements()} method.
 * <p>
 * <p>This class implements the bulk methods <code>removeElements()</code>,
 * <code>addElements()</code> and <code>getElements()</code> using
 * high-performance system calls (e.g., {@link
 * System#arraycopy(Object, int, Object, int, int) System.arraycopy()} instead of
 * expensive loops.
 *
 * @see java.util.ArrayList
 */

public class IntArrayList extends AbstractIntList implements RandomAccess, Cloneable, java.io.Serializable {
  private static final long serialVersionUID = -7046029254386353130L;
  /**
   * The initial default capacity of an array list.
   */
  public final static int DEFAULT_INITIAL_CAPACITY = 16;


  /**
   * The backing array.
   */
  protected transient int a[];

  /**
   * The current actual size of the list (never greater than the backing-array length).
   */
  protected int size;

  private static final boolean ASSERTS = false;

  /**
   * Creates a new array list using a given array.
   * <p>
   * <P>This constructor is only meant to be used by the wrapping methods.
   *
   * @param a the array that will be used to back this array list.
   */

  @SuppressWarnings("unused")
  protected IntArrayList(final int a[], boolean dummy) {
    this.a = a;


  }

  /**
   * Creates a new array list with given capacity.
   *
   * @param capacity the initial capacity of the array list (may be 0).
   */


  public IntArrayList(final int capacity) {
    if (capacity < 0) {
      throw new IllegalArgumentException("Initial capacity (" + capacity + ") is negative");
    }

    a = new int[capacity];


  }

  /**
   * Creates a new array list with {@link #DEFAULT_INITIAL_CAPACITY} capacity.
   */

  public IntArrayList() {
    this(DEFAULT_INITIAL_CAPACITY);
  }

  /**
   * Creates a new array list and fills it with a given collection.
   *
   * @param c a collection that will be used to fill the array list.
   */

  public IntArrayList(final Collection<? extends Integer> c) {
    this(c.size());

    size = IntIterators.unwrap(IntIterators.asIntIterator(c.iterator()), a);


  }

  /**
   * Creates a new array list and fills it with a given type-specific collection.
   *
   * @param c a type-specific collection that will be used to fill the array list.
   */

  public IntArrayList(final IntCollection c) {
    this(c.size());
    size = IntIterators.unwrap(c.iterator(), a);
  }

  /**
   * Creates a new array list and fills it with a given type-specific list.
   *
   * @param l a type-specific list that will be used to fill the array list.
   */

  public IntArrayList(final IntList l) {
    this(l.size());
    l.getElements(0, a, 0, size = l.size());
  }

  /**
   * Creates a new array list and fills it with the elements of a given array.
   *
   * @param a an array whose elements will be used to fill the array list.
   */

  public IntArrayList(final int a[]) {
    this(a, 0, a.length);
  }

  /**
   * Creates a new array list and fills it with the elements of a given array.
   *
   * @param a      an array whose elements will be used to fill the array list.
   * @param offset the first element to use.
   * @param length the number of elements to use.
   */

  public IntArrayList(final int a[], final int offset, final int length) {
    this(length);
    System.arraycopy(a, offset, this.a, 0, length);
    size = length;
  }

  /**
   * Creates a new array list and fills it with the elements returned by an iterator..
   *
   * @param i an iterator whose returned elements will fill the array list.
   */

  public IntArrayList(final Iterator<? extends Integer> i) {
    this();
    while (i.hasNext()) {
      this.add(i.next());
    }
  }

  /**
   * Creates a new array list and fills it with the elements returned by a type-specific iterator..
   *
   * @param i a type-specific iterator whose returned elements will fill the array list.
   */

  public IntArrayList(final IntIterator i) {
    this();
    while (i.hasNext()) {
      this.add(i.nextInt());
    }
  }


  /**
   * Returns the backing array of this list.
   *
   * @return the backing array.
   */

  public int[] elements() {
    return a;
  }

  /**
   * Wraps a given array into an array list of given size.
   * <p>
   * <P>Note it is guaranteed
   * that the type of the array returned by {@link #elements()} will be the same
   * (see the comments in the class documentation).
   *
   * @param a      an array to wrap.
   * @param length the length of the resulting array list.
   * @return a new array list of the given size, wrapping the given array.
   */

  public static IntArrayList wrap(final int a[], final int length) {
    if (length > a.length) {
      throw new IllegalArgumentException("The specified length (" + length + ") is greater than the array size (" + a.length + ")");
    }
    final IntArrayList l = new IntArrayList(a, false);
    l.size = length;
    return l;
  }

  /**
   * Wraps a given array into an array list.
   * <p>
   * <P>Note it is guaranteed
   * that the type of the array returned by {@link #elements()} will be the same
   * (see the comments in the class documentation).
   *
   * @param a an array to wrap.
   * @return a new array list wrapping the given array.
   */

  public static IntArrayList wrap(final int a[]) {
    return wrap(a, a.length);
  }


  /**
   * Ensures that this array list can contain the given number of entries without resizing.
   *
   * @param capacity the new minimum capacity for this array list.
   */

  public void ensureCapacity(final int capacity) {

    a = IntArrays.ensureCapacity(a, capacity, size);
    if (ASSERTS) {
      assert size <= a.length;
    }
  }

  /**
   * Grows this array list, ensuring that it can contain the given number of entries without resizing,
   * and in case enlarging it at least by a factor of two.
   *
   * @param capacity the new minimum capacity for this array list.
   */

  private void grow(final int capacity) {

    a = IntArrays.grow(a, capacity, size);
    if (ASSERTS) {
      assert size <= a.length;
    }
  }

  public void add(final int index, final int k) {
    ensureIndex(index);
    grow(size + 1);
    if (index != size) {
      System.arraycopy(a, index, a, index + 1, size - index);
    }
    a[index] = k;
    size++;
    if (ASSERTS) {
      assert size <= a.length;
    }
  }

  public boolean add(final int k) {
    grow(size + 1);
    a[size++] = k;
    if (ASSERTS) {
      assert size <= a.length;
    }
    return true;
  }

  public int getInt(final int index) {
    if (index >= size) {
      throw new IndexOutOfBoundsException("Index (" + index + ") is greater than or equal to list size (" + size + ")");
    }
    return a[index];
  }

  public int indexOf(final int k) {
    for (int i = 0; i < size; i++) {
      if (((k) == (a[i]))) {
        return i;
      }
    }
    return -1;
  }


  public int lastIndexOf(final int k) {
    for (int i = size; i-- != 0; ) {
      if (((k) == (a[i]))) {
        return i;
      }
    }
    return -1;
  }

  public int removeInt(final int index) {
    if (index >= size) {
      throw new IndexOutOfBoundsException("Index (" + index + ") is greater than or equal to list size (" + size + ")");
    }
    final int old = a[index];
    size--;
    if (index != size) {
      System.arraycopy(a, index + 1, a, index, size - index);
    }


    if (ASSERTS) {
      assert size <= a.length;
    }
    return old;
  }

  public boolean rem(final int k) {
    int index = indexOf(k);
    if (index == -1) {
      return false;
    }
    removeInt(index);
    if (ASSERTS) {
      assert size <= a.length;
    }
    return true;
  }


  public int set(final int index, final int k) {
    if (index >= size) {
      throw new IndexOutOfBoundsException("Index (" + index + ") is greater than or equal to list size (" + size + ")");
    }
    int old = a[index];
    a[index] = k;
    return old;
  }

  public void clear() {


    size = 0;
    if (ASSERTS) {
      assert size <= a.length;
    }
  }

  public int size() {
    return size;
  }

  public void size(final int size) {
    if (size > a.length) {
      ensureCapacity(size);
    }
    if (size > this.size) {
      Arrays.fill(a, this.size, size, (0));
    }


    this.size = size;
  }

  public boolean isEmpty() {
    return size == 0;
  }

  /**
   * Trims this array list so that the capacity is equal to the size.
   *
   * @see java.util.ArrayList#trimToSize()
   */
  public void trim() {
    trim(0);
  }

  /**
   * Trims the backing array if it is too large.
   * <p>
   * If the current array length is smaller than or equal to
   * <code>n</code>, this method does nothing. Otherwise, it trims the
   * array length to the maximum between <code>n</code> and {@link #size()}.
   * <p>
   * <P>This method is useful when reusing lists.  {@linkplain #clear() Clearing a
   * list} leaves the array length untouched. If you are reusing a list
   * many times, you can call this method with a typical
   * size to avoid keeping around a very large array just
   * because of a few large transient lists.
   *
   * @param n the threshold for the trimming.
   */


  public void trim(final int n) {
    // TODO: use Arrays.trim() and preserve type only if necessary
    if (n >= a.length || size == a.length) {
      return;
    }
    final int t[] = new int[Math.max(n, size)];
    System.arraycopy(a, 0, t, 0, size);
    a = t;
    if (ASSERTS) {
      assert size <= a.length;
    }
  }


  /**
   * Copies element of this type-specific list into the given array using optimized system calls.
   *
   * @param from   the start index (inclusive).
   * @param a      the destination array.
   * @param offset the offset into the destination array where to store the first element copied.
   * @param length the number of elements to be copied.
   */

  public void getElements(final int from, final int[] a, final int offset, final int length) {
    IntArrays.ensureOffsetLength(a, offset, length);
    System.arraycopy(this.a, from, a, offset, length);
  }

  /**
   * Removes elements of this type-specific list using optimized system calls.
   *
   * @param from the start index (inclusive).
   * @param to   the end index (exclusive).
   */
  public void removeElements(final int from, final int to) {
    akka.remote.artery.fastutil.Arrays.ensureFromTo(size, from, to);
    System.arraycopy(a, to, a, from, size - to);
    size -= (to - from);


  }


  /**
   * Adds elements to this type-specific list using optimized system calls.
   *
   * @param index  the index at which to add elements.
   * @param a      the array containing the elements.
   * @param offset the offset of the first element to add.
   * @param length the number of elements to add.
   */
  public void addElements(final int index, final int a[], final int offset, final int length) {
    ensureIndex(index);
    IntArrays.ensureOffsetLength(a, offset, length);
    grow(size + length);
    System.arraycopy(this.a, index, this.a, index + length, size - index);
    System.arraycopy(a, offset, this.a, index, length);
    size += length;
  }


  public int[] toIntArray(int a[]) {
    if (a == null || a.length < size) {
      a = new int[size];
    }
    System.arraycopy(this.a, 0, a, 0, size);
    return a;
  }

  public boolean addAll(int index, final IntCollection c) {
    ensureIndex(index);
    int n = c.size();
    if (n == 0) {
      return false;
    }
    grow(size + n);
    if (index != size) {
      System.arraycopy(a, index, a, index + n, size - index);
    }
    final IntIterator i = c.iterator();
    size += n;
    while (n-- != 0) {
      a[index++] = i.nextInt();
    }
    if (ASSERTS) {
      assert size <= a.length;
    }
    return true;
  }

  public boolean addAll(final int index, final IntList l) {
    ensureIndex(index);
    final int n = l.size();
    if (n == 0) {
      return false;
    }
    grow(size + n);
    if (index != size) {
      System.arraycopy(a, index, a, index + n, size - index);
    }
    l.getElements(0, a, index, n);
    size += n;
    if (ASSERTS) {
      assert size <= a.length;
    }
    return true;
  }

  @Override
  public boolean removeAll(final IntCollection c) {
    final int[] a = this.a;
    int j = 0;
    for (int i = 0; i < size; i++) {
      if (!c.contains(a[i])) {
        a[j++] = a[i];
      }
    }
    final boolean modified = size != j;
    size = j;
    return modified;
  }

  @Override
  public boolean removeAll(final Collection<?> c) {
    final int[] a = this.a;
    int j = 0;
    for (int i = 0; i < size; i++) {
      if (!c.contains((Integer.valueOf(a[i])))) {
        a[j++] = a[i];
      }
    }
    final boolean modified = size != j;
    size = j;
    return modified;
  }

  public IntListIterator listIterator(final int index) {
    ensureIndex(index);

    return new AbstractIntListIterator() {
      int pos = index, last = -1;

      public boolean hasNext() {
        return pos < size;
      }

      public boolean hasPrevious() {
        return pos > 0;
      }

      public int nextInt() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        return a[last = pos++];
      }

      public int previousInt() {
        if (!hasPrevious()) {
          throw new NoSuchElementException();
        }
        return a[last = --pos];
      }

      public int nextIndex() {
        return pos;
      }

      public int previousIndex() {
        return pos - 1;
      }

      public void add(int k) {
        IntArrayList.this.add(pos++, k);
        last = -1;
      }

      public void set(int k) {
        if (last == -1) {
          throw new IllegalStateException();
        }
        IntArrayList.this.set(last, k);
      }

      public void remove() {
        if (last == -1) {
          throw new IllegalStateException();
        }
        IntArrayList.this.removeInt(last);
     /* If the last operation was a next(), we are removing an element *before* us, and we must decrease pos correspondingly. */
        if (last < pos) {
          pos--;
        }
        last = -1;
      }
    };
  }


  public IntArrayList clone() {
    IntArrayList c = new IntArrayList(size);
    System.arraycopy(a, 0, c.a, 0, size);
    c.size = size;
    return c;
  }


  /**
   * Compares this type-specific array list to another one.
   * <p>
   * <P>This method exists only for sake of efficiency. The implementation
   * inherited from the abstract implementation would already work.
   *
   * @param l a type-specific array list.
   * @return true if the argument contains the same elements of this type-specific array list.
   */
  public boolean equals(final IntArrayList l) {
    if (l == this) {
      return true;
    }
    int s = size();
    if (s != l.size()) {
      return false;
    }
    final int[] a1 = a;
    final int[] a2 = l.a;


    while (s-- != 0) {
      if (a1[s] != a2[s]) {
        return false;
      }
    }

    return true;
  }


  /**
   * Compares this array list to another array list.
   * <p>
   * <P>This method exists only for sake of efficiency. The implementation
   * inherited from the abstract implementation would already work.
   *
   * @param l an array list.
   * @return a negative integer,
   * zero, or a positive integer as this list is lexicographically less than, equal
   * to, or greater than the argument.
   */

  public int compareTo(final IntArrayList l) {
    final int s1 = size(), s2 = l.size();
    final int a1[] = a, a2[] = l.a;
    int e1, e2;
    int r, i;

    for (i = 0; i < s1 && i < s2; i++) {
      e1 = a1[i];
      e2 = a2[i];
      if ((r = (Integer.compare((e1), (e2)))) != 0) {
        return r;
      }
    }

    return i < s2 ? -1 : (i < s1 ? 1 : 0);
  }


  private void writeObject(java.io.ObjectOutputStream s) throws java.io.IOException {
    s.defaultWriteObject();
    for (int i = 0; i < size; i++) {
      s.writeInt(a[i]);
    }
  }


  private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
    s.defaultReadObject();
    a = new int[size];
    for (int i = 0; i < size; i++) a[i] = s.readInt();
  }
}

