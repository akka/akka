/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil.objects;


import java.util.Map;
import java.util.NoSuchElementException;

import akka.remote.artery.fastutil.ints.IntCollection;
import akka.remote.artery.fastutil.ints.IntCollections;
import akka.remote.artery.fastutil.ints.IntArraySet;
import akka.remote.artery.fastutil.ints.IntArrays;

/**
 * A simple, brute-force implementation of a map based on two parallel backing arrays.
 * <p>
 * <p>The main purpose of this
 * implementation is that of wrapping cleanly the brute-force approach to the storage of a very
 * small number of pairs: just put them into two parallel arrays and scan linearly to find an item.
 */

public class Object2IntArrayMap<K> extends AbstractObject2IntMap<K> implements java.io.Serializable, Cloneable {

  private static final long serialVersionUID = 1L;
  /**
   * The keys (valid up to {@link #size}, excluded).
   */
  private transient Object[] key;
  /**
   * The values (parallel to {@link #key}).
   */
  private transient int[] value;
  /**
   * The number of valid entries in {@link #key} and {@link #value}.
   */
  private int size;

  /**
   * Creates a new empty array map with given key and value backing arrays. The resulting map will have as many entries as the given arrays.
   * <p>
   * <p>It is responsibility of the caller that the elements of <code>key</code> are distinct.
   *
   * @param key   the key array.
   * @param value the value array (it <em>must</em> have the same length as <code>key</code>).
   */
  public Object2IntArrayMap(final Object[] key, final int[] value) {
    this.key = key;
    this.value = value;
    size = key.length;
    if (key.length != value.length) {
      throw new IllegalArgumentException("Keys and values have different lengths (" + key.length + ", " + value.length + ")");
    }
  }

  /**
   * Creates a new empty array map.
   */
  public Object2IntArrayMap() {
    this.key = ObjectArrays.EMPTY_ARRAY;
    this.value = IntArrays.EMPTY_ARRAY;
  }

  /**
   * Creates a new empty array map of given capacity.
   *
   * @param capacity the initial capacity.
   */
  public Object2IntArrayMap(final int capacity) {
    this.key = new Object[capacity];
    this.value = new int[capacity];
  }

  /**
   * Creates a new empty array map copying the entries of a given map.
   *
   * @param m a map.
   */
  public Object2IntArrayMap(final Object2IntMap<K> m) {
    this(m.size());
    putAll(m);
  }

  /**
   * Creates a new empty array map copying the entries of a given map.
   *
   * @param m a map.
   */
  public Object2IntArrayMap(final Map<? extends K, ? extends Integer> m) {
    this(m.size());
    putAll(m);
  }

  /**
   * Creates a new array map with given key and value backing arrays, using the given number of elements.
   * <p>
   * <p>It is responsibility of the caller that the first <code>size</code> elements of <code>key</code> are distinct.
   *
   * @param key   the key array.
   * @param value the value array (it <em>must</em> have the same length as <code>key</code>).
   * @param size  the number of valid elements in <code>key</code> and <code>value</code>.
   */
  public Object2IntArrayMap(final Object[] key, final int[] value, final int size) {
    this.key = key;
    this.value = value;
    this.size = size;
    if (key.length != value.length) {
      throw new IllegalArgumentException("Keys and values have different lengths (" + key.length + ", " + value.length + ")");
    }
    if (size > key.length) {
      throw new IllegalArgumentException("The provided size (" + size + ") is larger than or equal to the backing-arrays size (" + key.length + ")");
    }
  }

  private final class EntrySet extends AbstractObjectSet<Object2IntMap.Entry<K>> implements FastEntrySet<K> {

    @Override
    public ObjectIterator<Object2IntMap.Entry<K>> iterator() {
      return new AbstractObjectIterator<Object2IntMap.Entry<K>>() {
        int curr = -1, next = 0;

        public boolean hasNext() {
          return next < size;
        }

        @SuppressWarnings("unchecked")
        public Entry<K> next() {
          if (!hasNext()) {
            throw new NoSuchElementException();
          }
          return new AbstractObject2IntMap.BasicEntry<K>((K) key[curr = next], value[next++]);
        }

        public void remove() {
          if (curr == -1) {
            throw new IllegalStateException();
          }
          curr = -1;
          final int tail = size-- - next--;
          System.arraycopy(key, next + 1, key, next, tail);
          System.arraycopy(value, next + 1, value, next, tail);

          key[size] = null;


        }
      };
    }

    public ObjectIterator<Object2IntMap.Entry<K>> fastIterator() {
      return new AbstractObjectIterator<Object2IntMap.Entry<K>>() {
        int next = 0, curr = -1;
        final BasicEntry<K> entry = new BasicEntry<K>((null), (0));

        public boolean hasNext() {
          return next < size;
        }

        @SuppressWarnings("unchecked")
        public Entry<K> next() {
          if (!hasNext()) {
            throw new NoSuchElementException();
          }
          entry.key = (K) key[curr = next];
          entry.value = value[next++];
          return entry;
        }

        public void remove() {
          if (curr == -1) {
            throw new IllegalStateException();
          }
          curr = -1;
          final int tail = size-- - next--;
          System.arraycopy(key, next + 1, key, next, tail);
          System.arraycopy(value, next + 1, value, next, tail);

          key[size] = null;


        }
      };
    }

