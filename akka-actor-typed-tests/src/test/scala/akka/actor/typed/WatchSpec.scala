/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.testkit.EventFilter
import akka.testkit.typed.scaladsl.TestProbe

import scala.concurrent._
import scala.concurrent.duration._
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
    class WatchSetup {
      val terminator = systemActor(terminatorBehavior)
      val receivedTerminationSignal: Promise[ActorRef[Nothing]] = Promise()
      val watchProbe = TestProbe[Done]()

      val watcher = systemActor(
        Behaviors.supervise(
          Behaviors.immutable[StartWatching] {
            case (ctx, StartWatching(watchee)) ⇒
              ctx.watch(watchee)
              watchProbe.ref ! Done
              Behaviors.same
          }.onSignal {
            case (_, Terminated(stopped)) ⇒
              receivedTerminationSignal.success(stopped)
              Behaviors.stopped
          }
        ).onFailure[Throwable](SupervisorStrategy.stop))
    }
    "get notified of actor termination" in new WatchSetup {
      watcher ! StartWatching(terminator)
      watchProbe.expectMessage(Done)
      terminator ! Stop

      receivedTerminationSignal.future.futureValue shouldEqual terminator
    }
    "allow idempotent invocations of watch" in new WatchSetup {
      watcher ! StartWatching(terminator)
      watchProbe.expectMessage(Done)
      // shouldn't fail when watched twice
      watcher ! StartWatching(terminator)
      watchProbe.expectMessage(Done)
      terminator ! Stop

      receivedTerminationSignal.future.futureValue shouldEqual terminator
    }

    class WatchWithSetup {
      val terminator = systemActor(terminatorBehavior)
      val receivedTerminationSignal: Promise[Message] = Promise()
      val watchProbe = TestProbe[Done]()

      val watcher = systemActor(
        Behaviors.supervise(
          Behaviors.immutable[Message] {
            case (ctx, StartWatchingWith(watchee, msg)) ⇒
              ctx.watchWith(watchee, msg)
              watchProbe.ref ! Done
              Behaviors.same
            case (_, msg) ⇒
              receivedTerminationSignal.success(msg)
              Behaviors.stopped
          }).onFailure[Throwable](SupervisorStrategy.stop)
      )
    }
    "get notified of actor termination with a custom message" in new WatchWithSetup {
      watcher ! StartWatchingWith(terminator, CustomTerminationMessage)
      watchProbe.expectMessage(Done)
      terminator ! Stop

      receivedTerminationSignal.future.futureValue shouldEqual CustomTerminationMessage
    }
    "allow idempotent invocations of watchWith with matching msgs" in new WatchWithSetup {
      watcher ! StartWatchingWith(terminator, CustomTerminationMessage)
      watchProbe.expectMessage(Done)
      // shouldn't fail when watchWith'd twice
      watcher ! StartWatchingWith(terminator, CustomTerminationMessage)
      watchProbe.expectMessage(Done)
      terminator ! Stop

      receivedTerminationSignal.future.futureValue shouldEqual CustomTerminationMessage
    }

    "allow watch message definition after watch using unwatch" in {
      val terminator = systemActor(terminatorBehavior)
      val receivedTerminationSignal: Promise[Message] = Promise()
      val watchProbe = TestProbe[Done]()

      val watcher = systemActor(
        Behaviors.supervise(
          Behaviors.immutable[Message] {
            case (ctx, StartWatching(watchee)) ⇒
              ctx.watch(watchee)
              Behaviors.same
            case (ctx, StartWatchingWith(watchee, msg)) ⇒
              ctx.unwatch(watchee)
              ctx.watchWith(watchee, msg)
              watchProbe.ref ! Done
              Behaviors.same
            case (_, msg) ⇒
              receivedTerminationSignal.success(msg)
              Behaviors.stopped
          }).onFailure[Throwable](SupervisorStrategy.stop)
      )

      watcher ! StartWatching(terminator)
      watcher ! StartWatchingWith(terminator, CustomTerminationMessage)
      watchProbe.expectMessage(Done)
      terminator ! Stop

      receivedTerminationSignal.future.futureValue shouldEqual CustomTerminationMessage
    }

    "allow watch message redefinition using unwatch" in {
      val terminator = systemActor(terminatorBehavior)
      val receivedTerminationSignal: Promise[Message] = Promise()
      val watchProbe = TestProbe[Done]()

      val watcher = systemActor(
        Behaviors.supervise(
          Behaviors.immutable[Message] {
            case (ctx, StartWatchingWith(watchee, msg)) ⇒
              ctx.unwatch(watchee)
              ctx.watchWith(watchee, msg)
              watchProbe.ref ! Done
              Behaviors.same
            case (_, msg) ⇒
              receivedTerminationSignal.success(msg)
              Behaviors.stopped
          }).onFailure[Throwable](SupervisorStrategy.stop)
      )

      watcher ! StartWatchingWith(terminator, CustomTerminationMessage)
      watcher ! StartWatchingWith(terminator, CustomTerminationMessage2)
      watchProbe.expectMessage(Done)
      terminator ! Stop

      receivedTerminationSignal.future.futureValue shouldEqual CustomTerminationMessage2
    }

    class ErrorTestSetup {
      val terminator = systemActor(terminatorBehavior)
      private val stopProbe = TestProbe[Done]()

      val watcher = systemActor(
        Behaviors.supervise(
          Behaviors.immutable[Message] {
            case (ctx, StartWatchingWith(watchee, msg)) ⇒
              ctx.watchWith(watchee, msg)
              Behaviors.same
            case (ctx, StartWatching(watchee)) ⇒
              ctx.watch(watchee)
              Behaviors.same
            case (_, msg) ⇒
              Behaviors.stopped
          }.onSignal {
            case (_, PostStop) ⇒
              Behaviors.stopped
          }
        ).onFailure[Throwable](SupervisorStrategy.stop)
      )

      def expectStopped(): Unit = stopProbe.expectTerminated(watcher, 1.second)
    }

    "fail when watch is used after watchWith on same subject" in new ErrorTestSetup {
      watcher ! StartWatchingWith(terminator, CustomTerminationMessage)

      EventFilter[IllegalStateException](pattern = ".*termination message was not overwritten.*", occurrences = 1) intercept {
        watcher ! StartWatching(terminator)
      }
      // supervisor should have stopped the actor
      expectStopped()
    }

    "fail when watchWitch is used after watchWith with different termination message" in new ErrorTestSetup {
      watcher ! StartWatchingWith(terminator, CustomTerminationMessage)

      EventFilter[IllegalStateException](pattern = ".*termination message was not overwritten.*", occurrences = 1) intercept {
        watcher ! StartWatchingWith(terminator, CustomTerminationMessage2)
      }
      // supervisor should have stopped the actor
      expectStopped()
    }
    "fail when watchWith is used after watch on same subject" in new ErrorTestSetup {
      watcher ! StartWatching(terminator)

      EventFilter[IllegalStateException](pattern = ".*termination message was not overwritten.*", occurrences = 1) intercept {
        watcher ! StartWatchingWith(terminator, CustomTerminationMessage)
      }
      // supervisor should have stopped the actor
      expectStopped()
    }
  }
}
