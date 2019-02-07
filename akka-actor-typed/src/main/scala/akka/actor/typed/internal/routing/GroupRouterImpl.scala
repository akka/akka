/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.routing
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi
import akka.dispatch.forkjoin.ThreadLocalRandom

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object GroupRouterImpl {
  def apply[T](serviceKey: ServiceKey[T], logicFactory: () ⇒ RoutingLogic[T]): Behavior[T] =
    Behaviors.setup(ctx ⇒ new GroupRouterImpl[T](ctx, serviceKey, logicFactory()))
}

/**
 * INTERNAL API
 */
@InternalApi
private final class GroupRouterImpl[T](ctx: ActorContext[T], serviceKey: ServiceKey[T], routingLogic: RoutingLogic[T]) extends AbstractBehavior[T] {

  private var routees: Array[ActorRef[T]] = Array.empty[ActorRef[T]]

  // casting trix to avoid having to wrap incoming messages
  ctx.system.receptionist ! Receptionist.Subscribe(serviceKey, ctx.self.unsafeUpcast[Any].narrow[Receptionist.Listing])

  def onMessage(msg: T): Behavior[T] = msg match {
    case serviceKey.Listing(update) ⇒
      // we don't need to watch, because receptionist already does that
      val newRoutees = update.toArray
      if (newRoutees.nonEmpty) {
        // make sure we keep a somewhat similar order, but don't put all entries from
        // the same node next to each other
        newRoutees.sortBy(ref ⇒ (ref.path.toStringWithoutAddress, ref.path.address))
        routingLogic.routeesUpdated(routees, newRoutees)
      }
      routees = newRoutees
      Behavior.same
    case msg: T @unchecked ⇒
      if (routees.nonEmpty) {
        val selectedIdx = ThreadLocalRandom.current().nextInt(routees.length)
        routees(selectedIdx) ! msg
      } else {
        ctx.system.deadLetters ! msg
      }
      Behavior.same
  }

}
