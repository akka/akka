/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.routing

import java.util.concurrent.ThreadLocalRandom

import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.actor.typed.internal.routing.RoutingLogics.ConsistentHashingLogic.ConsistentHashMapping
import akka.annotation.InternalApi
import akka.routing.ConsistentHash
import akka.serialization.SerializationExtension
import akka.actor.typed.scaladsl.adapter._

import scala.util.control.NonFatal

/**
 * Kept in the behavior, not shared between instances, meant to be stateful.
 *
 * INTERNAL API
 */
@InternalApi
sealed private[akka] trait RoutingLogic[T] {

  def selectRoutee(msg: T): ActorRef[T]

  /**
   * Invoked an initial time before `selectRoutee` is ever called and then every time the set of available
   * routees changes.
   *
   * @param newRoutees The updated set of routees. For a group router this could be empty, in that case
   *                   `selectRoutee()` will not be called before `routeesUpdated` is invoked again with at
   *                   least one routee. For a pool the pool stops instead of ever calling `routeesUpdated`
   *                   with an empty list of routees.
   */
  def routeesUpdated(newRoutees: Set[ActorRef[T]]): Unit
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object RoutingLogics {

  final class RoundRobinLogic[T] extends RoutingLogic[T] {

    private var currentRoutees: Array[ActorRef[T]] = _

    private var nextIdx = 0

    def selectRoutee(msg: T): ActorRef[T] = {
      if (nextIdx >= currentRoutees.length) nextIdx = 0
      val selected = currentRoutees(nextIdx)
      nextIdx += 1
      selected
    }

    override def routeesUpdated(newRoutees: Set[ActorRef[T]]): Unit = {
      // make sure we keep a somewhat similar order so we can potentially continue roundrobining
      // from where we were unless the set of routees completely changed
      // Also, avoid putting all entries from the same node next to each other in case of cluster
      val sortedNewRoutees = newRoutees.toArray.sortBy(ref => (ref.path.toStringWithoutAddress, ref.path.address))

      if (currentRoutees ne null) {
        val firstDiffIndex = {
          var idx = 0
          while (idx < currentRoutees.length &&
                 idx < sortedNewRoutees.length &&
                 currentRoutees(idx) == sortedNewRoutees(idx)) {
            idx += 1
          }
          idx
        }
        if (nextIdx > firstDiffIndex) nextIdx -= 1
      }
      currentRoutees = sortedNewRoutees
    }
  }

  final class RandomLogic[T] extends RoutingLogic[T] {

    private var currentRoutees: Array[ActorRef[T]] = _

    override def selectRoutee(msg: T): ActorRef[T] = {
      val selectedIdx = ThreadLocalRandom.current().nextInt(currentRoutees.length)
      currentRoutees(selectedIdx)
    }

    override def routeesUpdated(newRoutees: Set[ActorRef[T]]): Unit = {
      currentRoutees = newRoutees.toArray
    }
  }

  final class ConsistentHashingLogic[T](
      virtualNodesFactor: Int,
      mapping: ConsistentHashMapping[T],
      system: ActorSystem[T])
      extends RoutingLogic[T] {
    require(system ne null, "system argument of ConsistentHashingLogic cannot be null.")

    private var currentRoutees: Set[ActorRef[T]] = Set.empty

    private var consistentHash: ConsistentHash[ActorRef[T]] = ConsistentHash(currentRoutees, virtualNodesFactor)

    override def selectRoutee(msg: T): ActorRef[T] =
      try {
        mapping(msg) match {
          case bytes: Array[Byte] => consistentHash.nodeFor(bytes)
          case str: String        => consistentHash.nodeFor(str)
          case x: AnyRef =>
            val bytes = SerializationExtension(system.toClassic).serialize(x).get
            consistentHash.nodeFor(bytes)
        }
      } catch {
        case NonFatal(e) =>
          system.log.warn("Couldn't route message [{}] due to [{}]", msg, e.getMessage)
          system.deadLetters
      }

    override def routeesUpdated(newRoutees: Set[ActorRef[T]]): Unit = {
      val withoutOld = currentRoutees.diff(newRoutees).foldLeft(consistentHash)(_ :- _)
      consistentHash = newRoutees.diff(currentRoutees).foldLeft(withoutOld)(_ :+ _)
      currentRoutees = newRoutees
    }
  }

  object ConsistentHashingLogic {
    type ConsistentHashMapping[T] = PartialFunction[T, Any]

    def emptyHashMapping[T]: ConsistentHashMapping[T] = new ConsistentHashMapping[T] {
      override def isDefinedAt(x: T): Boolean = false

      override def apply(v1: T): Any = throw new UnsupportedOperationException("Empty ConsistentHashMapping[T] apply()")
    }
  }

}
