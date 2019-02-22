/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.routing
import akka.actor.typed.ActorRef
import akka.annotation.InternalApi
import akka.dispatch.forkjoin.ThreadLocalRandom

/**
 * Kept in the behavior, not shared between instances so is allowed to be stateful/mutable.
 *
 * INTERNAL API
 */
@InternalApi
sealed private[akka] trait RoutingLogic[T] {
  /**
   * @param routees available routees, will contain at least one element. Must not be mutated by select logic.
   */
  def selectRoutee(routees: Array[ActorRef[T]]): ActorRef[T]

  /**
   * Invoked when a routee is stopping and get removed from the router, in case the logic needs to update
   * its state because of that. Will only be invoked as long as there are elements in `newRoutees`, but
   * when used in a group router `originalRoutees` may be empty.
   *
   * Neither array may be mutated by the logic.
   */
  def routeesUpdated(originalRoutees: Array[ActorRef[T]], newRoutees: Array[ActorRef[T]]): Unit = {}
}

/**
 * INTERNAL API
 */
@InternalApi
object RoutingLogics {

  final class RoundRobinLogic[T] extends RoutingLogic[T] {

    private var nextIdx = 0

    def selectRoutee(routees: Array[ActorRef[T]]): ActorRef[T] = {
      if (nextIdx >= routees.length) nextIdx = 0
      val selected = routees(nextIdx)
      nextIdx += 1
      selected
    }

    override def routeesUpdated(originalRoutees: Array[ActorRef[T]], newRoutees: Array[ActorRef[T]]): Unit = {
      val firstDiffIndex = {
        var idx = 0
        while (idx < originalRoutees.length &&
          idx < newRoutees.length &&
          originalRoutees(idx) == newRoutees(idx)
        ) {
          idx += 1
        }
        idx
      }

      if (nextIdx > firstDiffIndex) nextIdx -= 1
    }
  }

  private object RandomLogic extends RoutingLogic[Nothing] {
    def selectRoutee(routees: Array[ActorRef[Nothing]]): ActorRef[Nothing] = {
      val selectedIdx = ThreadLocalRandom.current().nextInt(routees.length)
      routees(selectedIdx)
    }
  }

  def randomLogic[T](): RoutingLogic[T] = RandomLogic.asInstanceOf[RoutingLogic[T]]
}
