/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */


package akka.remote.artery.fastutil.objects;

import java.util.ListIterator;

/** A type-specific bidirectional iterator that is also a {@link ListIterator}.
 *
 * <P>This interface merges the methods provided by a {@link ListIterator} and
 * a type-specific {@link akka.remote.artery.fastutil.BidirectionalIterator}. Moreover, it provides
 * type-specific versions of {@link java.util.ListIterator#add(Object) add()}
 * and {@link java.util.ListIterator#set(Object) set()}.
 *
 * @see java.util.ListIterator
 * @see akka.remote.artery.fastutil.BidirectionalIterator
 */

public interface ObjectListIterator <K> extends ListIterator<K>, ObjectBidirectionalIterator <K> {






}

