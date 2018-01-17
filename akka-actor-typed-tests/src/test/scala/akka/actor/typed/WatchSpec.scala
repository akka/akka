/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed

import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent._
import akka.testkit.typed.TestKit

object WatchSpec {
  case object Stop

  val terminatorBehavior =
    Behaviors.immutable[Stop.type] {
      case (_, Stop) ⇒ Behaviors.stopped
    }

  sealed trait Message
  case object CustomTerminationMessage extends Message
  case class StartWatchingWith(watchee: ActorRef[Stop.type], msg: CustomTerminationMessage.type) extends Message
}

class WatchSpec extends TestKit("WordSpec")
  with TypedAkkaSpecWithShutdown {

  import WatchSpec._

  "Actor monitoring" must {
    "get notified of actor termination" in {
      case class StartWatching(watchee: ActorRef[Stop.type])
      val terminator = systemActor(terminatorBehavior)
      val receivedTerminationSignal: Promise[ActorRef[Nothing]] = Promise()

      val watcher = systemActor(Behaviors.immutable[StartWatching] {
        case (ctx, StartWatching(watchee)) ⇒
          ctx.watch(watchee)
          Behaviors.same
      }.onSignal {
        case (_, Terminated(stopped)) ⇒
          receivedTerminationSignal.success(stopped)
          Behaviors.stopped
      })

      watcher ! StartWatching(terminator)
      terminator ! Stop

      receivedTerminationSignal.future.futureValue shouldEqual terminator
    }

    "get notified of actor termination with a custom message" in {
      val terminator = systemActor(terminatorBehavior)
      val receivedTerminationSignal: Promise[Message] = Promise()

      val watcher = systemActor(Behaviors.immutable[Message] {
        case (ctx, StartWatchingWith(watchee, msg)) ⇒
          ctx.watchWith(watchee, msg)
          Behaviors.same
        case (_, msg) ⇒
          receivedTerminationSignal.success(msg)
          Behaviors.stopped
      })

      watcher ! StartWatchingWith(terminator, CustomTerminationMessage)
      terminator ! Stop

      receivedTerminationSignal.future.futureValue shouldEqual CustomTerminationMessage
    }
  }
}
