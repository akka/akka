/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil.objects;


import akka.remote.artery.fastutil.Hash;
import akka.remote.artery.fastutil.HashCommon;

import static akka.remote.artery.fastutil.HashCommon.arraySize;
import static akka.remote.artery.fastutil.HashCommon.maxFill;

import java.util.Map;
import java.util.Arrays;
import java.util.NoSuchElementException;

import akka.remote.artery.fastutil.ints.IntCollection;
import akka.remote.artery.fastutil.ints.AbstractIntCollection;


import akka.remote.artery.fastutil.ints.IntIterator;
import akka.remote.artery.fastutil.objects.AbstractObjectSet;

/**
 * A type-specific hash map with a fast, small-footprint implementation.
 * <p>
 * <P>Instances of this class use a hash table to represent a map. The table is
 * filled up to a specified <em>load factor</em>, and then doubled in size to
 * accommodate new entries. If the table is emptied below <em>one fourth</em>
 * of the load factor, it is halved in size. However, halving is
 * not performed when deleting entries from an iterator, as it would interfere
 * with the iteration process.
 * <p>
 * <p>Note that {@link #clear()} does not modify the hash table size.
 * Rather, a family of {@linkplain #trim() trimming
 * methods} lets you control the size of the table; this is particularly useful
 * if you reuse instances of this class.
 *
 * @see Hash
 * @see HashCommon
 */

public class Object2IntOpenHashMap<K> extends AbstractObject2IntMap<K> implements java.io.Serializable, Cloneable, Hash {


  private static final long serialVersionUID = 0L;
  private static final boolean ASSERTS = false;

  /**
   * The array of keys.
   */
  protected transient K[] key;

  /**
   * The array of values.
   */
  protected transient int[] value;

  /**
   * The mask for wrapping a position counter.
   */
  protected transient int mask;

  /**
   * Whether this set contains the key zero.
   */
  protected transient boolean containsNullKey;
  /**
   * The current table size.
   */
  protected transient int n;

  /**
   * Threshold after which we rehash. It must be the table size times {@link #f}.
   */
  protected transient int maxFill;

  /**
   * Number of entries in the set (including the key zero, if present).
   */
  protected int size;

  /**
   * The acceptable load factor.
   */
  protected final float f;
  /**
   * Cached set of entries.
   */
  protected transient FastEntrySet<K> entries;

  /**
   * Cached set of keys.
   */
  protected transient ObjectSet<K> keys;


  /**
   * Cached collection of values.
   */
  protected transient IntCollection values;

  /**
   * Creates a new hash map.
   * <p>
   * <p>The actual table size will be the least power of two greater than <code>expected</code>/<code>f</code>.
   *
   * @param expected the expected number of elements in the hash set.
   * @param f        the load factor.
   */
  @SuppressWarnings("unchecked")
  public Object2IntOpenHashMap(final int expected, final float f) {

    if (f <= 0 || f > 1) {
      throw new IllegalArgumentException("Load factor must be greater than 0 and smaller than or equal to 1");
    }
    if (expected < 0) {
      throw new IllegalArgumentException("The expected number of elements must be nonnegative");
    }

    this.f = f;

    n = arraySize(expected, f);
    mask = n - 1;
    maxFill = maxFill(n, f);
    key = (K[]) new Object[n + 1];
    value = new int[n + 1];


  }

  /**
   * Creates a new hash map with {@link Hash#DEFAULT_LOAD_FACTOR} as load factor.
   *
   * @param expected the expected number of elements in the hash map.
   */

  public Object2IntOpenHashMap(final int expected) {
    this(expected, DEFAULT_LOAD_FACTOR);
  }

  /**
   * Creates a new hash map with initial expected {@link Hash#DEFAULT_INITIAL_SIZE} entries
   * and {@link Hash#DEFAULT_LOAD_FACTOR} as load factor.
   */

  public Object2IntOpenHashMap() {
    this(DEFAULT_INITIAL_SIZE, DEFAULT_LOAD_FACTOR);
  }

