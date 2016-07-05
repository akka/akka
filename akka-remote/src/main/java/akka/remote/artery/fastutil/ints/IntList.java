/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */


package akka.remote.artery.fastutil.ints;

import java.util.List;


/**
 * A type-specific {@link List}; provides some additional methods that use polymorphism to avoid (un)boxing.
 * <p>
 * <P>Note that this type-specific interface extends {@link Comparable}: it is expected that implementing
 * classes perform a lexicographical comparison using the standard operator "less then" for primitive types,
 * and the usual {@link Comparable#compareTo(Object) compareTo()} method for objects.
 * <p>
 * <P>Additionally, this interface strengthens {@link #listIterator()},
 * {@link #listIterator(int)} and {@link #subList(int, int)}.
 * <p>
 * <P>Besides polymorphic methods, this interfaces specifies methods to copy into an array or remove contiguous
 * sublists. Although the abstract implementation of this interface provides simple, one-by-one implementations
 * of these methods, it is expected that concrete implementation override them with optimized versions.
 *
 * @see List
 */

public interface IntList extends List<Integer>, Comparable<List<? extends Integer>>, IntCollection {
  /**
   * Returns a type-specific iterator on the elements of this list (in proper sequence).
   * <p>
   * Note that this specification strengthens the one given in {@link List#iterator()}.
   * It would not be normally necessary, but {@link java.lang.Iterable#iterator()} is bizarrily re-specified
   * in {@link List}.
   *
   * @return an iterator on the elements of this list (in proper sequence).
   */
  IntListIterator iterator();

  /**
   * Returns a type-specific list iterator on the list.
   *
   * @see #listIterator()
   * @deprecated As of <code>fastutil</code> 5, replaced by {@link #listIterator()}.
   */
  @Deprecated
  IntListIterator intListIterator();

  /**
   * Returns a type-specific list iterator on the list starting at a given index.
   *
   * @see #listIterator(int)
   * @deprecated As of <code>fastutil</code> 5, replaced by {@link #listIterator(int)}.
   */
  @Deprecated
  IntListIterator intListIterator(int index);

  /**
   * Returns a type-specific list iterator on the list.
   *
   * @see List#listIterator()
   */
  IntListIterator listIterator();

  /**
   * Returns a type-specific list iterator on the list starting at a given index.
   *
   * @see List#listIterator(int)
   */
  IntListIterator listIterator(int index);

  /**
   * Returns a type-specific view of the portion of this list from the index <code>from</code>, inclusive, to the index <code>to</code>, exclusive.
   *
   * @see List#subList(int, int)
   * @deprecated As of <code>fastutil</code> 5, replaced by {@link #subList(int, int)}.
   */
  @Deprecated
  IntList intSubList(int from, int to);

  /**
   * Returns a type-specific view of the portion of this list from the index <code>from</code>, inclusive, to the index <code>to</code>, exclusive.
   * <p>
   * <P>Note that this specification strengthens the one given in {@link List#subList(int, int)}.
   *
   * @see List#subList(int, int)
   */
  IntList subList(int from, int to);


  /**
   * Sets the size of this list.
   * <p>
   * <P>If the specified size is smaller than the current size, the last elements are
   * discarded. Otherwise, they are filled with 0/<code>null</code>/<code>false</code>.
   *
   * @param size the new size.
   */

  void size(int size);

  /**
   * Copies (hopefully quickly) elements of this type-specific list into the given array.
   *
   * @param from   the start index (inclusive).
   * @param a      the destination array.
   * @param offset the offset into the destination array where to store the first element copied.
   * @param length the number of elements to be copied.
   */
  void getElements(int from, int a[], int offset, int length);

  /**
   * Removes (hopefully quickly) elements of this type-specific list.
   *
   * @param from the start index (inclusive).
   * @param to   the end index (exclusive).
   */
  void removeElements(int from, int to);

  /**
   * Add (hopefully quickly) elements to this type-specific list.
   *
   * @param index the index at which to add elements.
   * @param a     the array containing the elements.
   */
  void addElements(int index, int a[]);

  /**
   * Add (hopefully quickly) elements to this type-specific list.
   *
   * @param index  the index at which to add elements.
   * @param a      the array containing the elements.
   * @param offset the offset of the first element to add.
   * @param length the number of elements to add.
   */
  void addElements(int index, int a[], int offset, int length);


  /**
   * @see List#add(Object)
   */
  boolean add(int key);

  /**
   * @see List#add(int, Object)
   */
  void add(int index, int key);

  /**
   * @see List#add(int, Object)
   */
  boolean addAll(int index, IntCollection c);

  /**
   * @see List#add(int, Object)
   */
  boolean addAll(int index, IntList c);

  /**
   * @see List#add(int, Object)
   */
  boolean addAll(IntList c);

  /**
   * @see List#get(int)
   */
  int getInt(int index);

  /**
   * @see List#indexOf(Object)
   */
  int indexOf(int k);

  /**
   * @see List#lastIndexOf(Object)
   */
  int lastIndexOf(int k);

  /**
   * @see List#remove(int)
   */
  int removeInt(int index);

  /**
   * @see List#set(int, Object)
   */
  int set(int index, int k);


}

