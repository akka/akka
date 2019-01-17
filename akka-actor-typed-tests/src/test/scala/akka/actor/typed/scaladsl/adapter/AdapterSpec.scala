/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl.adapter

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import akka.actor.InvalidMessageException
import akka.actor.testkit.typed.TestException
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Terminated
import akka.testkit._
import akka.Done
import akka.NotUsed
import akka.{ actor ⇒ untyped }

object AdapterSpec {
  val untyped1: untyped.Props = untyped.Props(new Untyped1)

  class Untyped1 extends untyped.Actor {
    def receive = {
      case "ping"     ⇒ sender() ! "pong"
      case t: ThrowIt ⇒ throw t
    }
  }

  def untypedForwarder(ref: untyped.ActorRef): untyped.Props = untyped.Props(new UntypedForwarder(ref))

  class UntypedForwarder(ref: untyped.ActorRef) extends untyped.Actor {
    def receive = {
      case a: String ⇒ ref ! a
    }
  }

  def typed1(ref: untyped.ActorRef, probe: ActorRef[String]): Behavior[String] =
    Behaviors.receive[String] {
      (context, message) ⇒
        message match {
          case "send" ⇒
            val replyTo = context.self.toUntyped
            ref.tell("ping", replyTo)
            Behaviors.same
          case "pong" ⇒
            probe ! "ok"
            Behaviors.same
          case "actorOf" ⇒
            val child = context.actorOf(untyped1)
            child.tell("ping", context.self.toUntyped)
            Behaviors.same
          case "watch" ⇒
            context.watch(ref)
            Behaviors.same
          case "supervise-stop" ⇒
            val child = context.actorOf(untyped1)
            context.watch(child)
            child ! ThrowIt3
            child.tell("ping", context.self.toUntyped)
            Behaviors.same
          case "stop-child" ⇒
            val child = context.actorOf(untyped1)
            context.watch(child)
            context.stop(child)
            Behaviors.same
        }
    } receiveSignal {
      case (context, Terminated(ref)) ⇒
        probe ! "terminated"
        Behaviors.same
    }

  def unhappyTyped(msg: String): Behavior[String] = Behaviors.setup[String] { ctx ⇒
    val child = ctx.spawnAnonymous(Behaviors.receiveMessage[String] { _ ⇒
      throw TestException(msg)
    })
    child ! "throw please"
    Behaviors.empty
  }

  sealed trait Typed2Msg
  final case class Ping(replyTo: ActorRef[String]) extends Typed2Msg
  case object StopIt extends Typed2Msg
  sealed trait ThrowIt extends RuntimeException with Typed2Msg with NoStackTrace
  case object ThrowIt1 extends ThrowIt
  case object ThrowIt2 extends ThrowIt
  case object ThrowIt3 extends ThrowIt

  def untyped2(ref: ActorRef[Ping], probe: ActorRef[String]): untyped.Props =
    untyped.Props(new Untyped2(ref, probe))

  class Untyped2(ref: ActorRef[Ping], probe: ActorRef[String]) extends untyped.Actor {

    override val supervisorStrategy = untyped.OneForOneStrategy() {
      ({
        case ThrowIt1 ⇒
          probe ! "thrown-stop"
          untyped.SupervisorStrategy.Stop
        case ThrowIt2 ⇒
          probe ! "thrown-resume"
          untyped.SupervisorStrategy.Resume
        case ThrowIt3 ⇒
          probe ! "thrown-restart"
          // TODO Restart will not really restart the behavior
          untyped.SupervisorStrategy.Restart
      }: untyped.SupervisorStrategy.Decider).orElse(untyped.SupervisorStrategy.defaultDecider)
    }

    def receive = {
      case "send" ⇒ ref ! Ping(self) // implicit conversion
      case "pong" ⇒ probe ! "ok"
      case "spawn" ⇒
        val child = context.spawnAnonymous(typed2)
        child ! Ping(self)
      case "actorOf-props" ⇒
        // this is how Cluster Sharding can be used
        val child = context.actorOf(typed2Props)
        child ! Ping(self)
      case "watch" ⇒
        context.watch(ref)
      case untyped.Terminated(_) ⇒
        probe ! "terminated"
      case "supervise-stop" ⇒
        testSupervice(ThrowIt1)
      case "supervise-resume" ⇒
        testSupervice(ThrowIt2)
      case "supervise-restart" ⇒
        testSupervice(ThrowIt3)
      case "stop-child" ⇒
        val child = context.spawnAnonymous(typed2)
        context.watch(child)
        context.stop(child)
    }

    private def testSupervice(t: ThrowIt): Unit = {
      val child = context.spawnAnonymous(typed2)
      context.watch(child)
      child ! t
      child ! Ping(self)
    }
  }

  def typed2: Behavior[Typed2Msg] =
    Behaviors.receive { (context, message) ⇒
      message match {
        case Ping(replyTo) ⇒
          replyTo ! "pong"
          Behaviors.same
        case StopIt ⇒
          Behaviors.stopped
        case t: ThrowIt ⇒
          throw t
      }
    }

  def typed2Props: untyped.Props = PropsAdapter(typed2)

}