  /**
   * Creates a new hash map copying a given one.
   *
   * @param m a {@link Map} to be copied into the new hash map.
   * @param f the load factor.
   */

  public Object2IntOpenHashMap(final Map<? extends K, ? extends Integer> m, final float f) {
    this(m.size(), f);
    putAll(m);
  }

  /**
   * Creates a new hash map with {@link Hash#DEFAULT_LOAD_FACTOR} as load factor copying a given one.
   *
   * @param m a {@link Map} to be copied into the new hash map.
   */

  public Object2IntOpenHashMap(final Map<? extends K, ? extends Integer> m) {
    this(m, DEFAULT_LOAD_FACTOR);
  }

  /**
   * Creates a new hash map copying a given type-specific one.
   *
   * @param m a type-specific map to be copied into the new hash map.
   * @param f the load factor.
   */

  public Object2IntOpenHashMap(final Object2IntMap<K> m, final float f) {
    this(m.size(), f);
    putAll(m);
  }

  /**
   * Creates a new hash map with {@link Hash#DEFAULT_LOAD_FACTOR} as load factor copying a given type-specific one.
   *
   * @param m a type-specific map to be copied into the new hash map.
   */

  public Object2IntOpenHashMap(final Object2IntMap<K> m) {
    this(m, DEFAULT_LOAD_FACTOR);
  }

  /**
   * Creates a new hash map using the elements of two parallel arrays.
   *
   * @param k the array of keys of the new hash map.
   * @param v the array of corresponding values in the new hash map.
   * @param f the load factor.
   * @throws IllegalArgumentException if <code>k</code> and <code>v</code> have different lengths.
   */

  public Object2IntOpenHashMap(final K[] k, final int[] v, final float f) {
    this(k.length, f);
    if (k.length != v.length) {
      throw new IllegalArgumentException("The key array and the value array have different lengths (" + k.length + " and " + v.length + ")");
    }
    for (int i = 0; i < k.length; i++) {
      this.put(k[i], v[i]);
    }
  }

  /**
   * Creates a new hash map with {@link Hash#DEFAULT_LOAD_FACTOR} as load factor using the elements of two parallel arrays.
   *
   * @param k the array of keys of the new hash map.
   * @param v the array of corresponding values in the new hash map.
   * @throws IllegalArgumentException if <code>k</code> and <code>v</code> have different lengths.
   */

  public Object2IntOpenHashMap(final K[] k, final int[] v) {
    this(k, v, DEFAULT_LOAD_FACTOR);
  }

  private int realSize() {
    return containsNullKey ? size - 1 : size;
  }

  private void ensureCapacity(final int capacity) {
    final int needed = arraySize(capacity, f);
    if (needed > n) {
      rehash(needed);
    }
  }

  private void tryCapacity(final long capacity) {
    final int needed = (int) Math.min(1 << 30, Math.max(2, HashCommon.nextPowerOfTwo((long) Math.ceil(capacity / f))));
    if (needed > n) {
      rehash(needed);
    }
  }

  private int removeEntry(final int pos) {
    final int oldValue = value[pos];


    size--;


    shiftKeys(pos);
    if (size < maxFill / 4 && n > DEFAULT_INITIAL_SIZE) {
      rehash(n / 2);
    }
    return oldValue;
  }

  private int removeNullEntry() {
    containsNullKey = false;

    key[n] = null;

    final int oldValue = value[n];


    size--;


    if (size < maxFill / 4 && n > DEFAULT_INITIAL_SIZE) {
      rehash(n / 2);
    }
    return oldValue;
  }


  /**
   * {@inheritDoc}
   */
  public void putAll(Map<? extends K, ? extends Integer> m) {
    if (f <= .5) {
      ensureCapacity(m.size()); // The resulting map will be sized for m.size() elements
    } else {
      tryCapacity(size() + m.size()); // The resulting map will be tentatively sized for size() + m.size() elements
    }
    super.putAll(m);
  }

