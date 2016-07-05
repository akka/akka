/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil.objects;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;

/**
 * An abstract class providing basic methods for collections implementing a type-specific interface.
 * <p>
 * <P>In particular, this class provide {@link #iterator()}, <code>add()</code>, {@link #remove(Object)} and
 * {@link #contains(Object)} methods that just call the type-specific counterpart.
 */

public abstract class AbstractObjectCollection<K> extends AbstractCollection<K> implements ObjectCollection<K> {

  protected AbstractObjectCollection() {
  }

  public Object[] toArray() {
    final Object[] a = new Object[size()];
    akka.remote.artery.fastutil.objects.ObjectIterators.unwrap(iterator(), a);
    return a;
  }

  @SuppressWarnings("unchecked")
  public <T> T[] toArray(T[] a) {
    final int size = size();
    if (a.length < size) {
      a = (T[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), size);
    }
    akka.remote.artery.fastutil.objects.ObjectIterators.unwrap(iterator(), a);
    if (size < a.length) {
      a[size] = null;
    }
    return a;
  }

  /**
   * Adds all elements of the given collection to this collection.
   *
   * @param c a collection.
   * @return <code>true</code> if this collection changed as a result of the call.
   */

  public boolean addAll(Collection<? extends K> c) {
    boolean retVal = false;
    final Iterator<? extends K> i = c.iterator();
    int n = c.size();

    while (n-- != 0) {
      if (add(i.next())) {
        retVal = true;
      }
    }
    return retVal;
  }

  public boolean add(K k) {
    throw new UnsupportedOperationException();
  }

  /**
   * Delegates to the new covariantly stronger generic method.
   */

  @Deprecated
  public ObjectIterator<K> objectIterator() {
    return iterator();
  }

  public abstract ObjectIterator<K> iterator();

  /**
   * Checks whether this collection contains all elements from the given collection.
   *
   * @param c a collection.
   * @return <code>true</code> if this collection contains all elements of the argument.
   */

  public boolean containsAll(Collection<?> c) {
    int n = c.size();

    final Iterator<?> i = c.iterator();
    while (n-- != 0) {
      if (!contains(i.next())) {
        return false;
      }
    }

    return true;
  }


  /**
   * Retains in this collection only elements from the given collection.
   *
   * @param c a collection.
   * @return <code>true</code> if this collection changed as a result of the call.
   */

  public boolean retainAll(Collection<?> c) {
    boolean retVal = false;
    int n = size();

    final Iterator<?> i = iterator();
    while (n-- != 0) {
      if (!c.contains(i.next())) {
        i.remove();
        retVal = true;
      }
    }

    return retVal;
  }

  /**
   * Remove from this collection all elements in the given collection.
   * If the collection is an instance of this class, it uses faster iterators.
   *
   * @param c a collection.
   * @return <code>true</code> if this collection changed as a result of the call.
   */

  public boolean removeAll(Collection<?> c) {
    boolean retVal = false;
    int n = c.size();

    final Iterator<?> i = c.iterator();
    while (n-- != 0) {
      if (remove(i.next())) {
        retVal = true;
      }
    }

    return retVal;
  }

  public boolean isEmpty() {
    return size() == 0;
  }

  public String toString() {
    final StringBuilder s = new StringBuilder();
    final ObjectIterator<K> i = iterator();
    int n = size();
    Object k;
    boolean first = true;

    s.append("{");

    while (n-- != 0) {
      if (first) {
        first = false;
      } else {
        s.append(", ");
      }
      k = i.next();

      if (this == k) {
        s.append("(this collection)");
      } else

      {
        s.append(String.valueOf(k));
      }
    }

    s.append("}");
    return s.toString();
  }
}

