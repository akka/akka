/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil.objects;

import akka.remote.artery.fastutil.ints.IntCollection;
import akka.remote.artery.fastutil.ints.AbstractIntCollection;
import akka.remote.artery.fastutil.ints.IntIterator;
import akka.remote.artery.fastutil.ints.AbstractIntIterator;

import akka.remote.artery.fastutil.objects.ObjectSet;


import java.util.Iterator;
import java.util.Map;

/**
 * An abstract class providing basic methods for maps implementing a type-specific interface.
 * <p>
 * <P>Optional operations just throw an {@link
 * UnsupportedOperationException}. Generic versions of accessors delegate to
 * the corresponding type-specific counterparts following the interface rules
 * (they take care of returning <code>null</code> on a missing key).
 * <p>
 * <P>As a further help, this class provides a {@link BasicEntry BasicEntry} inner class
 * that implements a type-specific version of {@link java.util.Map.Entry}; it
 * is particularly useful for those classes that do not implement their own
 * entries (e.g., most immutable maps).
 */

public abstract class AbstractObject2IntMap<K> extends AbstractObject2IntFunction<K> implements Object2IntMap<K>, java.io.Serializable {

  private static final long serialVersionUID = -4940583368468432370L;

  protected AbstractObject2IntMap() {
  }


  public boolean containsValue(Object ov) {
    if (ov == null) {
      return false;
    }
    return containsValue(((((Integer) (ov)).intValue())));
  }


  /**
   * Checks whether the given value is contained in {@link #values()}.
   */
  public boolean containsValue(int v) {
    return values().contains(v);
  }

  /**
   * Checks whether the given value is contained in {@link #keySet()}.
   */
  public boolean containsKey(Object k) {
    return keySet().contains(k);
  }

  /**
   * Puts all pairs in the given map.
   * If the map implements the interface of this map,
   * it uses the faster iterators.
   *
   * @param m a map.
   */


  @SuppressWarnings({"unchecked", "deprecation"})

  public void putAll(Map<? extends K, ? extends Integer> m) {
    int n = m.size();
    final Iterator<? extends Map.Entry<? extends K, ? extends Integer>> i = m.entrySet().iterator();

    if (m instanceof Object2IntMap) {
      Object2IntMap.Entry<? extends K> e;
      while (n-- != 0) {
        e = (Object2IntMap.Entry<? extends K>) i.next();
        put(e.getKey(), e.getIntValue());
      }
    } else {
      Map.Entry<? extends K, ? extends Integer> e;
      while (n-- != 0) {
        e = i.next();
        put(e.getKey(), e.getValue());
      }
    }
  }

  public boolean isEmpty() {
    return size() == 0;
  }

  /**
   * This class provides a basic but complete type-specific entry class for all those maps implementations
   * that do not have entries on their own (e.g., most immutable maps).
   * <p>
   * <P>This class does not implement {@link java.util.Map.Entry#setValue(Object) setValue()}, as the modification
   * would not be reflected in the base map.
   */

  public static class BasicEntry<K> implements Object2IntMap.Entry<K> {
    protected K key;
    protected int value;

    public BasicEntry(final K key, final Integer value) {
      this.key = (key);
      this.value = ((value).intValue());
    }


    public BasicEntry(final K key, final int value) {
      this.key = key;
      this.value = value;
    }

    public K getKey() {
      return (key);
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated Please use the corresponding type-specific method instead.
     */
    @Deprecated

    public Integer getValue() {
      return (Integer.valueOf(value));
    }


    public int getIntValue() {
      return value;
    }


    public int setValue(final int value) {
      throw new UnsupportedOperationException();
    }


    /**
     * {@inheritDoc}
     *
     * @deprecated Please use the corresponding type-specific method instead.
     */
    @Deprecated
    public Integer setValue(final Integer value) {
      return Integer.valueOf(setValue(value.intValue()));
    }


    public boolean equals(final Object o) {
      if (!(o instanceof Map.Entry)) {
        return false;
      }
      final Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;


      if (e.getValue() == null || !(e.getValue() instanceof Integer)) {
        return false;
      }

      return ((key) == null ? ((e.getKey())) == null : (key).equals((e.getKey()))) && ((value) == (((((Integer) (e.getValue())).intValue()))));
    }

    public int hashCode() {
      return ((key) == null ? 0 : (key).hashCode()) ^ (value);
    }


    public String toString() {
      return key + "->" + value;
    }
  }