  private int insert(final K k, final int v) {
    int pos;

    if (((k) == null)) {
      if (containsNullKey) {
        return n;
      }
      containsNullKey = true;
      pos = n;
    } else {
      K curr;
      final K[] key = this.key;

      // The starting point.
      if (!((curr = key[pos = (akka.remote.artery.fastutil.HashCommon.mix((k).hashCode())) & mask]) == null)) {
        if (((curr).equals(k))) {
          return pos;
        }
        while (!((curr = key[pos = (pos + 1) & mask]) == null)) {
          if (((curr).equals(k))) {
            return pos;
          }
        }
      }
    }

    key[pos] = k;
    value[pos] = v;
    if (size++ >= maxFill) {
      rehash(arraySize(size + 1, f));
    }
    if (ASSERTS) {
      checkTable();
    }
    return -1;
  }

  public int put(final K k, final int v) {
    final int pos = insert(k, v);
    if (pos < 0) {
      return defRetValue;
    }
    final int oldValue = value[pos];
    value[pos] = v;
    return oldValue;
  }


  /**
   * {@inheritDoc}
   *
   * @deprecated Please use the corresponding type-specific method instead.
   */
  @Deprecated
  @Override
  public Integer put(final K ok, final Integer ov) {
    final int v = ((ov).intValue());

    final int pos = insert((ok), v);
    if (pos < 0) {
      return (null);
    }
    final int oldValue = value[pos];
    value[pos] = v;
    return (Integer.valueOf(oldValue));
  }


  private int addToValue(final int pos, final int incr) {
    final int oldValue = value[pos];


    value[pos] = oldValue + incr;

    return oldValue;
  }

  /**
   * Adds an increment to value currently associated with a key.
   * <p>
   * <P>Note that this method respects the {@linkplain #defaultReturnValue() default return value} semantics: when
   * called with a key that does not currently appears in the map, the key
   * will be associated with the default return value plus
   * the given increment.
   *
   * @param k    the key.
   * @param incr the increment.
   * @return the old value, or the {@linkplain #defaultReturnValue() default return value} if no value was present for the given key.
   */
  public int addTo(final K k, final int incr) {
    int pos;

    if (((k) == null)) {
      if (containsNullKey) {
        return addToValue(n, incr);
      }
      pos = n;
      containsNullKey = true;
    } else {
      K curr;
      final K[] key = this.key;

      // The starting point.
      if (!((curr = key[pos = (akka.remote.artery.fastutil.HashCommon.mix((k).hashCode())) & mask]) == null)) {
        if (((curr).equals(k))) {
          return addToValue(pos, incr);
        }
        while (!((curr = key[pos = (pos + 1) & mask]) == null)) {
          if (((curr).equals(k))) {
            return addToValue(pos, incr);
          }
        }
      }
    }

    key[pos] = k;


    value[pos] = defRetValue + incr;
    if (size++ >= maxFill) {
      rehash(arraySize(size + 1, f));
    }
    if (ASSERTS) {
      checkTable();
    }
    return defRetValue;
  }


  /**
   * Shifts left entries with the specified hash code, starting at the specified position,
   * and empties the resulting free entry.
   *
   * @param pos a starting position.
   */
  protected final void shiftKeys(int pos) {
    // Shift entries with the same hash.
    int last, slot;
    K curr;
    final K[] key = this.key;

    for (; ; ) {
      pos = ((last = pos) + 1) & mask;

      for (; ; ) {
        if (((curr = key[pos]) == null)) {
          key[last] = (null);


          return;
        }
        slot = (akka.remote.artery.fastutil.HashCommon.mix((curr).hashCode())) & mask;
        if (last <= pos ? last >= slot || slot > pos : last >= slot && slot > pos) {
          break;
        }
        pos = (pos + 1) & mask;
      }

      key[last] = curr;
      value[last] = value[pos];


    }
  }


  @SuppressWarnings("unchecked")
  public int removeInt(final Object k) {
    if ((((K) k) == null)) {
      if (containsNullKey) {
        return removeNullEntry();
      }
      return defRetValue;
    }

    K curr;
    final K[] key = this.key;
    int pos;

    // The starting point.
    if (((curr = key[pos = (akka.remote.artery.fastutil.HashCommon.mix((k).hashCode())) & mask]) == null)) {
      return defRetValue;
    }
    if (((k).equals(curr))) {
      return removeEntry(pos);
    }
    while (true) {
      if (((curr = key[pos = (pos + 1) & mask]) == null)) {
        return defRetValue;
      }
      if (((k).equals(curr))) {
        return removeEntry(pos);
      }
    }
  }


