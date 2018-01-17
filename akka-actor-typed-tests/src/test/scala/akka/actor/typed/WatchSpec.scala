/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed

import akka.actor.typed.scaladsl.ActorBehavior

import scala.concurrent._
import akka.testkit.typed.TestKit

object WatchSpec {
  case object Stop

  val terminatorBehavior =
    ActorBehavior.immutable[Stop.type] {
      case (_, Stop) ⇒ ActorBehavior.stopped
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

      val watcher = systemActor(ActorBehavior.immutable[StartWatching] {
        case (ctx, StartWatching(watchee)) ⇒
          ctx.watch(watchee)
          ActorBehavior.same
      }.onSignal {
        case (_, Terminated(stopped)) ⇒
          receivedTerminationSignal.success(stopped)
          ActorBehavior.stopped
      })

      watcher ! StartWatching(terminator)
      terminator ! Stop

      receivedTerminationSignal.future.futureValue shouldEqual terminator
    }

    "get notified of actor termination with a custom message" in {
      val terminator = systemActor(terminatorBehavior)
      val receivedTerminationSignal: Promise[Message] = Promise()

      val watcher = systemActor(ActorBehavior.immutable[Message] {
        case (ctx, StartWatchingWith(watchee, msg)) ⇒
          ctx.watchWith(watchee, msg)
          ActorBehavior.same
        case (_, msg) ⇒
          receivedTerminationSignal.success(msg)
          ActorBehavior.stopped
      })

      watcher ! StartWatchingWith(terminator, CustomTerminationMessage)
      terminator ! Stop

      receivedTerminationSignal.future.futureValue shouldEqual CustomTerminationMessage
    }
  }
}
