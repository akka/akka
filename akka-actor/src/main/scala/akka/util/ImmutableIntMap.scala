/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.util
import java.util.Arrays

import akka.annotation.InternalApi

import scala.annotation.tailrec

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ImmutableIntMap {
  val empty: ImmutableIntMap =
    new ImmutableIntMap(Array.emptyIntArray, Array.empty)

  private final val MaxScanLength = 10
}

/**
 * INTERNAL API
 * Specialized Map for primitive `Int` keys to avoid allocations (boxing).
 * Keys and values are backed by arrays and lookup is performed with binary
 * search. It's intended for rather small (<1000) maps.
 */
@InternalApi private[akka] final class ImmutableIntMap private (
  private val keys: Array[Int], private val values: Array[Int]) {

  final val size: Int = keys.length

  /**
   * Worst case `O(log n)`, allocation free.
   */
  def get(key: Int): Int = {
    val i = Arrays.binarySearch(keys, key)
    if (i >= 0) values(i)
    else Int.MinValue // cant use null, cant use OptionVal, other option is to throw an exception...
  }

  /**
   * Worst case `O(log n)`, allocation free.
   */
  def contains(key: Int): Boolean = {
    Arrays.binarySearch(keys, key) >= 0
  }

  def updateIfAbsent(key: Int, value: ⇒ Int): ImmutableIntMap = {
    if (contains(key))
      this
    else
      updated(key, value)
  }

  /**
   * Worst case `O(log n)`, creates new `ImmutableIntMap`
   * with copies of the internal arrays for the keys and
   * values.
   */
  def updated(key: Int, value: Int): ImmutableIntMap = {
    if (size == 0)
      new ImmutableIntMap(Array(key), Array(value))
    else {
      val i = Arrays.binarySearch(keys, key)
      if (i >= 0) {
        // existing key, replace value
        val newValues = new Array[Int](values.length)
        System.arraycopy(values, 0, newValues, 0, values.length)
        newValues(i) = value
        new ImmutableIntMap(keys, newValues)
      } else {
        // insert the entry at the right position, and keep the arrays sorted
        val j = -(i + 1)
        val newKeys = new Array[Int](size + 1)
        System.arraycopy(keys, 0, newKeys, 0, j)
        newKeys(j) = key
        System.arraycopy(keys, j, newKeys, j + 1, keys.length - j)

        val newValues = new Array[Int](size + 1)
        System.arraycopy(values, 0, newValues, 0, j)
        newValues(j) = value
        System.arraycopy(values, j, newValues, j + 1, values.length - j)

        new ImmutableIntMap(newKeys, newValues)
      }
    }
  }

  def remove(key: Int): ImmutableIntMap = {
    val i = Arrays.binarySearch(keys, key)
    if (i >= 0) {
      if (size == 1)
        ImmutableIntMap.empty
      else {
        val newKeys = new Array[Int](size - 1)
        System.arraycopy(keys, 0, newKeys, 0, i)
        System.arraycopy(keys, i + 1, newKeys, i, keys.length - i - 1)

        val newValues = new Array[Int](size - 1)
        System.arraycopy(values, 0, newValues, 0, i)
        System.arraycopy(values, i + 1, newValues, i, values.length - i - 1)

        new ImmutableIntMap(newKeys, newValues)
      }
    } else
      this
  }

  /**
   * All keys
   */
  def keysIterator: Iterator[Int] =
    keys.iterator

  override def toString: String =
    keysIterator.map(key ⇒ s"$key -> ${get(key)}").mkString("ImmutableIntMap(", ", ", ")")

  override def hashCode: Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, keys)
    result = HashCode.hash(result, values)
    result
  }

  override def equals(obj: Any): Boolean = obj match {
    case other: ImmutableIntMap ⇒
      if (other eq this) true
      else if (size != other.size) false
      else if (size == 0 && other.size == 0) true
      else {
        @tailrec def check(i: Int): Boolean = {
          if (i < 0) true
          else if (keys(i) == other.keys(i) && values(i) == other.values(i))
            check(i - 1) // recur, next elem
          else false
        }
        check(size - 1)
      }
    case _ ⇒ false
  }
}
