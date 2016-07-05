/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */


package akka.remote.artery.fastutil.objects;


import akka.remote.artery.fastutil.Stack;


import java.util.List;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.Collection;
import java.util.NoSuchElementException;

/**  An abstract class providing basic methods for lists implementing a type-specific list interface.
 *
 * <P>As an additional bonus, this class implements on top of the list operations a type-specific stack.
 */

public abstract class AbstractObjectList <K> extends AbstractObjectCollection <K> implements ObjectList <K>, Stack <K> {

 protected AbstractObjectList() {}

 /** Ensures that the given index is nonnegative and not greater than the list size.
	 *
	 * @param index an index.
	 * @throws IndexOutOfBoundsException if the given index is negative or greater than the list size.
	 */
 protected void ensureIndex( final int index ) {
  if ( index < 0 ) throw new IndexOutOfBoundsException( "Index (" + index + ") is negative" );
  if ( index > size() ) throw new IndexOutOfBoundsException( "Index (" + index + ") is greater than list size (" + ( size() ) + ")" );
 }

 /** Ensures that the given index is nonnegative and smaller than the list size.
	 *
	 * @param index an index.
	 * @throws IndexOutOfBoundsException if the given index is negative or not smaller than the list size.
	 */
 protected void ensureRestrictedIndex( final int index ) {
  if ( index < 0 ) throw new IndexOutOfBoundsException( "Index (" + index + ") is negative" );
  if ( index >= size() ) throw new IndexOutOfBoundsException( "Index (" + index + ") is greater than or equal to list size (" + ( size() ) + ")" );
 }

 public void add( final int index, final K k ) {
  throw new UnsupportedOperationException();
 }

 public boolean add( final K k ) {
  add( size(), k );
  return true;
 }

 public K remove( int i ) {
  throw new UnsupportedOperationException();
 }

 public K set( final int index, final K k ) {
  throw new UnsupportedOperationException();
 }

 public boolean addAll( int index, final Collection<? extends K> c ) {
  ensureIndex( index );
  int n = c.size();
  if ( n == 0 ) return false;
  Iterator<? extends K> i = c.iterator();
  while( n-- != 0 ) add( index++, i.next() );
  return true;
 }

 /** Delegates to a more generic method. */
 public boolean addAll( final Collection<? extends K> c ) {
  return addAll( size(), c );
 }

 /** Delegates to the new covariantly stronger generic method. */

 @Deprecated
 public ObjectListIterator <K> objectListIterator() {
  return listIterator();
 }

 /** Delegates to the new covariantly stronger generic method. */

 @Deprecated
 public ObjectListIterator <K> objectListIterator( final int index ) {
  return listIterator( index );
 }

 public ObjectListIterator <K> iterator() {
  return listIterator();
 }

 public ObjectListIterator <K> listIterator() {
  return listIterator( 0 );
 }

 public ObjectListIterator <K> listIterator( final int index ) {
  ensureIndex( index );

  return new AbstractObjectListIterator <K>() {
    int pos = index, last = -1;

    public boolean hasNext() { return pos < AbstractObjectList.this.size(); }
    public boolean hasPrevious() { return pos > 0; }
    public K next() { if ( ! hasNext() ) throw new NoSuchElementException(); return AbstractObjectList.this.get( last = pos++ ); }
    public K previous() { if ( ! hasPrevious() ) throw new NoSuchElementException(); return AbstractObjectList.this.get( last = --pos ); }
    public int nextIndex() { return pos; }
    public int previousIndex() { return pos - 1; }
    public void add( K k ) {
     AbstractObjectList.this.add( pos++, k );
     last = -1;
    }
    public void set( K k ) {
     if ( last == -1 ) throw new IllegalStateException();
     AbstractObjectList.this.set( last, k );
    }
    public void remove() {
     if ( last == -1 ) throw new IllegalStateException();
     AbstractObjectList.this.remove( last );
     /* If the last operation was a next(), we are removing an element *before* us, and we must decrease pos correspondingly. */
     if ( last < pos ) pos--;
     last = -1;
    }
   };
 }



