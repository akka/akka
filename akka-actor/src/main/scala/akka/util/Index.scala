/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

import annotation.tailrec

import java.util.concurrent.{ ConcurrentSkipListSet, ConcurrentHashMap }
import java.util.{ Comparator, Set ⇒ JSet }
import scala.collection.mutable

/**
 * An implementation of a ConcurrentMultiMap
 * Adds/remove is serialized over the specified key
 * Reads are fully concurrent <-- el-cheapo
 */
class Index[K, V](val mapSize: Int, val valueComparator: Comparator[V]) {

  def this(mapSize: Int, cmp: (V, V) ⇒ Int) = this(mapSize, new Comparator[V] {
    def compare(a: V, b: V): Int = cmp(a, b)
  })

  private val container = new ConcurrentHashMap[K, JSet[V]](mapSize)
  private val emptySet = new ConcurrentSkipListSet[V]

  /**
   * Associates the value of type V with the key of type K
   * @return true if the value didn't exist for the key previously, and false otherwise
   */
  def put(key: K, value: V): Boolean = {
    //Tailrecursive spin-locking put
    @tailrec
    def spinPut(k: K, v: V): Boolean = {
      var retry = false
      var added = false
      val set = container get k

      if (set ne null) {
        set.synchronized {
          if (set.isEmpty) retry = true //IF the set is empty then it has been removed, so signal retry
          else { //Else add the value to the set and signal that retry is not needed
            added = set add v
            retry = false
          }
        }
      } else {
        val newSet = new ConcurrentSkipListSet[V](valueComparator)
        newSet add v

        // Parry for two simultaneous putIfAbsent(id,newSet)
        val oldSet = container.putIfAbsent(k, newSet)
        if (oldSet ne null) {
          oldSet.synchronized {
            if (oldSet.isEmpty) retry = true //IF the set is empty then it has been removed, so signal retry
            else { //Else try to add the value to the set and signal that retry is not needed
              added = oldSet add v
              retry = false
            }
          }
        } else added = true
      }

      if (retry) spinPut(k, v)
      else added
    }

    spinPut(key, value)
  }

  /**
   * @return Some(value) for the first matching value where the supplied function returns true for the given key,
   * if no matches it returns None
   */
  def findValue(key: K)(f: (V) ⇒ Boolean): Option[V] = {
    import scala.collection.JavaConversions._
    val set = container get key
    if (set ne null) set.iterator.find(f)
    else None
  }

  /**
   * Returns an Iterator of V containing the values for the supplied key, or an empty iterator if the key doesn't exist
   */
  def valueIterator(key: K): scala.Iterator[V] = {
    container.get(key) match {
      case null ⇒ Iterator.empty
      case some ⇒ scala.collection.JavaConversions.asScalaIterator(some.iterator())
    }
  }

  /**
   * Applies the supplied function to all keys and their values
   */
  def foreach(fun: (K, V) ⇒ Unit) {
    import scala.collection.JavaConversions._
    container.entrySet foreach { e ⇒ e.getValue.foreach(fun(e.getKey, _)) }
  }

  /**
   * Returns the union of all value sets.
   */
  def values: Set[V] = {
    import scala.collection.JavaConversions._
    val builder = mutable.Set.empty[V]
    for {
      entry ← container.entrySet
      v ← entry.getValue
    } builder += v
    builder.toSet
  }

  /**
   * Returns the key set.
   */
  def keys = scala.collection.JavaConversions.collectionAsScalaIterable(container.keySet)

  /**
   * Disassociates the value of type V from the key of type K
   * @return true if the value was disassociated from the key and false if it wasn't previously associated with the key
   */
  def remove(key: K, value: V): Boolean = {
    val set = container get key

    if (set ne null) {
      set.synchronized {
        if (set.remove(value)) { //If we can remove the value
          if (set.isEmpty) //and the set becomes empty
            container.remove(key, emptySet) //We try to remove the key if it's mapped to an empty set

          true //Remove succeeded
        } else false //Remove failed
      }
    } else false //Remove failed
  }

  /**
   * Disassociates all the values for the specified key
   * @return None if the key wasn't associated at all, or Some(scala.Iterable[V]) if it was associated
   */
  def remove(key: K): Option[Iterable[V]] = {
    val set = container get key

    if (set ne null) {
      set.synchronized {
        container.remove(key, set)
        Some(scala.collection.JavaConverters.collectionAsScalaIterableConverter(set).asScala)
      }
    } else None //Remove failed
  }

  /**
   * Removes the specified value from all keys
   */
  def removeValue(value: V): Unit = {
    val i = container.entrySet().iterator()
    while (i.hasNext) {
      val e = i.next()
      val set = e.getValue()

      if (set ne null) {
        set.synchronized {
          if (set.remove(value)) { //If we can remove the value
            if (set.isEmpty) //and the set becomes empty
              container.remove(e.getKey, emptySet) //We try to remove the key if it's mapped to an empty set
          }
        }
      }
    }
  }

  /**
   * @return true if the underlying containers is empty, may report false negatives when the last remove is underway
   */
  def isEmpty: Boolean = container.isEmpty

  /**
   *  Removes all keys and all values
   */
  def clear(): Unit = {
    val i = container.entrySet().iterator()
    while (i.hasNext) {
      val e = i.next()
      val set = e.getValue()
      if (set ne null) { set.synchronized { set.clear(); container.remove(e.getKey, emptySet) } }
    }
  }
}

/**
 * An implementation of a ConcurrentMultiMap
 * Adds/remove is serialized over the specified key
 * Reads are fully concurrent <-- el-cheapo
 */
class ConcurrentMultiMap[K, V](mapSize: Int, valueComparator: Comparator[V]) extends Index[K, V](mapSize, valueComparator)
