/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */


package akka.remote.artery.fastutil.objects;

/**  An abstract class facilitating the creation of type-specific {@linkplain akka.remote.artery.fastutil.BidirectionalIterator bidirectional iterators}.
 *
 * <P>To create a type-specific bidirectional iterator, besides what is needed
 * for an iterator you need both a method returning the previous element as
 * primitive type and a method returning the previous element as an
 * object. However, if you inherit from this class you need just one (anyone).
 *
 * <P>This class implements also a trivial version of {@link #back(int)} that
 * uses type-specific methods.
 */

public abstract class AbstractObjectBidirectionalIterator <K> extends AbstractObjectIterator <K> implements ObjectBidirectionalIterator <K> {

 protected AbstractObjectBidirectionalIterator() {}
 /** This method just iterates the type-specific version of {@link #previous()} for
	 * at most <code>n</code> times, stopping if {@link
	 * #hasPrevious()} becomes false. */
 public int back( final int n ) {
  int i = n;
  while( i-- != 0 && hasPrevious() ) previous();
  return n - i - 1;
 }

}