 public boolean contains( final Object k ) {
  return indexOf( k ) >= 0;
 }

 public int indexOf( final Object k ) {
  final ObjectListIterator <K> i = listIterator();
  K e;
  while( i.hasNext() ) {
   e = i.next();
   if ( ( (k) == null ? (e) == null : (k).equals(e) ) ) return i.previousIndex();
  }
  return -1;
 }

 public int lastIndexOf( final Object k ) {
  ObjectListIterator <K> i = listIterator( size() );
  K e;
  while( i.hasPrevious() ) {
   e = i.previous();
   if ( ( (k) == null ? (e) == null : (k).equals(e) ) ) return i.nextIndex();
  }
  return -1;
 }

 public void size( final int size ) {
  int i = size();
  if ( size > i ) while( i++ < size ) add( (null) );
  else while( i-- != size ) remove( i );
 }


 public ObjectList <K> subList( final int from, final int to ) {
  ensureIndex( from );
  ensureIndex( to );
  if ( from > to ) throw new IndexOutOfBoundsException( "Start index (" + from + ") is greater than end index (" + to + ")" );

  return new ObjectSubList <K>( this, from, to );
 }

 /** Delegates to the new covariantly stronger generic method. */

 @Deprecated
 public ObjectList <K> objectSubList( final int from, final int to ) {
  return subList( from, to );
 }

 /** Removes elements of this type-specific list one-by-one. 
	 *
	 * <P>This is a trivial iterator-based implementation. It is expected that
	 * implementations will override this method with a more optimized version.
	 *
	 *
	 * @param from the start index (inclusive).
	 * @param to the end index (exclusive).
	 */

 public void removeElements( final int from, final int to ) {
  ensureIndex( to );
  ObjectListIterator <K> i = listIterator( from );
  int n = to - from;
  if ( n < 0 ) throw new IllegalArgumentException( "Start index (" + from + ") is greater than end index (" + to + ")" );
  while( n-- != 0 ) {
   i.next();
   i.remove();
  }
 }

 /** Adds elements to this type-specific list one-by-one. 
	 *
	 * <P>This is a trivial iterator-based implementation. It is expected that
	 * implementations will override this method with a more optimized version.
	 *
	 * @param index the index at which to add elements.
	 * @param a the array containing the elements.
	 * @param offset the offset of the first element to add.
	 * @param length the number of elements to add.
	 */

 public void addElements( int index, final K a[], int offset, int length ) {
  ensureIndex( index );
  if ( offset < 0 ) throw new ArrayIndexOutOfBoundsException( "Offset (" + offset + ") is negative" );
  if ( offset + length > a.length ) throw new ArrayIndexOutOfBoundsException( "End index (" + ( offset + length ) + ") is greater than array length (" + a.length + ")" );
  while( length-- != 0 ) add( index++, a[ offset++ ] );
 }

 public void addElements( final int index, final K a[] ) {
  addElements( index, a, 0, a.length );
 }

 /** Copies element of this type-specific list into the given array one-by-one.
	 *
	 * <P>This is a trivial iterator-based implementation. It is expected that
	 * implementations will override this method with a more optimized version.
	 *
	 * @param from the start index (inclusive).
	 * @param a the destination array.
	 * @param offset the offset into the destination array where to store the first element copied.
	 * @param length the number of elements to be copied.
	 */

 public void getElements( final int from, final Object a[], int offset, int length ) {
  ObjectListIterator <K> i = listIterator( from );
  if ( offset < 0 ) throw new ArrayIndexOutOfBoundsException( "Offset (" + offset + ") is negative" );
  if ( offset + length > a.length ) throw new ArrayIndexOutOfBoundsException( "End index (" + ( offset + length ) + ") is greater than array length (" + a.length + ")" );
  if ( from + length > size() ) throw new IndexOutOfBoundsException( "End index (" + ( from + length ) + ") is greater than list size (" + size() + ")" );
  while( length-- != 0 ) a[ offset++ ] = i.next();
 }