  /**
   * {@inheritDoc}
   *
   * @deprecated Please use the corresponding type-specific method instead.
   */
  @Deprecated
  @Override
  @SuppressWarnings("unchecked")
  public Integer remove(final Object ok) {
    final K k = (K) (ok);
    if (((k) == null)) {
      if (containsNullKey) {
        return (Integer.valueOf(removeNullEntry()));
      }
      return (null);
    }

    K curr;
    final K[] key = this.key;
    int pos;

    // The starting point.
    if (((curr = key[pos = (akka.remote.artery.fastutil.HashCommon.mix((k).hashCode())) & mask]) == null)) {
      return (null);
    }
    if (((curr).equals(k))) {
      return (Integer.valueOf(removeEntry(pos)));
    }
    while (true) {
      if (((curr = key[pos = (pos + 1) & mask]) == null)) {
        return (null);
      }
      if (((curr).equals(k))) {
        return (Integer.valueOf(removeEntry(pos)));
      }
    }
  }

  @SuppressWarnings("unchecked")
  public int getInt(final Object k) {
    if ((((K) k) == null)) {
      return containsNullKey ? value[n] : defRetValue;
    }

    K curr;
    final K[] key = this.key;
    int pos;

    // The starting point.
    if (((curr = key[pos = (akka.remote.artery.fastutil.HashCommon.mix((k).hashCode())) & mask]) == null)) {
      return defRetValue;
    }
    if (((k).equals(curr))) {
      return value[pos];
    }
    // There's always an unused entry.
    while (true) {
      if (((curr = key[pos = (pos + 1) & mask]) == null)) {
        return defRetValue;
      }
      if (((k).equals(curr))) {
        return value[pos];
      }
    }
  }

  @SuppressWarnings("unchecked")
  public boolean containsKey(final Object k) {
    if ((((K) k) == null)) {
      return containsNullKey;
    }

    K curr;
    final K[] key = this.key;
    int pos;

    // The starting point.
    if (((curr = key[pos = (akka.remote.artery.fastutil.HashCommon.mix((k).hashCode())) & mask]) == null)) {
      return false;
    }
    if (((k).equals(curr))) {
      return true;
    }
    // There's always an unused entry.
    while (true) {
      if (((curr = key[pos = (pos + 1) & mask]) == null)) {
        return false;
      }
      if (((k).equals(curr))) {
        return true;
      }
    }
  }


  public boolean containsValue(final int v) {
    final int value[] = this.value;
    final K key[] = this.key;
    if (containsNullKey && ((value[n]) == (v))) {
      return true;
    }
    for (int i = n; i-- != 0; ) {
      if (!((key[i]) == null) && ((value[i]) == (v))) {
        return true;
      }
    }
    return false;
  }

  /* Removes all elements from this map.
    *
    * <P>To increase object reuse, this method does not change the table size.
    * If you want to reduce the table size, you must use {@link #trim()}.
    *
    */
  public void clear() {
    if (size == 0) {
      return;
    }
    size = 0;
    containsNullKey = false;

    Arrays.fill(key, (null));


  }

  public int size() {
    return size;
  }

  public boolean isEmpty() {
    return size == 0;
  }


  /**
   * A no-op for backward compatibility.
   *
   * @param growthFactor unused.
   * @deprecated Since <code>fastutil</code> 6.1.0, hash tables are doubled when they are too full.
   */
  @Deprecated
  public void growthFactor(int growthFactor) {
  }


  /**
   * Gets the growth factor (2).
   *
   * @return the growth factor of this set, which is fixed (2).
   * @see #growthFactor(int)
   * @deprecated Since <code>fastutil</code> 6.1.0, hash tables are doubled when they are too full.
   */
  @Deprecated
  public int growthFactor() {
    return 16;
  }


