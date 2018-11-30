/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.typed

import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Props, SupervisorStrategy}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.Singleton

import scala.concurrent.duration._

object SingletonCompileOnlySpec {

  val system = ActorSystem(Behaviors.empty, "Singleton")

  //#counter
  trait CounterCommand
  case object Increment extends CounterCommand
  final case class GetValue(replyTo: ActorRef[Int]) extends CounterCommand
  case object GoodByeCounter extends CounterCommand

  def counter(entityId: String, value: Int): Behavior[CounterCommand] =
    Behaviors.receiveMessage[CounterCommand] {
      case Increment ⇒
        counter(entityId, value + 1)
      case GetValue(replyTo) ⇒
        replyTo ! value
        Behaviors.same
      case GoodByeCounter ⇒
        Behaviors.stopped
    }
  //#counter

  //#singleton
  import akka.cluster.typed.ClusterSingleton

  val singletonManager = ClusterSingleton(system)
  // Start if needed and provide a proxy to a named singleton
  val proxy: ActorRef[CounterCommand] = singletonManager.init(
    Singleton("GlobalCounter",
    Behaviors.supervise(counter("TheCounter", 0))
      .onFailure[Exception](SupervisorStrategy.restart),
    ).withStopMessage(GoodByeCounter)
  )

  proxy ! Increment
  //#singleton

  //#backoff
  val proxyBackOff: ActorRef[CounterCommand] = singletonManager.init(
    Singleton("GlobalCounter",
    Behaviors.supervise(counter("TheCounter", 0))
      .onFailure[Exception](SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2)))
        .withStopMessage(GoodByeCounter)
  )
  //#backoff
}
