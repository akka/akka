/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed.supervision

import akka.actor.typed.ActorRef
import akka.actor.typed.{ Behavior, SupervisorStrategy }
import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration._

object SupervisionCompileOnly {

  val behavior = Behaviors.empty[String]

  //#restart
  Behaviors.supervise(behavior)
    .onFailure[IllegalStateException](SupervisorStrategy.restart)
  //#restart

  //#resume
  Behaviors.supervise(behavior)
    .onFailure[IllegalStateException](SupervisorStrategy.resume)
  //#resume

  //#restart-limit
  Behaviors.supervise(behavior)
    .onFailure[IllegalStateException](SupervisorStrategy.restartWithLimit(
      maxNrOfRetries = 10, withinTimeRange = 10.seconds
    ))
  //#restart-limit

  //#multiple
  Behaviors.supervise(Behaviors.supervise(behavior)
    .onFailure[IllegalStateException](SupervisorStrategy.restart))
    .onFailure[IllegalArgumentException](SupervisorStrategy.stop)
  //#multiple

  //#wrap
  sealed trait Command
  case class Increment(nr: Int) extends Command
  case class GetCount(replyTo: ActorRef[Int]) extends Command

  def counter(count: Int): Behavior[Command] = Behaviors.receiveMessage[Command] {
    case Increment(nr: Int) ⇒
      counter(count + nr)
    case GetCount(replyTo) ⇒
      replyTo ! count
      Behaviors.same
  }
  //#wrap

  //#top-level
  Behaviors.supervise(counter(1))
  //#top-level
}