  /**
   * Returns a type-specific-set view of the keys of this map.
   * <p>
   * <P>The view is backed by the set returned by {@link #entrySet()}. Note that
   * <em>no attempt is made at caching the result of this method</em>, as this would
   * require adding some attributes that lightweight implementations would
   * not need. Subclasses may easily override this policy by calling
   * this method and caching the result, but implementors are encouraged to
   * write more efficient ad-hoc implementations.
   *
   * @return a set view of the keys of this map; it may be safely cast to a type-specific interface.
   */


  public ObjectSet<K> keySet() {
    return new AbstractObjectSet<K>() {

      public boolean contains(final Object k) {
        return containsKey(k);
      }

      public int size() {
        return AbstractObject2IntMap.this.size();
      }

      public void clear() {
        AbstractObject2IntMap.this.clear();
      }

      public ObjectIterator<K> iterator() {
        return new AbstractObjectIterator<K>() {
          final ObjectIterator<Map.Entry<K, Integer>> i = entrySet().iterator();

          /** {@inheritDoc}
           * @deprecated Please use the corresponding type-specific method instead. */
          @Deprecated
          public K next() {
            return ((Object2IntMap.Entry<K>) i.next()).getKey();
          }

          ;

          public boolean hasNext() {
            return i.hasNext();
          }
        };
      }
    };
  }

  /**
   * Returns a type-specific-set view of the values of this map.
   * <p>
   * <P>The view is backed by the set returned by {@link #entrySet()}. Note that
   * <em>no attempt is made at caching the result of this method</em>, as this would
   * require adding some attributes that lightweight implementations would
   * not need. Subclasses may easily override this policy by calling
   * this method and caching the result, but implementors are encouraged to
   * write more efficient ad-hoc implementations.
   *
   * @return a set view of the values of this map; it may be safely cast to a type-specific interface.
   */


  public IntCollection values() {
    return new AbstractIntCollection() {

      public boolean contains(final int k) {
        return containsValue(k);
      }

      public int size() {
        return AbstractObject2IntMap.this.size();
      }

      public void clear() {
        AbstractObject2IntMap.this.clear();
      }

      public IntIterator iterator() {
        return new AbstractIntIterator() {
          final ObjectIterator<Map.Entry<K, Integer>> i = entrySet().iterator();

          /** {@inheritDoc}
           * @deprecated Please use the corresponding type-specific method instead. */
          @Deprecated
          public int nextInt() {
            return ((Object2IntMap.Entry<K>) i.next()).getIntValue();
          }

          ;

          public boolean hasNext() {
            return i.hasNext();
          }
        };
      }
    };
  }


  @SuppressWarnings({"unchecked", "rawtypes"})
  public ObjectSet<Map.Entry<K, Integer>> entrySet() {
    return (ObjectSet) object2IntEntrySet();
  }


  /**
   * Returns a hash code for this map.
   * <p>
   * The hash code of a map is computed by summing the hash codes of its entries.
   *
   * @return a hash code for this map.
   */

  public int hashCode() {
    int h = 0, n = size();
    final ObjectIterator<? extends Map.Entry<K, Integer>> i = entrySet().iterator();

    while (n-- != 0) {
      h += i.next().hashCode();
    }
    return h;
  }

  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof Map)) {
      return false;
    }

    Map<?, ?> m = (Map<?, ?>) o;
    if (m.size() != size()) {
      return false;
    }
    return entrySet().containsAll(m.entrySet());
  }


  public String toString() {
    final StringBuilder s = new StringBuilder();
    final ObjectIterator<? extends Map.Entry<K, Integer>> i = entrySet().iterator();
    int n = size();
    Object2IntMap.Entry<K> e;
    boolean first = true;

    s.append("{");

    while (n-- != 0) {
      if (first) {
        first = false;
      } else {
        s.append(", ");
      }

      e = (Object2IntMap.Entry<K>) i.next();


      if (this == e.getKey()) {
        s.append("(this map)");
      } else

      {
        s.append(String.valueOf(e.getKey()));
      }
      s.append("=>");


      s.append(String.valueOf(e.getIntValue()));
    }

    s.append("}");
    return s.toString();
  }


}

