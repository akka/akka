/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.annotation.tailrec
import akka.util.OptionVal
import scala.reflect.ClassTag
import java.util.Arrays

/**
 * INTERNAL API
 */
private[akka] object ImmutableLongMap {
  def empty[A >: Null](implicit t: ClassTag[A]): ImmutableLongMap[A] =
    new ImmutableLongMap(Array.emptyLongArray, Array.empty)

  private val MaxScanLength = 10
}

/**
 * INTERNAL API
 * Specialized Map for primitive `Long` keys to avoid allocations (boxing).
 * Keys and values are backed by arrays and lookup is performed with binary
 * search. It's intended for rather small (<1000) maps.
 */
private[akka] class ImmutableLongMap[A >: Null] private (keys: Array[Long], values: Array[A])(implicit t: ClassTag[A]) {
  import ImmutableLongMap.MaxScanLength

  val size: Int = keys.length

  /**
   * Worst case `O(log n)`, allocation free.
   */
  def get(key: Long): OptionVal[A] = {
    val i = Arrays.binarySearch(keys, key)
    if (i >= 0) OptionVal(values(i))
    else OptionVal.None
  }

  /**
   * Worst case `O(log n)`, allocation free.
   */
  def contains(key: Long): Boolean = {
    Arrays.binarySearch(keys, key) >= 0
  }

  /**
   * Worst case `O(log n)`, creates new `ImmutableLongMap`
   * with copies of the internal arrays for the keys and
   * values.
   */
  def updated(key: Long, value: A): ImmutableLongMap[A] = {
    if (size == 0)
      new ImmutableLongMap(Array(key), Array(value))
    else {
      val i = Arrays.binarySearch(keys, key)
      if (i >= 0) {
        // existing key, replace value
        val newValues = Array.ofDim[A](values.length)
        System.arraycopy(values, 0, newValues, 0, values.length)
        newValues(i) = value
        new ImmutableLongMap(keys, newValues)
      } else {
        // insert the entry at the right position, and keep the arrays sorted
        val j = -(i + 1)
        val newKeys = Array.ofDim[Long](size + 1)
        System.arraycopy(keys, 0, newKeys, 0, j)
        newKeys(j) = key
        System.arraycopy(keys, j, newKeys, j + 1, keys.length - j)

        val newValues = Array.ofDim[A](size + 1)
        System.arraycopy(values, 0, newValues, 0, j)
        newValues(j) = value
        System.arraycopy(values, j, newValues, j + 1, values.length - j)

        new ImmutableLongMap(newKeys, newValues)
      }
    }
  }

  /**
   * All keys
   */
  def keysIterator: Iterator[Long] =
    keys.iterator

}
