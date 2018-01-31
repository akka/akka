/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed.scaladsl.adapter

import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.actor.typed.ActorRef
import akka.actor.{ InvalidMessageException, Props }
import akka.actor.typed.Behavior
import akka.actor.typed.Terminated
import akka.actor.typed.scaladsl.Behaviors
import akka.{ actor ⇒ untyped }
import akka.testkit._
import akka.actor.typed.Behavior.UntypedBehavior

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
    Behaviors.immutable[String] {
      (ctx, msg) ⇒
        msg match {
          case "send" ⇒
            val replyTo = ctx.self.toUntyped
            ref.tell("ping", replyTo)
            Behaviors.same
          case "pong" ⇒
            probe ! "ok"
            Behaviors.same
          case "actorOf" ⇒
            val child = ctx.actorOf(untyped1)
            child.tell("ping", ctx.self.toUntyped)
            Behaviors.same
          case "watch" ⇒
            ctx.watch(ref)
            Behaviors.same
          case "supervise-stop" ⇒
            val child = ctx.actorOf(untyped1)
            ctx.watch(child)
            child ! ThrowIt3
            child.tell("ping", ctx.self.toUntyped)
            Behaviors.same
          case "stop-child" ⇒
            val child = ctx.actorOf(untyped1)
            ctx.watch(child)
            ctx.stop(child)
            Behaviors.same
        }
    } onSignal {
      case (ctx, Terminated(ref)) ⇒
        probe ! "terminated"
        Behaviors.same
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
    Behaviors.immutable { (ctx, msg) ⇒
      msg match {
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

class AdapterSpec extends AkkaSpec {
  import AdapterSpec._

  "ActorSystem adaption" must {
    "only happen once for a given actor system" in {
      val typed1 = system.toTyped
      val typed2 = system.toTyped

      typed1 should be theSameInstanceAs typed2
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

      untypedRef ! "supervise-stop"
      probe.expectMsg("thrown-stop")
      // ping => ok should not get through here
      probe.expectMsg("terminated")

      untypedRef ! "supervise-resume"
      probe.expectMsg("thrown-resume")
      probe.expectMsg("ok")

      untypedRef ! "supervise-restart"
      probe.expectMsg("thrown-restart")
      probe.expectMsg("ok")
    }

    "supervise untyped child from typed parent" in {
      val probe = TestProbe()
      val ignore = system.actorOf(untyped.Props.empty)
      val typedRef = system.spawnAnonymous(typed1(ignore, probe.ref))

      // only stop supervisorStrategy
      typedRef ! "supervise-stop"
      probe.expectMsg("terminated")
      probe.expectNoMsg(100.millis) // no pong
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

    "spawn untyped behavior anonymously" in {
      val probe = TestProbe()
      val untypedBehavior: Behavior[String] = new UntypedBehavior[String] {
        override private[akka] def untypedProps: Props = untypedForwarder(probe.ref)
      }
      val ref = system.spawnAnonymous(untypedBehavior)
      ref ! "hello"
      probe.expectMsg("hello")
    }

    "spawn untyped behavior" in {
      val probe = TestProbe()
      val untypedBehavior: Behavior[String] = new UntypedBehavior[String] {
        override private[akka] def untypedProps: Props = untypedForwarder(probe.ref)
      }
      val ref = system.spawn(untypedBehavior, "test")
      ref ! "hello"
      probe.expectMsg("hello")
    }
  }
}
