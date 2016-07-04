/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil.objects;

import java.util.Set;

/**
 * An abstract class providing basic methods for sets implementing a type-specific interface.
 */

public abstract class AbstractObjectSet<K> extends AbstractObjectCollection<K> implements Cloneable, ObjectSet<K> {

  protected AbstractObjectSet() {
  }

  public abstract ObjectIterator<K> iterator();

  public boolean equals(final Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof Set)) {
      return false;
    }

    Set<?> s = (Set<?>) o;
    if (s.size() != size()) {
      return false;
    }
    return containsAll(s);
  }


  /**
   * Returns a hash code for this set.
   * <p>
   * The hash code of a set is computed by summing the hash codes of
   * its elements.
   *
   * @return a hash code for this set.
   */

  public int hashCode() {
    int h = 0, n = size();
    ObjectIterator<K> i = iterator();
    K k;

    while (n-- != 0) {
      k = i.next(); // We need k because KEY2JAVAHASH() is a macro with repeated evaluation.
      h += ((k) == null ? 0 : (k).hashCode());
    }
    return h;
  }


  public boolean remove(Object k) {
    throw new UnsupportedOperationException();
  }
}