class AdapterSpec extends AkkaSpec(
  """
   akka.loggers = [akka.testkit.TestEventListener]
  """) {
  import AdapterSpec._

  "ActorSystem adaption" must {
    "only happen once for a given actor system" in {
      val typed1 = system.toTyped
      val typed2 = system.toTyped

      typed1 should be theSameInstanceAs typed2
    }

    "not crash if guardian is stopped" in {
      for { _ ← 0 to 10 } {
        var system: akka.actor.typed.ActorSystem[NotUsed] = null
        try {
          system = ActorSystem.create(Behaviors.setup[NotUsed](_ ⇒ Behavior.stopped[NotUsed]), "AdapterSpec-stopping-guardian")
        } finally if (system != null) shutdown(system.toUntyped)
      }
    }

    "not crash if guardian is stopped very quickly" in {
      for { _ ← 0 to 10 } {
        var system: akka.actor.typed.ActorSystem[Done] = null
        try {
          system = ActorSystem.create(Behaviors.receive[Done] { (context, message) ⇒
            context.self ! Done
            message match {
              case Done ⇒ Behaviors.stopped
            }

          }, "AdapterSpec-stopping-guardian-2")

        } finally if (system != null) shutdown(system.toUntyped)
      }
    }
  }

  "Adapted actors" must {

    "send message from typed to untyped" in {
      val probe = TestProbe()
      val untypedRef = system.actorOf(untyped1)
      val typedRef = system.spawnAnonymous(typed1(untypedRef, probe.ref))
      typedRef ! "send"
      probe.expectMsg("ok")
    }

    "not send null message from typed to untyped" in {
      val probe = TestProbe()
      val untypedRef = system.actorOf(untyped1)
      val typedRef = system.spawnAnonymous(typed1(untypedRef, probe.ref))
      intercept[InvalidMessageException] {
        typedRef ! null
      }
    }

    "send message from untyped to typed" in {
      val probe = TestProbe()
      val typedRef = system.spawnAnonymous(typed2)
      val untypedRef = system.actorOf(untyped2(typedRef, probe.ref))
      untypedRef ! "send"
      probe.expectMsg("ok")
    }

    "spawn typed child from untyped parent" in {
      val probe = TestProbe()
      val ign = system.spawnAnonymous(Behaviors.ignore[Ping])
      val untypedRef = system.actorOf(untyped2(ign, probe.ref))
      untypedRef ! "spawn"
      probe.expectMsg("ok")
    }

    "actorOf typed child via Props from untyped parent" in {
      val probe = TestProbe()
      val ign = system.spawnAnonymous(Behaviors.ignore[Ping])
      val untypedRef = system.actorOf(untyped2(ign, probe.ref))
      untypedRef ! "actorOf-props"
      probe.expectMsg("ok")
    }

    "actorOf untyped child from typed parent" in {
      val probe = TestProbe()
      val ignore = system.actorOf(untyped.Props.empty)
      val typedRef = system.spawnAnonymous(typed1(ignore, probe.ref))
      typedRef ! "actorOf"
      probe.expectMsg("ok")
    }

    "watch typed from untyped" in {
      val probe = TestProbe()
      val typedRef = system.spawnAnonymous(typed2)
      val untypedRef = system.actorOf(untyped2(typedRef, probe.ref))
      untypedRef ! "watch"
      typedRef ! StopIt
      probe.expectMsg("terminated")
    }

    "watch untyped from typed" in {
      val probe = TestProbe()
      val untypedRef = system.actorOf(untyped1)
      val typedRef = system.spawnAnonymous(typed1(untypedRef, probe.ref))
      typedRef ! "watch"
      untypedRef ! untyped.PoisonPill
      probe.expectMsg("terminated")
    }

    "supervise typed child from untyped parent" in {
      val probe = TestProbe()
      val ign = system.spawnAnonymous(Behaviors.ignore[Ping])
      val untypedRef = system.actorOf(untyped2(ign, probe.ref))

      EventFilter[AdapterSpec.ThrowIt1.type](occurrences = 1).intercept {
        EventFilter.warning(pattern = """.*received dead letter.*""", occurrences = 1).intercept {
          untypedRef ! "supervise-stop"
          probe.expectMsg("thrown-stop")
          // ping => ok should not get through here
          probe.expectMsg("terminated")
        }
      }

      untypedRef ! "supervise-resume"
      probe.expectMsg("thrown-resume")
      probe.expectMsg("ok")

      EventFilter[AdapterSpec.ThrowIt3.type](occurrences = 1).intercept {
        untypedRef ! "supervise-restart"
        probe.expectMsg("thrown-restart")
        probe.expectMsg("ok")
      }
    }

    "supervise untyped child from typed parent" in {
      // FIXME there's a warning with null logged from the untyped empty child here, where does that come from?
      val probe = TestProbe()
      val ignore = system.actorOf(untyped.Props.empty)
      val typedRef = system.spawnAnonymous(typed1(ignore, probe.ref))

      // only stop supervisorStrategy
      EventFilter[AdapterSpec.ThrowIt3.type](occurrences = 1).intercept {
        EventFilter.warning(pattern = """.*received dead letter.*""", occurrences = 1).intercept {
          typedRef ! "supervise-stop"
          probe.expectMsg("terminated")
          probe.expectNoMessage(100.millis) // no pong
        }
      }
    }

    "stop typed child from untyped parent" in {
      val probe = TestProbe()
      val ignore = system.spawnAnonymous(Behaviors.ignore[Ping])
      val untypedRef = system.actorOf(untyped2(ignore, probe.ref))
      untypedRef ! "stop-child"
      probe.expectMsg("terminated")
    }

    "stop untyped child from typed parent" in {
      val probe = TestProbe()
      val ignore = system.actorOf(untyped.Props.empty)
      val typedRef = system.spawnAnonymous(typed1(ignore, probe.ref))
      typedRef ! "stop-child"
      probe.expectMsg("terminated")
    }

    "log exception if not by handled typed supervisor" in {
      val throwMsg = "sad panda"
      EventFilter.warning(pattern = ".*sad panda.*").intercept {
        system.spawnAnonymous(unhappyTyped(throwMsg))
        Thread.sleep(1000)
      }
    }
  }
}
