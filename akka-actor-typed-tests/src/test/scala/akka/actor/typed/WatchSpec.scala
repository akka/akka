/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.MutableBehavior
import akka.actor.typed.scaladsl.adapter._
import akka.testkit.EventFilter
import akka.testkit.typed.scaladsl.{ ActorTestKit, TestProbe }

import scala.concurrent._
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory

object WatchSpec {
  val config = ConfigFactory.parseString("""akka.loggers = ["akka.testkit.TestEventListener"]""")

  case object Stop

  val terminatorBehavior =
    Behaviors.receive[Stop.type] {
      case (_, Stop) ⇒ Behaviors.stopped
    }

  val mutableTerminatorBehavior = new MutableBehavior[Stop.type] {
    override def onMessage(msg: Stop.type) = msg match {
      case Stop ⇒ Behaviors.stopped
    }
  }

  sealed trait Message
  sealed trait CustomTerminationMessage extends Message
  case object CustomTerminationMessage extends CustomTerminationMessage
  case object CustomTerminationMessage2 extends CustomTerminationMessage
  case class StartWatching(watchee: ActorRef[Stop.type]) extends Message
  case class StartWatchingWith(watchee: ActorRef[Stop.type], msg: CustomTerminationMessage) extends Message
}

class WatchSpec extends ActorTestKit
  with TypedAkkaSpecWithShutdown {

  override def config = WatchSpec.config
  implicit def untypedSystem = system.toUntyped

  import WatchSpec._

  class WatchSetup {
    val terminator = systemActor(terminatorBehavior)
    val receivedTerminationSignal: Promise[Terminated] = Promise()
    val watchProbe = TestProbe[Done]()

    val watcher = systemActor(
      Behaviors.supervise(
        Behaviors.receive[StartWatching] {
          case (ctx, StartWatching(watchee)) ⇒
            ctx.watch(watchee)
            watchProbe.ref ! Done
            Behaviors.same
        }.receiveSignal {
          case (_, t: Terminated) ⇒
            receivedTerminationSignal.success(t)
            Behaviors.stopped
        }
      ).onFailure[Throwable](SupervisorStrategy.stop))
  }

  "Actor monitoring" must {

    "get notified of graceful actor termination" in new WatchSetup {
      watcher ! StartWatching(terminator)
      watchProbe.expectMessage(Done)
      terminator ! Stop

      val termination = receivedTerminationSignal.future.futureValue
      termination.ref shouldEqual terminator
      termination.failure shouldBe empty
    }
    "notify a parent of child termination because of failure" in {
      case class Failed(t: Terminated) // we need to wrap it as it is handled specially
      val probe = TestProbe[Any]()
      val ex = new TestException("boom")
      val parent = spawn(Behaviors.setup[Any] { ctx ⇒
        val child = ctx.spawn(Behaviors.receive[Any]((ctx, msg) ⇒
          throw ex
        ), "child")
        ctx.watch(child)

        Behaviors.receive[Any] { (ctx, msg) ⇒
          child ! msg
          Behaviors.same
        }.receiveSignal {
          case (_, t: Terminated) ⇒
            probe.ref ! Failed(t)
            Behaviors.same
        }
      }, "parent")

      EventFilter[TestException](occurrences = 1).intercept {
        parent ! "boom"
      }
      val terminated = probe.expectMessageType[Failed].t
      terminated.failure should ===(Some(ex)) // here we get the exception from the child
    }
    "fail the actor itself with DeathPact if it does not accept Terminated" in {
      case class Failed(t: Terminated) // we need to wrap it as it is handled specially
      val probe = TestProbe[Any]()
      val ex = new TestException("boom")
      val grossoBosso = spawn(Behaviors.setup[Any] { ctx ⇒
        val middleManagement = ctx.spawn(Behaviors.setup[Any] { ctx ⇒
          val sixPackJoe = ctx.spawn(Behaviors.receive[Any]((ctx, msg) ⇒
            throw ex
          ), "joe")
          ctx.watch(sixPackJoe)

          Behaviors.receive[Any] { (ctx, msg) ⇒
            sixPackJoe ! msg
            Behaviors.same
          } // no handling of terminated, even though we watched!!!
        }, "middle-management")

        ctx.watch(middleManagement)

        Behaviors.receive[Any] { (ctx, msg) ⇒
          middleManagement ! msg
          Behaviors.same
        }.receiveSignal {
          case (_, t: Terminated) ⇒
            probe.ref ! Failed(t)
            Behaviors.stopped
        }

      }, "grosso-bosso")

      EventFilter[TestException](occurrences = 1).intercept {
        EventFilter[DeathPactException](occurrences = 1).intercept {
          grossoBosso ! "boom"
        }
      }
      val terminated = probe.expectMessageType[Failed].t
      terminated.failure.isDefined should ===(true)
      terminated.failure.get shouldBe a[DeathPactException]
    }

    "allow idempotent invocations of watch" in new WatchSetup {
      watcher ! StartWatching(terminator)
      watchProbe.expectMessage(Done)
      // shouldn't fail when watched twice
      watcher ! StartWatching(terminator)
      watchProbe.expectMessage(Done)
      terminator ! Stop
      receivedTerminationSignal.future.futureValue.ref shouldEqual terminator
    }

    class WatchWithSetup {
      val terminator = systemActor(terminatorBehavior)
      val receivedTerminationSignal: Promise[Message] = Promise()
      val watchProbe = TestProbe[Done]()

      val watcher = systemActor(
        Behaviors.supervise(
          Behaviors.receive[Message] {
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
          Behaviors.receive[Message] {
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
          Behaviors.receive[Message] {
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
          Behaviors.receive[Message] {
            case (ctx, StartWatchingWith(watchee, msg)) ⇒
              ctx.watchWith(watchee, msg)
              Behaviors.same
            case (ctx, StartWatching(watchee)) ⇒
              ctx.watch(watchee)
              Behaviors.same
            case (_, msg) ⇒
              Behaviors.stopped
          }.receiveSignal {
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