    public int size() {
      return size;
    }

    @SuppressWarnings("unchecked")
    public boolean contains(Object o) {
      if (!(o instanceof Map.Entry)) {
        return false;
      }
      final Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;


      if (e.getValue() == null || !(e.getValue() instanceof Integer)) {
        return false;
      }

      final K k = ((K) e.getKey());
      return Object2IntArrayMap.this.containsKey(k) && ((Object2IntArrayMap.this.getInt(k)) == (((((Integer) (e.getValue())).intValue()))));
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean remove(final Object o) {
      if (!(o instanceof Map.Entry)) {
        return false;
      }
      final Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;


      if (e.getValue() == null || !(e.getValue() instanceof Integer)) {
        return false;
      }

      final K k = ((K) e.getKey());
      final int v = ((((Integer) (e.getValue())).intValue()));

      final int oldPos = Object2IntArrayMap.this.findKey(k);
      if (oldPos == -1 || !((v) == (Object2IntArrayMap.this.value[oldPos]))) {
        return false;
      }
      final int tail = size - oldPos - 1;
      System.arraycopy(Object2IntArrayMap.this.key, oldPos + 1, Object2IntArrayMap.this.key, oldPos, tail);
      System.arraycopy(Object2IntArrayMap.this.value, oldPos + 1, Object2IntArrayMap.this.value, oldPos, tail);
      Object2IntArrayMap.this.size--;

      Object2IntArrayMap.this.key[size] = null;


      return true;
    }
  }

  public FastEntrySet<K> object2IntEntrySet() {
    return new EntrySet();
  }

  private int findKey(final Object k) {
    final Object[] key = this.key;
    for (int i = size; i-- != 0; ) {
      if (((key[i]) == null ? (k) == null : (key[i]).equals(k))) {
        return i;
      }
    }
    return -1;
  }


  public int getInt(final Object k) {


    final Object[] key = this.key;
    for (int i = size; i-- != 0; ) {
      if (((key[i]) == null ? (k) == null : (key[i]).equals(k))) {
        return value[i];
      }
    }
    return defRetValue;
  }

  public int size() {
    return size;
  }

  @Override
  public void clear() {

    for (int i = size; i-- != 0; ) {

      key[i] = null;


    }

    size = 0;
  }

  @Override
  public boolean containsKey(final Object k) {
    return findKey(k) != -1;
  }

  @Override
  public boolean containsValue(int v) {
    for (int i = size; i-- != 0; ) {
      if (((value[i]) == (v))) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean isEmpty() {
    return size == 0;
  }

  @Override
  public int put(K k, int v) {
    final int oldKey = findKey(k);
    if (oldKey != -1) {
      final int oldValue = value[oldKey];
      value[oldKey] = v;
      return oldValue;
    }
    if (size == key.length) {
      final Object[] newKey = new Object[size == 0 ? 2 : size * 2];
      final int[] newValue = new int[size == 0 ? 2 : size * 2];
      for (int i = size; i-- != 0; ) {
        newKey[i] = key[i];
        newValue[i] = value[i];
      }
      key = newKey;
      value = newValue;
    }
    key[size] = k;
    value[size] = v;
    size++;
    return defRetValue;
  }

  @Override


  public int removeInt(final Object k) {


    final int oldPos = findKey(k);
    if (oldPos == -1) {
      return defRetValue;
    }
    final int oldValue = value[oldPos];
    final int tail = size - oldPos - 1;
    System.arraycopy(key, oldPos + 1, key, oldPos, tail);
    System.arraycopy(value, oldPos + 1, value, oldPos, tail);
    size--;

    key[size] = null;


    return oldValue;
  }

  @Override

  public ObjectSet<K> keySet() {
    return new ObjectArraySet<K>(key, size);
  }

  @Override
  public IntCollection values() {
    return IntCollections.unmodifiable(new IntArraySet(value, size));
  }

  /**
   * Returns a deep copy of this map.
   * <p>
   * <P>This method performs a deep copy of this hash map; the data stored in the
   * map, however, is not cloned. Note that this makes a difference only for object keys.
   *
   * @return a deep copy of this map.
   */

  @SuppressWarnings("unchecked")
  public Object2IntArrayMap<K> clone() {
    Object2IntArrayMap<K> c;
    try {
      c = (Object2IntArrayMap<K>) super.clone();
    } catch (CloneNotSupportedException cantHappen) {
      throw new InternalError();
    }
    c.key = key.clone();
    c.value = value.clone();
    return c;
  }

  private void writeObject(java.io.ObjectOutputStream s) throws java.io.IOException {
    s.defaultWriteObject();
    for (int i = 0; i < size; i++) {
      s.writeObject(key[i]);
      s.writeInt(value[i]);
    }
  }

  private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
    s.defaultReadObject();
    key = new Object[size];
    value = new int[size];
    for (int i = 0; i < size; i++) {
      key[i] = s.readObject();
      value[i] = s.readInt();
    }
  }
}