  /**
   * The entry class for a hash map does not record key and value, but
   * rather the position in the hash table of the corresponding entry. This
   * is necessary so that calls to {@link java.util.Map.Entry#setValue(Object)} are reflected in
   * the map
   */

  final class MapEntry implements Object2IntMap.Entry<K>, Map.Entry<K, Integer> {
    // The table index this entry refers to, or -1 if this entry has been deleted.
    int index;

    MapEntry(final int index) {
      this.index = index;
    }

    MapEntry() {
    }


    public K getKey() {
      return (key[index]);
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated Please use the corresponding type-specific method instead.
     */
    @Deprecated

    public Integer getValue() {
      return (Integer.valueOf(value[index]));
    }


    public int getIntValue() {
      return value[index];
    }


    public int setValue(final int v) {
      final int oldValue = value[index];
      value[index] = v;
      return oldValue;
    }


    public Integer setValue(final Integer v) {
      return (Integer.valueOf(setValue(((v).intValue()))));
    }


    @SuppressWarnings("unchecked")
    public boolean equals(final Object o) {
      if (!(o instanceof Map.Entry)) {
        return false;
      }
      Map.Entry<K, Integer> e = (Map.Entry<K, Integer>) o;

      return ((key[index]) == null ? ((e.getKey())) == null : (key[index]).equals((e.getKey()))) && ((value[index]) == (((e.getValue()).intValue())));
    }

    public int hashCode() {
      return ((key[index]) == null ? 0 : (key[index]).hashCode()) ^ (value[index]);
    }


    public String toString() {
      return key[index] + "=>" + value[index];
    }
  }

  /**
   * An iterator over a hash map.
   */

  private class MapIterator {
    /**
     * The index of the last entry returned, if positive or zero; initially, {@link #n}. If negative, the last
     * entry returned was that of the key of index {@code - pos - 1} from the {@link #wrapped} list.
     */
    int pos = n;
    /**
     * The index of the last entry that has been returned (more precisely, the value of {@link #pos} if {@link #pos} is positive,
     * or {@link Integer#MIN_VALUE} if {@link #pos} is negative). It is -1 if either
     * we did not return an entry yet, or the last returned entry has been removed.
     */
    int last = -1;
    /**
     * A downward counter measuring how many entries must still be returned.
     */
    int c = size;
    /**
     * A boolean telling us whether we should return the entry with the null key.
     */
    boolean mustReturnNullKey = Object2IntOpenHashMap.this.containsNullKey;
    /**
     * A lazily allocated list containing keys of entries that have wrapped around the table because of removals.
     */
    ObjectArrayList<K> wrapped;

    public boolean hasNext() {
      return c != 0;
    }

    public int nextEntry() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }

      c--;
      if (mustReturnNullKey) {
        mustReturnNullKey = false;
        return last = n;
      }

      final K key[] = Object2IntOpenHashMap.this.key;

      for (; ; ) {
        if (--pos < 0) {
          // We are just enumerating elements from the wrapped list.
          last = Integer.MIN_VALUE;
          final K k = wrapped.get(-pos - 1);
          int p = (akka.remote.artery.fastutil.HashCommon.mix((k).hashCode())) & mask;
          while (!((k).equals(key[p]))) {
            p = (p + 1) & mask;
          }
          return p;
        }
        if (!((key[pos]) == null)) {
          return last = pos;
        }
      }
    }

    /**
     * Shifts left entries with the specified hash code, starting at the specified position,
     * and empties the resulting free entry.
     *
     * @param pos a starting position.
     */
    private final void shiftKeys(int pos) {
      // Shift entries with the same hash.
      int last, slot;
      K curr;
      final K[] key = Object2IntOpenHashMap.this.key;

      for (; ; ) {
        pos = ((last = pos) + 1) & mask;

        for (; ; ) {
          if (((curr = key[pos]) == null)) {
            key[last] = (null);


            return;
          }
          slot = (akka.remote.artery.fastutil.HashCommon.mix((curr).hashCode())) & mask;
          if (last <= pos ? last >= slot || slot > pos : last >= slot && slot > pos) {
            break;
          }
          pos = (pos + 1) & mask;
        }

        if (pos < last) { // Wrapped entry.
          if (wrapped == null) {
            wrapped = new ObjectArrayList<K>(2);
          }
          wrapped.add(key[pos]);
        }

        key[last] = curr;
        value[last] = value[pos];
      }
    }

