/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery.compress

import java.util.Objects

import scala.annotation.{ switch, tailrec }
import scala.reflect.ClassTag

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
private[remote] final class TopHeavyHitters[T >: Null](val max: Int)(implicit classTag: ClassTag[T]) {

  require((max & (max - 1)) == 0, "Maximum numbers of heavy hitters should be in form of 2^k for any natural k")

  val capacity = max * 2
  val mask = capacity - 1

  import TopHeavyHitters._

  private[this] val hashes: Array[Int] = Array.ofDim(capacity)
  private[this] val items: Array[T] = Array.ofDim[T](capacity)
  private[this] val weights: Array[Long] = Array.ofDim(capacity)
  private[this] val heapIndex: Array[Int] = Array.fill(capacity)(-1)
  private[this] val heap: Array[Int] = Array.fill(max)(-1)

  // TODO think if we could get away without copy
  /** Returns the current heavy hitters, order is not of significance */
  def snapshot: Array[T] = {
    val snap = Array.ofDim(max).asInstanceOf[Array[T]]
    var i = 0
    while (i < max) {
      val index = heap(i)
      val value =
        if (index < 0) null
        else items(index)
      snap(i) = value
      i += 1
    }
    snap
  }

  def toDebugString =
    s"""TopHeavyHitters(
        |  max: $max,
        |  lowestHitterIdx: $lowestHitterIndex (weight: $lowestHitterWeight)
        |
        |  hashes:      ${hashes.toList.mkString("[", ", ", "]")}
        |  weights:     ${weights.toList.mkString("[", ", ", "]")}
        |  items:       ${items.toList.mkString("[", ", ", "]")}
        |  heapIndex:   ${heapIndex.toList.mkString("[", ", ", "]")}
        |  heap:   ${heap.toList.mkString("[", ", ", "]")}
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
      val startIndex = hashCode.get & mask
      (findHashIdx(startIndex, hashCode): @switch) match { // worst case O(n), common O(1 + alpha), can't really bin search here since indexes are kept in synch with other arrays hmm...
        case -1 ⇒
          // not previously heavy hitter
          insertKnownNewHeavy(hashCode, item, count) // O(log n + alpha)
          true
        case potentialIndexGuess ⇒
          // the found index could be one of many which hash to the same value (we're using open-addressing),
          // so it is only used as hint for the replace call. If the value matches, we're good, if not we need to search from here onwards.
          val actualIdx = findItemIdx(potentialIndexGuess, hashCode, item)

          if (actualIdx == -1) {
            insertKnownNewHeavy(hashCode, item, count) // O(1 + log n), we simply replace the current lowest heavy hitter
            true
          } else replaceExistingHeavyHitter(actualIdx, hashCode, item, count) // usually O(1), worst case O(n) if we need to scan due to hash conflicts
      }
    }

  def isHeavy(count: Long): Boolean =
    count > lowestHitterWeight

  private def findItemIdx(searchFromIndex: Int, hashCode: HashCodeVal, o: T): Int = {
    @tailrec def loop(index: Int, start: Int, hashCodeVal: HashCodeVal, o: T): Int = {
      if (index == start) -1
      else if (hashCodeVal.get == hashes(index)) {
        val item: T = items(index)
        if (item == o) {
          index
        } else {
          loop((index + 1) & mask, start, hashCodeVal, o)
        }
      } else {
        loop((index + 1) & mask, start, hashCodeVal, o)
      }
    }

    if (searchFromIndex == -1) -1
    else if (Objects.equals(items(searchFromIndex), o)) searchFromIndex
    else loop((searchFromIndex + 1) & mask, searchFromIndex, hashCode, o)
  }

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
      updateCount(foundHashIndex, count) // we don't need to change `hashCode` or `item`, those remain the same
      fixHeap(heapIndex(foundHashIndex))
      false // not a "new" heavy hitter, since we only replaced it (so it was signaled as new once before)
    } else replaceExistingHeavyHitter(findHashIdx(foundHashIndex + 1, hashCode), hashCode, item, count) // recurse

  private def findHashIdx(searchFromIndex: Int, hashCode: HashCodeVal): Int =
    findEqIndex(hashes, searchFromIndex, hashCode.get)

  /**
   * Fix heap property on `heap` array
   * @param index place to check and fix
   */
  @tailrec
  private def fixHeap(index: Int): Unit = {
    val leftIndex = index * 2 + 1
    val rightIndex = index * 2 + 2
    val currentWeights: Long = weights(heap(index))
    if (rightIndex < max) {
      val leftValueIndex: Int = heap(leftIndex)
      val rightValueIndex: Int = heap(rightIndex)
      if (leftValueIndex < 0) {
        swapHeapNode(index, leftIndex)
        fixHeap(leftIndex)
      } else if (rightValueIndex < 0) {
        swapHeapNode(index, rightIndex)
        fixHeap(rightIndex)
      } else {
        val rightWeights: Long = weights(rightValueIndex)
        val leftWeights: Long = weights(leftValueIndex)
        if (leftWeights < rightWeights) {
          if (currentWeights > leftWeights) {
            swapHeapNode(index, leftIndex)
            fixHeap(leftIndex)
          }
        } else {
          if (currentWeights > rightWeights) {
            swapHeapNode(index, rightIndex)
            fixHeap(rightIndex)
          }
        }
      }
    } else if (leftIndex < max) {
      val leftValueIndex: Int = heap(leftIndex)
      if (leftValueIndex < 0) {
        swapHeapNode(index, leftIndex)
        fixHeap(leftIndex)
      } else {
        val leftWeights: Long = weights(leftValueIndex)
        if (currentWeights > leftWeights) {
          swapHeapNode(index, leftIndex)
          fixHeap(leftIndex)
        }
      }
    }
  }

  /**
   * Swaps two elements in `heap` array and maintain correct index in `heapIndex`.
   *
   * @param a index of first element
   * @param b index of second element
   */
  private def swapHeapNode(a: Int, b: Int): Unit = {
    if (heap(a) >= 0) {
      heapIndex(heap(a)) = b
    }
    if (heap(b) >= 0) {
      heapIndex(heap(b)) = a
    }
    val temp = heap(a)
    heap(a) = heap(b)
    heap(b) = temp
  }

  /**
   * Puts the item and additional information into the index of the current lowest hitter.
   *
   * @return index at which the insertion was performed
   */
  private def insertKnownNewHeavy(hashCode: HashCodeVal, item: T, count: Long): Unit = {
    removeHash(lowestHitterIndex)
    lowestHitterIndex = insert(hashCode, item, count)
  }

  /**
   * Remove value from hash-table based on position.
   *
   * @param index position to remove
   */
  private def removeHash(index: Int): Unit = {
    if (index > 0) {
      items(index) = null
      hashes(index) = 0
      weights(index) = 0
    }
  }

  /**
   * Only update the count for a given index, e.g. if value and hashCode remained the same.
   */
  private def updateCount(idx: Int, count: Long): Unit =
    weights(idx) = count

  /**
   * Insert value in hash-table.
   *
   * Using open addressing for resolving collisions.
   * Initial index is reminder in division hashCode and table size.
   *
   * @param hashCode hashCode of item
   * @param item     value which should be added to hash-table
   * @param count    count associated to value
   * @return Index in hash-table where was inserted
   */
  private def insert(hashCode: HashCodeVal, item: T, count: Long): Int = {
    var index: Int = hashCode.get & mask
    while (items(index) != null) {
      index = (index + 1) & mask
    }
    hashes(index) = hashCode.get
    items(index) = item
    weights(index) = count
    index
  }

  /** Weight of lowest heavy hitter, if a new inserted item has a weight greater than this it is a heavy hitter. */
  def lowestHitterWeight: Long = {
    val index: Int = lowestHitterIndex
    if (index > 0) {
      weights(index)
    } else {
      0
    }

  }

  private def lowestHitterIndex: Int = {
    heap(0)
  }

  private def lowestHitterIndex_=(index: Int): Unit = {
    heap(0) = index
    heapIndex(index) = 0
    fixHeap(0)
  }

  private def findEqIndex(hashes: Array[Int], searchFromIndex: Int, hashCode: Int): Int = {
    var i: Int = 0
    while (i < hashes.length) {
      val index = (i + searchFromIndex) & mask
      if (hashes(index) == hashCode) {
        return index
      }
      i += 1
    }
    -1
  }

  override def toString =
    s"${getClass.getSimpleName}(max:$max)"
}

/**
 * INTERNAL API
 */
private[remote] object TopHeavyHitters {

  /** Value class to avoid mixing up count and hashCode in APIs. */
  private[compress] final class HashCodeVal(val get: Int) extends AnyVal {
    def isEmpty = false
  }

}
