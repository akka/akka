/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.testkit.EventFilter

import scala.concurrent._
import akka.testkit.typed.TestKit
import com.typesafe.config.ConfigFactory

object WatchSpec {
  val config = ConfigFactory.parseString("""akka.loggers = ["akka.testkit.TestEventListener"]""")

  case object Stop

  val terminatorBehavior =
    Behaviors.immutable[Stop.type] {
      case (_, Stop) ⇒ Behaviors.stopped
    }

  sealed trait Message
  sealed trait CustomTerminationMessage extends Message
  case object CustomTerminationMessage extends CustomTerminationMessage
  case object CustomTerminationMessage2 extends CustomTerminationMessage
  case class StartWatching(watchee: ActorRef[Stop.type]) extends Message
  case class StartWatchingWith(watchee: ActorRef[Stop.type], msg: CustomTerminationMessage) extends Message
}

class WatchSpec extends TestKit("WordSpec", WatchSpec.config)
  with TypedAkkaSpecWithShutdown {
  implicit def untypedSystem = system.toUntyped

  import WatchSpec._

  "Actor monitoring" must {
    "get notified of actor termination" in {
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

    class ErrorTestSetup {
      val terminator = systemActor(terminatorBehavior)

      val watcher = systemActor(Behaviors.immutable[Message] {
        case (ctx, StartWatchingWith(watchee, msg)) ⇒
          ctx.watchWith(watchee, msg)
          Behaviors.same
        case (ctx, StartWatching(watchee)) ⇒
          ctx.watch(watchee)
          Behaviors.same
        case (_, msg) ⇒
          Behaviors.stopped
      })
    }
    "warn when watch is used after watchWith on same subject" in new ErrorTestSetup {
      watcher ! StartWatchingWith(terminator, CustomTerminationMessage)

      EventFilter[IllegalStateException](pattern = ".*termination message was not overwritten.*", occurrences = 1) intercept {
        watcher ! StartWatching(terminator)
      }
      terminator ! Stop
    }

    "warn when watchWitch is used after watchWith with different termination message" in new ErrorTestSetup {
      watcher ! StartWatchingWith(terminator, CustomTerminationMessage)

      EventFilter[IllegalStateException](pattern = ".*termination message was not overwritten.*", occurrences = 1) intercept {
        watcher ! StartWatchingWith(terminator, CustomTerminationMessage2)
      }
      terminator ! Stop
    }
    "warn when watchWith is used after watch on same subject" in new ErrorTestSetup {
      watcher ! StartWatching(terminator)

      EventFilter[IllegalStateException](pattern = ".*termination message was not overwritten.*", occurrences = 1) intercept {
        watcher ! StartWatchingWith(terminator, CustomTerminationMessage)
      }
      terminator ! Stop
    }
  }
}
