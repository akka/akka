/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil;

import java.util.Iterator;
import java.util.ListIterator;

/**
 * A bidirectional {@link Iterator}.
 * <p>
 * <P>This kind of iterator is essentially a {@link ListIterator} that
 * does not support {@link ListIterator#previousIndex()} and {@link
 * ListIterator#nextIndex()}. It is useful for those maps that can easily
 * provide bidirectional iteration, but provide no index.
 * <p>
 * <P>Note that iterators returned by <code>fastutil</code> classes are more
 * specific, and support skipping. This class serves the purpose of organising
 * in a cleaner way the relationships between various iterators.
 *
 * @see Iterator
 * @see ListIterator
 */

public interface BidirectionalIterator<K> extends Iterator<K> {

  /**
   * Returns the previous element from the collection.
   *
   * @return the previous element from the collection.
   * @see java.util.ListIterator#previous()
   */

  K previous();

  /**
   * Returns whether there is a previous element.
   *
   * @return whether there is a previous element.
   * @see java.util.ListIterator#hasPrevious()
   */

  boolean hasPrevious();
}
