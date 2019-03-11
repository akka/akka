/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.routing

import akka.actor.typed.ActorRef
import akka.annotation.InternalApi
import akka.dispatch.forkjoin.ThreadLocalRandom

/**
 * Kept in the behavior, not shared between instances, meant to be stateful.
 *
 * INTERNAL API
 */
@InternalApi
sealed private[akka] trait RoutingLogic[T] {

  /**
   * @param routees available routees, will contain at least one element. Must not be mutated by select logic.
   */
  def selectRoutee(): ActorRef[T]

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

    def selectRoutee(): ActorRef[T] = {
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

    override def selectRoutee(): ActorRef[T] = {
      val selectedIdx = ThreadLocalRandom.current().nextInt(currentRoutees.length)
      currentRoutees(selectedIdx)
    }
    override def routeesUpdated(newRoutees: Set[ActorRef[T]]): Unit = {
      currentRoutees = newRoutees.toArray
    }

  }

}
