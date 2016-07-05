/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */


package akka.remote.artery.fastutil.ints;

import akka.remote.artery.fastutil.ints.AbstractIntIterator;
import akka.remote.artery.fastutil.ints.AbstractIntSet;
import akka.remote.artery.fastutil.ints.IntArrays;
import akka.remote.artery.fastutil.ints.IntCollection;

import java.util.Collection;
import java.util.NoSuchElementException;

import static java.util.Collections.addAll;

/** A simple, brute-force implementation of a set based on a backing array.
 *
 * <p>The main purpose of this
 * implementation is that of wrapping cleanly the brute-force approach to the storage of a very 
 * small number of items: just put them into an array and scan linearly to find an item.
 */

public class IntArraySet extends AbstractIntSet implements java.io.Serializable, Cloneable {

 private static final long serialVersionUID = 1L;
 /** The backing array (valid up to {@link #size}, excluded). */
 private transient int[] a;
 /** The number of valid entries in {@link #a}. */
 private int size;

 /** Creates a new array set using the given backing array. The resulting set will have as many elements as the array.
	 * 
	 * <p>It is responsibility of the caller that the elements of <code>a</code> are distinct.
	 * 
	 * @param a the backing array.
	 */
 public IntArraySet( final int[] a ) {
  this.a = a;
  size = a.length;
 }

 /** Creates a new empty array set.
	 */
 public IntArraySet() {
  this.a = IntArrays.EMPTY_ARRAY;
 }

 /** Creates a new empty array set of given initial capacity.
	 * 
	 * @param capacity the initial capacity.
	 */
 public IntArraySet( final int capacity ) {
  this.a = new int[ capacity ];
 }

 /** Creates a new array set copying the contents of a given collection.
	 * @param c a collection.
	 */
 public IntArraySet( IntCollection c ) {
  this( c.size () );
  addAll( c );
 }

 /** Creates a new array set copying the contents of a given set.
	 * @param c a collection.
	 */
 public IntArraySet( final Collection<? extends Integer> c ) {
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
 public IntArraySet( final int[] a, final int size ) {
  this.a = a;
  this.size = size;
  if ( size > a.length ) throw new IllegalArgumentException( "The provided size (" + size + ") is larger than or equal to the array size (" + a.length + ")" );
 }

 private int findKey( final int o ) {
  for( int i = size; i-- != 0; ) if ( ( (a[ i ]) == (o) ) ) return i;
  return -1;
 }

 @Override

 public IntIterator iterator() {
  return new AbstractIntIterator() {
   int next = 0;

   public boolean hasNext() {
    return next < size;
   }

   public int nextInt() {
    if ( ! hasNext() ) throw new NoSuchElementException();
    return a[ next++ ];
   }

   public void remove() {
    final int tail = size-- - next--;
    System.arraycopy( a, next + 1, a, next, tail );



   }
  };
 }

 public boolean contains( final int k ) {
  return findKey( k ) != -1;
 }

 public int size() {
  return size;
 }

 @Override
 public boolean remove( final int k ) {
  final int pos = findKey( k );
  if ( pos == -1 ) return false;
  final int tail = size - pos - 1;
  for( int i = 0; i < tail; i++ ) a[ pos + i ] = a[ pos + i + 1 ];
  size--;



  return true;
 }

 @Override
 public boolean add( final int k ) {
  final int pos = findKey( k );
  if ( pos != -1 ) return false;
  if ( size == a.length ) {
   final int[] b = new int[ size == 0 ? 2 : size * 2 ];
   for( int i = size; i-- != 0; ) b[ i ] = a[ i ];
   a = b;
  }
  a[ size++ ] = k;
  return true;
 }

 @Override
 public void clear() {



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


 public IntArraySet clone() {
  IntArraySet c;
  try {
   c = (IntArraySet )super.clone();
  }
  catch(CloneNotSupportedException cantHappen) {
   throw new InternalError();
  }
  c.a = a.clone();
  return c;
 }

 private void writeObject(java.io.ObjectOutputStream s) throws java.io.IOException {
  s.defaultWriteObject();
  for( int i = 0; i < size; i++ ) s.writeInt( a[ i ] );
 }


 private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
  s.defaultReadObject();
  a = new int[ size ];
  for( int i = 0; i < size; i++ ) a[ i ] = s.readInt();
 }

}

