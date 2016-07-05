/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */


package akka.remote.artery.fastutil.ints;

import java.util.Set;

/** A type-specific {@link Set}; provides some additional methods that use polymorphism to avoid (un)boxing. 
 *
 * <P>Additionally, this interface strengthens (again) {@link #iterator()}.
 *
 * @see Set
 */

public interface IntSet extends IntCollection , Set<Integer> {

 /** Returns a type-specific iterator on the elements of this set.
	 *
	 * <p>Note that this specification strengthens the one given in {@link java.lang.Iterable#iterator()},
	 * which was already strengthened in the corresponding type-specific class,
	 * but was weakened by the fact that this interface extends {@link Set}.
	 *
	 * @return a type-specific iterator on the elements of this set.
	 */
 IntIterator iterator();

 /** Removes an element from this set.
	 *
	 * <p>Note that the corresponding method of the type-specific collection is <code>rem()</code>.
	 * This unfortunate situation is caused by the clash
	 * with the similarly named index-based method in the {@link java.util.List} interface.
	 *
	 * @see java.util.Collection#remove(Object)
	 */
 boolean remove(int k);
}

