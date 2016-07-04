/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */


package akka.remote.artery.fastutil.objects;

import java.util.List;



/** A type-specific {@link List}; provides some additional methods that use polymorphism to avoid (un)boxing. 
 *
 * <P>Note that this type-specific interface extends {@link Comparable}: it is expected that implementing
 * classes perform a lexicographical comparison using the standard operator "less then" for primitive types,
 * and the usual {@link Comparable#compareTo(Object) compareTo()} method for objects.
 *
 * <P>Additionally, this interface strengthens {@link #listIterator()},
 * {@link #listIterator(int)} and {@link #subList(int,int)}.
 *
 * <P>Besides polymorphic methods, this interfaces specifies methods to copy into an array or remove contiguous
 * sublists. Although the abstract implementation of this interface provides simple, one-by-one implementations
 * of these methods, it is expected that concrete implementation override them with optimized versions.
 *
 * @see List
 */

public interface ObjectList <K> extends List<K>, Comparable<List<? extends K>>, ObjectCollection <K> {
 /** Returns a type-specific iterator on the elements of this list (in proper sequence).
	 *
	 * Note that this specification strengthens the one given in {@link List#iterator()}.
	 * It would not be normally necessary, but {@link java.lang.Iterable#iterator()} is bizarrily re-specified
	 * in {@link List}.
	 *
	 * @return an iterator on the elements of this list (in proper sequence).
	 */
 ObjectListIterator <K> iterator();

 /** Returns a type-specific list iterator on the list.
	 *
	 * @see #listIterator()
	 * @deprecated As of <code>fastutil</code> 5, replaced by {@link #listIterator()}.
	 */
 @Deprecated
 ObjectListIterator <K> objectListIterator();

 /** Returns a type-specific list iterator on the list starting at a given index.
	 *
	 * @see #listIterator(int)
	 * @deprecated As of <code>fastutil</code> 5, replaced by {@link #listIterator(int)}.
	 */
 @Deprecated
 ObjectListIterator <K> objectListIterator( int index );

 /** Returns a type-specific list iterator on the list.
	 *
	 * @see List#listIterator()
	 */
 ObjectListIterator <K> listIterator();

 /** Returns a type-specific list iterator on the list starting at a given index.
	 *
	 * @see List#listIterator(int)
	 */
 ObjectListIterator <K> listIterator( int index );

 /** Returns a type-specific view of the portion of this list from the index <code>from</code>, inclusive, to the index <code>to</code>, exclusive.
	 * @see List#subList(int,int)
	 * @deprecated As of <code>fastutil</code> 5, replaced by {@link #subList(int,int)}.
	 */
 @Deprecated
 ObjectList <K> objectSubList( int from, int to );

 /** Returns a type-specific view of the portion of this list from the index <code>from</code>, inclusive, to the index <code>to</code>, exclusive.
	 *
	 * <P>Note that this specification strengthens the one given in {@link List#subList(int,int)}.
	 *
	 * @see List#subList(int,int)
	 */
 ObjectList <K> subList(int from, int to);


 /** Sets the size of this list.
	 *
	 * <P>If the specified size is smaller than the current size, the last elements are
	 * discarded. Otherwise, they are filled with 0/<code>null</code>/<code>false</code>.
	 *
	 * @param size the new size.
	 */

 void size( int size );

 /** Copies (hopefully quickly) elements of this type-specific list into the given array.
	 *
	 * @param from the start index (inclusive).
	 * @param a the destination array.
	 * @param offset the offset into the destination array where to store the first element copied.
	 * @param length the number of elements to be copied.
	 */
 void getElements( int from, Object a[], int offset, int length );

 /** Removes (hopefully quickly) elements of this type-specific list.
	 *
	 * @param from the start index (inclusive).
	 * @param to the end index (exclusive).
	 */
 void removeElements( int from, int to );

 /** Add (hopefully quickly) elements to this type-specific list.
	 *
	 * @param index the index at which to add elements.
	 * @param a the array containing the elements.
	 */
 void addElements( int index, K a[] );

 /** Add (hopefully quickly) elements to this type-specific list.
	 *
	 * @param index the index at which to add elements.
	 * @param a the array containing the elements.
	 * @param offset the offset of the first element to add.
	 * @param length the number of elements to add.
	 */
 void addElements( int index, K a[], int offset, int length );
}

