/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery.compress

import java.util
import java.util.Objects

import akka.japi.Util

import scala.annotation.{ switch, tailrec }
import scala.collection.immutable

/**
 * INTERNAL API
 *
 * Mutable, open-addressing with linear-probing (though naive one which in theory could get pathological) heavily optimised "top N heavy hitters" data-structure.
 *
 * Keeps a number of specific heavy hitters around in memory.
 *
 * See also Section 5.2 of http://dimacs.rutgers.edu/~graham/pubs/papers/cm-full.pdf
 * for a discussion about the assumptions made and guarantees about the Heavy Hitters made in this model.
 * We assume the Cash Register model in which there are only additions, which simplifies HH detecion significantly.
 */
private[remote] final class TopHeavyHitters[T](val max: Int) {
  import TopHeavyHitters._
  private[this] var _lowestHitterIdx: Int = 0

  private[this] val hashes: Array[Int] = Array.ofDim(max)
  private[this] val items: Array[T] = Array.ofDim[Object](max).asInstanceOf[Array[T]]
  private[this] val weights: Array[Long] = Array.ofDim(max)

  // TODO think if we could get away without copy
  /** Returns copy(!) of items which are currently considered to be heavy hitters. */
  def snapshot: Array[T] = {
    val snap = Array.ofDim(max).asInstanceOf[Array[T]]
    System.arraycopy(items, 0, snap, 0, items.length)
    snap
  }

  def toDebugString =
    s"""TopHeavyHitters(
       |  max: $max,
       |  lowestHitterIdx: $lowestHitterIdx (weight: $lowestHitterWeight)
       |  
       |  hashes:  ${hashes.toList.mkString("[", ", ", "]")}
       |  weights: ${weights.toList.mkString("[", ", ", "]")}
       |  items:   ${items.toList.mkString("[", ", ", "]")}
       |)""".stripMargin

  /**
   * Attempt adding item to heavy hitters set, if it does not fit in the top yet,
   * it will be dropped and the method will return `false`.
   *
   * @return `true` if the added item has become a heavy hitter.
   */
  // TODO possibly can be optimised further? (there is a benchmark)
  def update(item: T, count: Long): Boolean =
    isHeavy(count) && { // O(1) terminate execution ASAP if known to not be a heavy hitter anyway 
      val hashCode = new HashCodeVal(item.hashCode()) // avoid re-calculating hashCode
      (findHashIdx(0, hashCode): @switch) match { // worst case O(n), can't really bin search here since indexes are kept in synch with other arrays hmm...  
        case -1 ⇒
          // not previously heavy hitter
          insertKnownNewHeavy(hashCode, item, count) // O(1) + rarely O(n) if needs to update lowest hitter

        case potentialIndexGuess ⇒
          // the found index could be one of many which hash to the same value (we're using open-addressing),
          // so it is only used as hint for the replace call. If the value matches, we're good, if not we need to search from here onwards.  
          val actualIdx = findItemIdx(potentialIndexGuess, hashCode, item)

          if (actualIdx == -1) insertKnownNewHeavy(hashCode, item, count) // O(1) + O(n), we simply replace the current lowest heavy hitter
          else replaceExistingHeavyHitter(actualIdx, hashCode, item, count) // usually O(1), worst case O(n) if we need to scan due to hash conflicts
      }
    }

  def isHeavy(count: Long): Boolean =
    count > lowestHitterWeight

  @tailrec private def findItemIdx(searchFromIndex: Int, hashCode: HashCodeVal, o: T): Int =
    if (searchFromIndex == -1) -1
    else if (Objects.equals(items(searchFromIndex), o)) searchFromIndex
    else findItemIdx(findHashIdx(searchFromIndex + 1, hashCode), hashCode, o)

  /**
   * Replace existing heavy hitter – give it a new `count` value.
   * If it was the lowest heavy hitter we update the `_lowestHitterIdx` as well, otherwise there is no need to.
   *
   * @return `false` to indicate "no, this insertion did not make this item a new heavy hitter" if update was successful,
   *         otherwise might throw [[NoSuchElementException]] if the `item` actually was not found
   */
  @tailrec private def replaceExistingHeavyHitter(foundHashIndex: Int, hashCode: HashCodeVal, item: T, count: Long): Boolean =
    if (foundHashIndex == -1) throw new NoSuchElementException(s"Item $item is not present in HeavyHitters, can not replace it!")
    else if (Objects.equals(items(foundHashIndex), item)) {
      putCount(foundHashIndex, count) // we don't need to change `hashCode` or `item`, those remain the same
      if (foundHashIndex == lowestHitterIdx) updateLowestHitterIdx() // need to update the lowestHitter since we just bumped its count 
      false // not a "new" heavy hitter, since we only replaced it (so it was signaled as new once before) 
    } else replaceExistingHeavyHitter(findHashIdx(foundHashIndex + 1, hashCode), hashCode, item, count) // recurse

  private def findHashIdx(searchFromIndex: Int, hashCode: HashCodeVal): Int =
    findEqIndex(hashes, searchFromIndex, hashCode.get)

  /**
   * Puts the item and additional information into the index of the current lowest hitter.
   *
   * @return index at which the insertion was performed
   */
  private def insertKnownNewHeavy(hashCode: HashCodeVal, item: T, count: Long): Boolean = {
    put(_lowestHitterIdx, hashCode, item, count)
    updateLowestHitterIdx()
    true
  }

  /**
   * Only update the count for a given index, e.g. if value and hashCode remained the same.
   */
  private def putCount(idx: Int, count: Long): Unit =
    weights(idx) = count

  private def put(idx: Int, hashCode: HashCodeVal, item: T, count: Long): Unit = {
    hashes(idx) = hashCode.get
    items(idx) = item
    weights(idx) = count
  }

  /** Perform a scan for the lowest hitter (by weight). */
  private def updateLowestHitterIdx(): Int = {
    _lowestHitterIdx = findIndexOfMinimum(weights)
    _lowestHitterIdx
  }

  /** Weight of lowest heavy hitter, if a new inserted item has a weight greater than this it is a heavy hitter. */
  def lowestHitterWeight: Long =
    weights(_lowestHitterIdx)

  // do not expose we're array based
  private def lowestHitterIdx: Int =
    _lowestHitterIdx

  private def findEqIndex(hashes: Array[Int], searchFromIndex: Int, hashCode: Int): Int = {
    var i: Int = searchFromIndex
    while (i < hashes.length) {
      if (hashes(i) == hashCode) return i
      i += 1
    }
    -1
  }

  private def findIndexOfMinimum(weights: Array[Long]): Int = {
    var _lowestHitterIdx: Int = -1
    var min: Long = Long.MaxValue
    var i: Int = 0
    while (i < weights.length) {
      if (weights(i) < min) {
        min = weights(i)
        _lowestHitterIdx = i
      }
      i += 1
    }
    _lowestHitterIdx
  }

  override def toString =
    s"${getClass.getSimpleName}(max:$max)"
}

object TopHeavyHitters {
  /** Value class to avoid mixing up count and hashCode in APIs. */
  private[compress] final class HashCodeVal(val get: Int) extends AnyVal {
    def isEmpty = false
  }
}