 private boolean valEquals( final Object a, final Object b ) {
  return a == null ? b == null : a.equals( b );
 }


 public boolean equals( final Object o ) {
  if ( o == this ) return true;
  if ( ! ( o instanceof List ) ) return false;

  final List<?> l = (List<?>)o;
  int s = size();
  if ( s != l.size() ) return false;
  final ListIterator<?> i1 = listIterator(), i2 = l.listIterator();




  while( s-- != 0 ) if ( ! valEquals( i1.next(), i2.next() ) ) return false;

  return true;
 }


    /** Compares this list to another object. If the
     * argument is a {@link java.util.List}, this method performs a lexicographical comparison; otherwise,
     * it throws a <code>ClassCastException</code>.
     *
     * @param l a list.
     * @return if the argument is a {@link java.util.List}, a negative integer,
     * zero, or a positive integer as this list is lexicographically less than, equal
     * to, or greater than the argument.
     * @throws ClassCastException if the argument is not a list.
     */

 @SuppressWarnings("unchecked")
 public int compareTo( final List<? extends K> l ) {
  if ( l == this ) return 0;

  if ( l instanceof ObjectList ) {

   final ObjectListIterator <K> i1 = listIterator(), i2 = ((ObjectList <K>)l).listIterator();
   int r;
   K e1, e2;

   while( i1.hasNext() && i2.hasNext() ) {
    e1 = i1.next();
    e2 = i2.next();
    if ( ( r = ( ((Comparable<K>)(e1)).compareTo(e2) ) ) != 0 ) return r;
   }
   return i2.hasNext() ? -1 : ( i1.hasNext() ? 1 : 0 );
  }

  ListIterator<? extends K> i1 = listIterator(), i2 = l.listIterator();
  int r;

  while( i1.hasNext() && i2.hasNext() ) {
   if ( ( r = ((Comparable<? super K>)i1.next()).compareTo( i2.next() ) ) != 0 ) return r;
  }
  return i2.hasNext() ? -1 : ( i1.hasNext() ? 1 : 0 );
 }


 /** Returns the hash code for this list, which is identical to {@link java.util.List#hashCode()}.
	 *
	 * @return the hash code for this list.
	 */
 public int hashCode() {
  ObjectIterator <K> i = iterator();
  int h = 1, s = size();
  while ( s-- != 0 ) {
   K k = i.next();
   h = 31 * h + ( (k) == null ? 0 : (k).hashCode() );
  }
  return h;
 }


 public void push( K o ) {
  add( o );
 }

 public K pop() {
  if ( isEmpty() ) throw new NoSuchElementException();
  return remove( size() - 1 );
 }

 public K top() {
  if ( isEmpty() ) throw new NoSuchElementException();
  return get( size() - 1 );
 }

 public K peek( int i ) {
  return get( size() - 1 - i );
 }
 public String toString() {
  final StringBuilder s = new StringBuilder();
  final ObjectIterator <K> i = iterator();
  int n = size();
  K k;
  boolean first = true;

  s.append("[");

  while( n-- != 0 ) {
   if (first) first = false;
   else s.append(", ");
   k = i.next();

   if (this == k) s.append("(this list)"); else

    s.append( String.valueOf( k ) );
  }

  s.append("]");
  return s.toString();
 }


 public static class ObjectSubList <K> extends AbstractObjectList <K> implements java.io.Serializable {
     private static final long serialVersionUID = -7046029254386353129L;
  /** The list this sublist restricts. */
  protected final ObjectList <K> l;
  /** Initial (inclusive) index of this sublist. */
  protected final int from;
  /** Final (exclusive) index of this sublist. */
  protected int to;

  private static final boolean ASSERTS = false;