    public void remove() {
      if (last == -1) {
        throw new IllegalStateException();
      }
      if (last == n) {
        containsNullKey = false;

        key[n] = null;


      } else if (pos >= 0) {
        shiftKeys(last);
      } else {
        // We're removing wrapped entries.

        Object2IntOpenHashMap.this.remove(wrapped.set(-pos - 1, null));


        last = -1; // Note that we must not decrement size
        return;
      }

      size--;
      last = -1; // You can no longer remove this entry.
      if (ASSERTS) {
        checkTable();
      }
    }

    public int skip(final int n) {
      int i = n;
      while (i-- != 0 && hasNext()) {
        nextEntry();
      }
      return n - i - 1;
    }
  }


  private class EntryIterator extends MapIterator implements ObjectIterator<Object2IntMap.Entry<K>> {
    private MapEntry entry;

    public Object2IntMap.Entry<K> next() {
      return entry = new MapEntry(nextEntry());
    }

    @Override
    public void remove() {
      super.remove();
      entry.index = -1; // You cannot use a deleted entry.
    }
  }

  private class FastEntryIterator extends MapIterator implements ObjectIterator<Object2IntMap.Entry<K>> {
    private final MapEntry entry = new MapEntry();

    public MapEntry next() {
      entry.index = nextEntry();
      return entry;
    }
  }

  private final class MapEntrySet extends AbstractObjectSet<Object2IntMap.Entry<K>> implements FastEntrySet<K> {

    public ObjectIterator<Object2IntMap.Entry<K>> iterator() {
      return new EntryIterator();
    }

    public ObjectIterator<Object2IntMap.Entry<K>> fastIterator() {
      return new FastEntryIterator();
    }


    @SuppressWarnings("unchecked")
    public boolean contains(final Object o) {
      if (!(o instanceof Map.Entry)) {
        return false;
      }
      final Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;


      if (e.getValue() == null || !(e.getValue() instanceof Integer)) {
        return false;
      }

      final K k = ((K) e.getKey());
      final int v = ((((Integer) (e.getValue())).intValue()));

      if (((k) == null)) {
        return Object2IntOpenHashMap.this.containsNullKey && ((value[n]) == (v));
      }

      K curr;
      final K[] key = Object2IntOpenHashMap.this.key;
      int pos;

      // The starting point.
      if (((curr = key[pos = (akka.remote.artery.fastutil.HashCommon.mix((k).hashCode())) & mask]) == null)) {
        return false;
      }
      if (((k).equals(curr))) {
        return ((value[pos]) == (v));
      }
      // There's always an unused entry.
      while (true) {
        if (((curr = key[pos = (pos + 1) & mask]) == null)) {
          return false;
        }
        if (((k).equals(curr))) {
          return ((value[pos]) == (v));
        }
      }
    }

    @SuppressWarnings("unchecked")
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


      if (((k) == null)) {
        if (containsNullKey && ((value[n]) == (v))) {
          removeNullEntry();
          return true;
        }
        return false;
      }

      K curr;
      final K[] key = Object2IntOpenHashMap.this.key;
      int pos;

      // The starting point.
      if (((curr = key[pos = (akka.remote.artery.fastutil.HashCommon.mix((k).hashCode())) & mask]) == null)) {
        return false;
      }
      if (((curr).equals(k))) {
        if (((value[pos]) == (v))) {
          removeEntry(pos);
          return true;
        }
        return false;
      }

      while (true) {
        if (((curr = key[pos = (pos + 1) & mask]) == null)) {
          return false;
        }
        if (((curr).equals(k))) {
          if (((value[pos]) == (v))) {
            removeEntry(pos);
            return true;
          }
        }
      }
    }

    public int size() {
      return size;
    }

    public void clear() {
      Object2IntOpenHashMap.this.clear();
    }
  }


  public FastEntrySet<K> object2IntEntrySet() {
    if (entries == null) {
      entries = new MapEntrySet();
    }

    return entries;
  }


  /**
   * An iterator on keys.
   * <p>
   * <P>We simply override the {@link java.util.ListIterator#next()}/{@link java.util.ListIterator#previous()} methods
   * (and possibly their type-specific counterparts) so that they return keys
   * instead of entries.
   */
  private final class KeyIterator extends MapIterator implements ObjectIterator<K> {


    public KeyIterator() {
      super();
    }

    public K next() {
      return key[nextEntry()];
    }


  }

  private final class KeySet extends AbstractObjectSet<K> {

    public ObjectIterator<K> iterator() {
      return new KeyIterator();
    }


    public int size() {
      return size;
    }

    public boolean contains(Object k) {
      return containsKey(k);
    }

    public boolean remove(Object k) {
      final int oldSize = size;
      Object2IntOpenHashMap.this.remove(k);
      return size != oldSize;
    }

    public void clear() {
      Object2IntOpenHashMap.this.clear();
    }
  }


  public ObjectSet<K> keySet() {

    if (keys == null) {
      keys = new KeySet();
    }
    return keys;
  }


  /**
   * An iterator on values.
   * <p>
   * <P>We simply override the {@link java.util.ListIterator#next()}/{@link java.util.ListIterator#previous()} methods
   * (and possibly their type-specific counterparts) so that they return values
   * instead of entries.
   */
  private final class ValueIterator extends MapIterator implements IntIterator {


    public ValueIterator() {
      super();
    }

    public int nextInt() {
      return value[nextEntry()];
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated Please use the corresponding type-specific method instead.
     */
    @Deprecated
    @Override
    public Integer next() {
      return (Integer.valueOf(value[nextEntry()]));
    }

  }

  public IntCollection values() {
    if (values == null) {
      values = new AbstractIntCollection() {

        public IntIterator iterator() {
          return new ValueIterator();
        }

        public int size() {
          return size;
        }

        public boolean contains(int v) {
          return containsValue(v);
        }

        public void clear() {
          Object2IntOpenHashMap.this.clear();
        }
      };
    }

    return values;
  }


  /**
   * A no-op for backward compatibility. The kind of tables implemented by
   * this class never need rehashing.
   * <p>
   * <P>If you need to reduce the table size to fit exactly
   * this set, use {@link #trim()}.
   *
   * @return true.
   * @see #trim()
   * @deprecated A no-op.
   */

  @Deprecated
  public boolean rehash() {
    return true;
  }


  /**
   * Rehashes the map, making the table as small as possible.
   * <p>
   * <P>This method rehashes the table to the smallest size satisfying the
   * load factor. It can be used when the set will not be changed anymore, so
   * to optimize access speed and size.
   * <p>
   * <P>If the table size is already the minimum possible, this method
   * does nothing.
   *
   * @return true if there was enough memory to trim the map.
   * @see #trim(int)
   */

  public boolean trim() {
    final int l = arraySize(size, f);
    if (l >= n || size > maxFill(l, f)) {
      return true;
    }
    try {
      rehash(l);
    } catch (OutOfMemoryError cantDoIt) {
      return false;
    }
    return true;
  }


  /**
   * Rehashes this map if the table is too large.
   * <p>
   * <P>Let <var>N</var> be the smallest table size that can hold
   * <code>max(n,{@link #size()})</code> entries, still satisfying the load factor. If the current
   * table size is smaller than or equal to <var>N</var>, this method does
   * nothing. Otherwise, it rehashes this map in a table of size
   * <var>N</var>.
   * <p>
   * <P>This method is useful when reusing maps.  {@linkplain #clear() Clearing a
   * map} leaves the table size untouched. If you are reusing a map
   * many times, you can call this method with a typical
   * size to avoid keeping around a very large table just
   * because of a few large transient maps.
   *
   * @param n the threshold for the trimming.
   * @return true if there was enough memory to trim the map.
   * @see #trim()
   */

  public boolean trim(final int n) {
    final int l = HashCommon.nextPowerOfTwo((int) Math.ceil(n / f));
    if (l >= n || size > maxFill(l, f)) {
      return true;
    }
    try {
      rehash(l);
    } catch (OutOfMemoryError cantDoIt) {
      return false;
    }
    return true;
  }

  /**
   * Rehashes the map.
   * <p>
   * <P>This method implements the basic rehashing strategy, and may be
   * overriden by subclasses implementing different rehashing strategies (e.g.,
   * disk-based rehashing). However, you should not override this method
   * unless you understand the internal workings of this class.
   *
   * @param newN the new size
   */

  @SuppressWarnings("unchecked")
  protected void rehash(final int newN) {
    final K key[] = this.key;
    final int value[] = this.value;

    final int mask = newN - 1; // Note that this is used by the hashing macro
    final K newKey[] = (K[]) new Object[newN + 1];
    final int newValue[] = new int[newN + 1];
    int i = n, pos;

    for (int j = realSize(); j-- != 0; ) {
      while (((key[--i]) == null)) {
        ;
      }

      if (!((newKey[pos = (akka.remote.artery.fastutil.HashCommon.mix((key[i]).hashCode())) & mask]) == null)) {
        while (!((newKey[pos = (pos + 1) & mask]) == null)) {
          ;
        }
      }

      newKey[pos] = key[i];
      newValue[pos] = value[i];
    }

    newValue[newN] = value[n];


    n = newN;
    this.mask = mask;
    maxFill = maxFill(n, f);
    this.key = newKey;
    this.value = newValue;
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
  public Object2IntOpenHashMap<K> clone() {
    Object2IntOpenHashMap<K> c;
    try {
      c = (Object2IntOpenHashMap<K>) super.clone();
    } catch (CloneNotSupportedException cantHappen) {
      throw new InternalError();
    }

    c.keys = null;
    c.values = null;
    c.entries = null;
    c.containsNullKey = containsNullKey;

    c.key = key.clone();
    c.value = value.clone();


    return c;
  }


  /**
   * Returns a hash code for this map.
   * <p>
   * This method overrides the generic method provided by the superclass.
   * Since <code>equals()</code> is not overriden, it is important
   * that the value returned by this method is the same value as
   * the one returned by the overriden method.
   *
   * @return a hash code for this map.
   */

  public int hashCode() {
    int h = 0;
    for (int j = realSize(), i = 0, t = 0; j-- != 0; ) {
      while (((key[i]) == null)) {
        i++;
      }

      if (this != key[i])

      {
        t = ((key[i]).hashCode());
      }


      t ^= (value[i]);
      h += t;
      i++;
    }
    // Zero / null keys have hash zero.		
    if (containsNullKey) {
      h += (value[n]);
    }
    return h;
  }


  private void writeObject(java.io.ObjectOutputStream s) throws java.io.IOException {
    final K key[] = this.key;
    final int value[] = this.value;
    final MapIterator i = new MapIterator();

    s.defaultWriteObject();

    for (int j = size, e; j-- != 0; ) {
      e = i.nextEntry();
      s.writeObject(key[e]);
      s.writeInt(value[e]);
    }
  }


  @SuppressWarnings("unchecked")
  private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
    s.defaultReadObject();

    n = arraySize(size, f);
    maxFill = maxFill(n, f);
    mask = n - 1;

    final K key[] = this.key = (K[]) new Object[n + 1];
    final int value[] = this.value = new int[n + 1];


    K k;
    int v;

    for (int i = size, pos; i-- != 0; ) {

      k = (K) s.readObject();
      v = s.readInt();

      if (((k) == null)) {
        pos = n;
        containsNullKey = true;
      } else {
        pos = (akka.remote.artery.fastutil.HashCommon.mix((k).hashCode())) & mask;
        while (!((key[pos]) == null)) {
          pos = (pos + 1) & mask;
        }
      }

      key[pos] = k;
      value[pos] = v;
    }
    if (ASSERTS) {
      checkTable();
    }
  }

  private void checkTable() {
  }
}

