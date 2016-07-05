/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */


package akka.remote.artery.fastutil.objects;

import java.lang.Iterable;

/** A type-specific {@link Iterable} that strengthens that specification of {@link Iterable#iterator()}.
 *
 * <p><strong>Warning</strong>: Java will let you write &ldquo;colon&rdquo; <code>for</code> statements with primitive-type
 * loop variables; however, what is (unfortunately) really happening is that at each iteration an
 * unboxing (and, in the case of <code>fastutil</code> type-specific data structures, a boxing) will be performed. Watch out.
 *
 * @see Iterable
 */

public interface ObjectIterable <K> extends Iterable<K> {

 /** Returns a type-specific iterator.
	 *
	 * Note that this specification strengthens the one given in {@link Iterable#iterator()}.
	 *
	 * @return a type-specific iterator.
	 */
 ObjectIterator <K> iterator();
}

