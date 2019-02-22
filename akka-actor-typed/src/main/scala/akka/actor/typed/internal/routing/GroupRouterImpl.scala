/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.routing
import akka.actor.typed._
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.annotation.InternalApi
import akka.dispatch.forkjoin.ThreadLocalRandom

/**
 * Provides builder style configuration options for group routers while still being a behavior that can be spawned
 *
 * INTERNAL API
 */
@InternalApi
private[akka] final case class GroupRouterBuilder[T] private[akka] (
  key:          ServiceKey[T],
  logicFactory: () ⇒ RoutingLogic[T] = () ⇒ RoutingLogics.randomLogic[T]()
) extends javadsl.GroupRouter[T]
  with scaladsl.GroupRouter[T] {

  // deferred creation of the actual router
  def apply(ctx: TypedActorContext[T]): Behavior[T] = new GroupRouterImpl[T](ctx.asScala, key, logicFactory())

  def withRandomRouting(): GroupRouterBuilder[T] = copy(logicFactory = RoutingLogics.randomLogic[T])

  def withRoundRobinRouting(): GroupRouterBuilder[T] = copy(logicFactory = () ⇒ new RoutingLogics.RoundRobinLogic[T])

}

/**
 * INTERNAL API
 */
@InternalApi
private final class GroupRouterImpl[T](
  ctx:          ActorContext[T],
  serviceKey:   ServiceKey[T],
  routingLogic: RoutingLogic[T]
) extends AbstractBehavior[T] {

  private var routees: Array[ActorRef[T]] = Array.empty[ActorRef[T]]

  // casting trix to avoid having to wrap incoming messages - note that this will cause problems if intercepting
  // messages to a router
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
      this
    case msg: T @unchecked ⇒
      if (routees.nonEmpty) {
        val selectedIdx = ThreadLocalRandom.current().nextInt(routees.length)
        routees(selectedIdx) ! msg
      } else {
        ctx.system.deadLetters ! msg
      }
      this
  }
}
