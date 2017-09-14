/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster

import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.AskPattern._
import akka.typed.scaladsl.adapter._
import akka.typed.{ ActorRef, Props }
import akka.util.Timeout
import scala.concurrent.duration._

object ClusterSingletonManagerApiSpec {

  trait PingProtocol
  case object Pong
  case class Ping(respondTo: ActorRef[Pong.type]) extends PingProtocol

  case object Perish extends PingProtocol

  val pingPong = Actor.immutable[PingProtocol] { (ctx, msg) ⇒

    msg match {
      case Ping(respondTo) ⇒
        respondTo ! Pong
        Actor.same

      case Perish ⇒
        Actor.stopped
    }

  }
}

class ClusterSingletonManagerApiSpec {
  import ClusterSingletonManagerApiSpec._

  // Compile only for now

  val system: akka.actor.ActorSystem = ???
  val typed = system.toTyped

  // more like a factory, so maybe the name ClusterSingletonManager is not so good?
  val csm = ClusterSingletonManager(typed)

  // same call on all nodes
  val singleton = csm.spawn(
    pingPong,
    "ping-pong",
    Props.empty,
    ClusterSingletonSettings(typed),
    Perish
  )

  implicit val timeout: Timeout = 3.seconds
  implicit val sc = typed.scheduler
  implicit val ec = typed.executionContext

  (singleton ? Ping).foreach(pong ⇒
    println("That's one goddamn good cup of pong!")
  )

}