  public ObjectSubList( final ObjectList <K> l, final int from, final int to ) {
   this.l = l;
   this.from = from;
   this.to = to;
  }

  private void assertRange() {
   if ( ASSERTS ) {
    assert from <= l.size();
    assert to <= l.size();
    assert to >= from;
   }
  }

  public boolean add( final K k ) {
   l.add( to, k );
   to++;
   if ( ASSERTS ) assertRange();
   return true;
  }

  public void add( final int index, final K k ) {
   ensureIndex( index );
   l.add( from + index, k );
   to++;
   if ( ASSERTS ) assertRange();
  }

  public boolean addAll( final int index, final Collection<? extends K> c ) {
   ensureIndex( index );
   to += c.size();
   if ( ASSERTS ) {
    boolean retVal = l.addAll( from + index, c );
    assertRange();
    return retVal;
   }
   return l.addAll( from + index, c );
  }

  public K get( int index ) {
   ensureRestrictedIndex( index );
   return l.get( from + index );
  }

  public K remove( int index ) {
   ensureRestrictedIndex( index );
   to--;
   return l.remove( from + index );
  }

  public K set( int index, K k ) {
   ensureRestrictedIndex( index );
   return l.set( from + index, k );
  }

  public void clear() {
   removeElements( 0, size() );
   if ( ASSERTS ) assertRange();
  }

  public int size() {
   return to - from;
  }

  public void getElements( final int from, final Object[] a, final int offset, final int length ) {
   ensureIndex( from );
   if ( from + length > size() ) throw new IndexOutOfBoundsException( "End index (" + from + length + ") is greater than list size (" + size() + ")" );
   l.getElements( this.from + from, a, offset, length );
  }

  public void removeElements( final int from, final int to ) {
   ensureIndex( from );
   ensureIndex( to );
   l.removeElements( this.from + from, this.from + to );
   this.to -= ( to - from );
   if ( ASSERTS ) assertRange();
  }

  public void addElements( int index, final K a[], int offset, int length ) {
   ensureIndex( index );
   l.addElements( this.from + index, a, offset, length );
   this.to += length;
   if ( ASSERTS ) assertRange();
  }

  public ObjectListIterator <K> listIterator( final int index ) {
   ensureIndex( index );

   return new AbstractObjectListIterator <K>() {
     int pos = index, last = -1;

     public boolean hasNext() { return pos < size(); }
     public boolean hasPrevious() { return pos > 0; }
     public K next() { if ( ! hasNext() ) throw new NoSuchElementException(); return l.get( from + ( last = pos++ ) ); }
     public K previous() { if ( ! hasPrevious() ) throw new NoSuchElementException(); return l.get( from + ( last = --pos ) ); }
     public int nextIndex() { return pos; }
     public int previousIndex() { return pos - 1; }
     public void add( K k ) {
      if ( last == -1 ) throw new IllegalStateException();
      ObjectSubList.this.add( pos++, k );
      last = -1;
      if ( ASSERTS ) assertRange();
     }
     public void set( K k ) {
      if ( last == -1 ) throw new IllegalStateException();
      ObjectSubList.this.set( last, k );
     }
     public void remove() {
      if ( last == -1 ) throw new IllegalStateException();
      ObjectSubList.this.remove( last );
      /* If the last operation was a next(), we are removing an element *before* us, and we must decrease pos correspondingly. */
      if ( last < pos ) pos--;
      last = -1;
      if ( ASSERTS ) assertRange();
     }
    };
  }

  public ObjectList <K> subList( final int from, final int to ) {
   ensureIndex( from );
   ensureIndex( to );
   if ( from > to ) throw new IllegalArgumentException( "Start index (" + from + ") is greater than end index (" + to + ")" );

   return new ObjectSubList <K>( this, from, to );
  }
  public boolean remove( final Object o ) {
   int index = indexOf( o );
   if ( index == -1 ) return false;
   remove( index );
   return true;
  }


 }

}

