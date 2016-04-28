/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import scala.collection.immutable

/**
 * Mutable.
 *
 * Keeps a number of specific heavy hitters around in memory.
 *
 * See also Section 5.2 of http://dimacs.rutgers.edu/~graham/pubs/papers/cm-full.pdf
 * for a discussion about the assumptions made and guarantees about the Heavy Hitters made in this model.
 * We assume the Cash Register model in which there are only additions, which simplifies HH detecion significantly.
 */
sealed class HeavyHitters[T](strategy: HeavyHittersStrategy[T]) {
  // TODO technically could be optimised depending on strategy (priority queue?) (store in order of counts, drop last if exceeded size)
  private[this] var _items = List.empty[HeavyHitter[T]]

  def items: immutable.Set[T] = _items.map(_.item).toSet // TODO avoid the toSet?    

  /**
   * Attempt adding item to heavy hitters set, if it does not fit in the top yet,
   * it will be dropped and the method will return `false`.
   *
   * @return `true` if the added item is indeed a heavy hitter
   */
  def update(item: T, count: Long): Boolean = {
    // TODO optimise, should be just insertion and trim if too big
    _items.find(_.item == item) match {
      case Some(existing) ⇒
        println("existing = " + existing)
        // if item already was a heavy hitter, and since we are in an addition only mode, no need to check counts,
        // as the new count always will be at-least 1 more than previously (e.g.g new message arrived so we increment the count
        // and end up checking if it is a HH).
        _items.filterNot(_ == existing)
        _items ::= HeavyHitter(item, count)
        _items.sortBy(_.count) // TODO remove this, pick better structure  
        true
      case None ⇒
        // item wasn't yet a heavy hitter, thus we need to insert it and trim out any HH that do not fit the range anymore
        val incoming = HeavyHitter(item, count)
        _items ::= incoming
        _items = strategy.findAll(_items)
        _items contains incoming
    }
  }
}

/**
 * [[HeavyHitters]] that keeps the top `N` heavy hitters at any given point in time.
 *
 * Simpler to test and easier to expect exact amount of memory use, however may not be useful if some hitters dominate strongly
 * and afterwards no heavy hitter heavier than them arrives.
 */
final case class TopNHeavyHitters[T](n: Int)
  extends HeavyHitters[T](new TopNHeavyHittersStrategy(n))

final case class TopPercentageHeavyHitters[T](percentage: Double) extends HeavyHitters[T](new TopPercentageHeavyHittersStrategy(percentage))

final case class HeavyHitter[T](item: T, count: Long)

trait HeavyHittersStrategy[T] {
  def findAll(items: List[HeavyHitter[T]]): List[HeavyHitter[T]]
}

final class TopPercentageHeavyHittersStrategy[T](percentage: Double) extends HeavyHittersStrategy[T] {
  require(0.0 < percentage && percentage <= 1.0, s"percentage must be expressed as double within (0, 1> range.")
  def findAll(items: List[HeavyHitter[T]]): List[HeavyHitter[T]] = {
    val minCount = percentage * items.size // TODO items.size is not actually correct... is it?  
    println("any above minCount are accepted = " + minCount)
    items.filter(_.count >= minCount)
  }
}

final class TopNHeavyHittersStrategy[T](maxCount: Int) extends HeavyHittersStrategy[T] {
  def findAll(items: List[HeavyHitter[T]]): List[HeavyHitter[T]] = {
    items.sortBy(-_.count).take(maxCount)
  }
}
