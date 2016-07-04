/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */


package akka.remote.artery.fastutil.objects;

import java.util.Collection;
import java.util.NoSuchElementException;

/** A simple, brute-force implementation of a set based on a backing array.
 *
 * <p>The main purpose of this
 * implementation is that of wrapping cleanly the brute-force approach to the storage of a very 
 * small number of items: just put them into an array and scan linearly to find an item.
 */

public class ObjectArraySet <K> extends AbstractObjectSet <K> implements java.io.Serializable, Cloneable {

 private static final long serialVersionUID = 1L;
 /** The backing array (valid up to {@link #size}, excluded). */
 private transient Object[] a;
 /** The number of valid entries in {@link #a}. */
 private int size;

 /** Creates a new array set using the given backing array. The resulting set will have as many elements as the array.
	 * 
	 * <p>It is responsibility of the caller that the elements of <code>a</code> are distinct.
	 * 
	 * @param a the backing array.
	 */
 public ObjectArraySet( final Object[] a ) {
  this.a = a;
  size = a.length;
 }

 /** Creates a new empty array set.
	 */
 public ObjectArraySet() {
  this.a = ObjectArrays.EMPTY_ARRAY;
 }

 /** Creates a new empty array set of given initial capacity.
	 * 
	 * @param capacity the initial capacity.
	 */
 public ObjectArraySet( final int capacity ) {
  this.a = new Object[ capacity ];
 }

 /** Creates a new array set copying the contents of a given collection.
	 * @param c a collection.
	 */
 public ObjectArraySet( ObjectCollection <K> c ) {
  this( c.size () );
  addAll( c );
 }

 /** Creates a new array set copying the contents of a given set.
	 * @param c a collection.
	 */
 public ObjectArraySet( final Collection<? extends K> c ) {
  this( c.size() );
  addAll( c );
 }


 /** Creates a new array set using the given backing array and the given number of elements of the array.
	 *
	 * <p>It is responsibility of the caller that the first <code>size</code> elements of <code>a</code> are distinct.
	 * 
	 * @param a the backing array.
	 * @param size the number of valid elements in <code>a</code>.
	 */
 public ObjectArraySet( final Object[] a, final int size ) {
  this.a = a;
  this.size = size;
  if ( size > a.length ) throw new IllegalArgumentException( "The provided size (" + size + ") is larger than or equal to the array size (" + a.length + ")" );
 }

 private int findKey( final Object o ) {
  for( int i = size; i-- != 0; ) if ( ( (a[ i ]) == null ? (o) == null : (a[ i ]).equals(o) ) ) return i;
  return -1;
 }

 @Override
 @SuppressWarnings("unchecked")
 public ObjectIterator <K> iterator() {
  return new AbstractObjectIterator <K> () {
   int next = 0;

   public boolean hasNext() {
    return next < size;
   }

   public K next() {
    if ( ! hasNext() ) throw new NoSuchElementException();
    return (K) a[ next++ ];
   }

   public void remove() {
    final int tail = size-- - next--;
    System.arraycopy( a, next + 1, a, next, tail );

    a[ size ] = null;

   }
  };
 }

 public boolean contains( final Object k ) {
  return findKey( k ) != -1;
 }

 public int size() {
  return size;
 }

 @Override
 public boolean remove( final Object k ) {
  final int pos = findKey( k );
  if ( pos == -1 ) return false;
  final int tail = size - pos - 1;
  for( int i = 0; i < tail; i++ ) a[ pos + i ] = a[ pos + i + 1 ];
  size--;

  a[ size ] = null;

  return true;
 }

 @Override
 public boolean add( final K k ) {
  final int pos = findKey( k );
  if ( pos != -1 ) return false;
  if ( size == a.length ) {
   final Object[] b = new Object[ size == 0 ? 2 : size * 2 ];
   for( int i = size; i-- != 0; ) b[ i ] = a[ i ];
   a = b;
  }
  a[ size++ ] = k;
  return true;
 }

 @Override
 public void clear() {

  for( int i = size; i-- != 0; ) a[ i ] = null;

  size = 0;
 }

 @Override
 public boolean isEmpty() {
  return size == 0;
 }

 /** Returns a deep copy of this set. 
	 *
	 * <P>This method performs a deep copy of this hash set; the data stored in the
	 * set, however, is not cloned. Note that this makes a difference only for object keys.
	 *
	 *  @return a deep copy of this set.
	 */

 @SuppressWarnings("unchecked")
 public ObjectArraySet <K> clone() {
  ObjectArraySet <K> c;
  try {
   c = (ObjectArraySet <K>)super.clone();
  }
  catch(CloneNotSupportedException cantHappen) {
   throw new InternalError();
  }
  c.a = a.clone();
  return c;
 }

 private void writeObject(java.io.ObjectOutputStream s) throws java.io.IOException {
  s.defaultWriteObject();
  for( int i = 0; i < size; i++ ) s.writeObject( a[ i ] );
 }


 private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
  s.defaultReadObject();
  a = new Object[ size ];
  for( int i = 0; i < size; i++ ) a[ i ] = s.readObject();
 }

}

