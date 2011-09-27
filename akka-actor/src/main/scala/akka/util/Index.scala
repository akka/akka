/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

import annotation.tailrec

import java.util.concurrent.{ ConcurrentSkipListSet, ConcurrentHashMap }
import java.util.{ Set ⇒ JSet }

/**
 * An implementation of a ConcurrentMultiMap
 * Adds/remove is serialized over the specified key
 * Reads are fully concurrent <-- el-cheapo
 *
 * @author Viktor Klang
 */
class Index[K <: AnyRef, V <: AnyRef: Manifest] {
  private val Naught = Array[V]() //Nil for Arrays
  private val container = new ConcurrentHashMap[K, JSet[V]]
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
        val newSet = new ConcurrentSkipListSet[V]
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
   * @return a _new_ array of all existing values for the given key at the time of the call
   */
  def values(key: K): Array[V] = {
    val set: JSet[V] = container get key
    val result = if (set ne null) set toArray Naught else Naught
    result.asInstanceOf[Array[V]]
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
   * Applies the supplied function to all keys and their values
   */
  def foreach(fun: (K, V) ⇒ Unit) {
    import scala.collection.JavaConversions._
    container.entrySet foreach { e ⇒ e.getValue.foreach(fun(e.getKey, _)) }
  }

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
   * @return true if the underlying containers is empty, may report false negatives when the last remove is underway
   */
  def isEmpty: Boolean = container.isEmpty

  /**
   *  Removes all keys and all values
   */
  def clear = foreach { case (k, v) ⇒ remove(k, v) }
}
