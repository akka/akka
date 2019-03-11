/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed.supervision

import akka.actor.typed.ActorRef
import akka.actor.typed.{ Behavior, SupervisorStrategy }
import akka.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration._

import akka.actor.TypedActor.PreRestart

object SupervisionCompileOnly {

  val behavior = Behaviors.empty[String]

  //#restart
  Behaviors.supervise(behavior).onFailure[IllegalStateException](SupervisorStrategy.restart)
  //#restart

  //#resume
  Behaviors.supervise(behavior).onFailure[IllegalStateException](SupervisorStrategy.resume)
  //#resume

  //#restart-limit
  Behaviors
    .supervise(behavior)
    .onFailure[IllegalStateException](
      SupervisorStrategy.restart.withLimit(maxNrOfRetries = 10, withinTimeRange = 10.seconds))
  //#restart-limit

  //#multiple
  Behaviors
    .supervise(Behaviors.supervise(behavior).onFailure[IllegalStateException](SupervisorStrategy.restart))
    .onFailure[IllegalArgumentException](SupervisorStrategy.stop)
  //#multiple

  //#wrap
  sealed trait Command
  case class Increment(nr: Int) extends Command
  case class GetCount(replyTo: ActorRef[Int]) extends Command

  def counter(count: Int): Behavior[Command] = Behaviors.receiveMessage[Command] {
    case Increment(nr: Int) =>
      counter(count + nr)
    case GetCount(replyTo) =>
      replyTo ! count
      Behaviors.same
  }
  //#wrap

  //#top-level
  Behaviors.supervise(counter(1))
  //#top-level

  //#restart-stop-children
  def child(size: Long): Behavior[String] =
    Behaviors.receiveMessage(msg => child(size + msg.length))

  def parent: Behavior[String] = {
    Behaviors
      .supervise[String] {
        Behaviors.setup { ctx =>
          val child1 = ctx.spawn(child(0), "child1")
          val child2 = ctx.spawn(child(0), "child2")

          Behaviors.receiveMessage[String] { msg =>
            // there might be bugs here...
            val parts = msg.split(" ")
            child1 ! parts(0)
            child2 ! parts(1)
            Behaviors.same
          }
        }
      }
      .onFailure(SupervisorStrategy.restart)
  }
  //#restart-stop-children

  //#restart-keep-children
  def parent2: Behavior[String] = {
    Behaviors.setup { ctx =>
      val child1 = ctx.spawn(child(0), "child1")
      val child2 = ctx.spawn(child(0), "child2")

      // supervision strategy inside the setup to not recreate children on restart
      Behaviors
        .supervise {
          Behaviors.receiveMessage[String] { msg =>
            // there might be bugs here...
            val parts = msg.split(" ")
            child1 ! parts(0)
            child2 ! parts(1)
            Behaviors.same
          }
        }
        .onFailure(SupervisorStrategy.restart.withStopChildren(false))
    }
  }
  //#restart-keep-children
}
