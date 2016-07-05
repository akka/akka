/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */


package akka.remote.artery.fastutil.ints;

import java.util.Set;

/**  An abstract class providing basic methods for sets implementing a type-specific interface. */

public abstract class AbstractIntSet extends AbstractIntCollection implements Cloneable, IntSet {

 protected AbstractIntSet() {}

 public abstract IntIterator iterator();

 public boolean equals( final Object o ) {
  if ( o == this ) return true;
  if ( !( o instanceof Set ) ) return false;

  Set<?> s = (Set<?>) o;
  if ( s.size() != size() ) return false;
  return containsAll(s);
 }


 /** Returns a hash code for this set.
	 *
	 * The hash code of a set is computed by summing the hash codes of
	 * its elements.
	 *
	 * @return a hash code for this set.
	 */

 public int hashCode() {
  int h = 0, n = size();
  IntIterator i = iterator();
  int k;

  while( n-- != 0 ) {
   k = i.nextInt(); // We need k because KEY2JAVAHASH() is a macro with repeated evaluation.
   h += (k);
  }
  return h;
 }


 public boolean remove( int k ) {
  throw new UnsupportedOperationException();
 }



 /** Delegates to <code>remove()</code>.
	 *
	 * @param k the element to be removed.
	 * @return true if the set was modified.
	 */
 public boolean rem( int k ) {
  return remove( k );
 }

 /** Delegates to the corresponding type-specific method. */
 public boolean remove( final Object o ) {
  return remove( ((((Integer)(o)).intValue())) );
 }


}

