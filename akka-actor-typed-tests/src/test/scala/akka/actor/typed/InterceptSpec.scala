/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import java.util.concurrent.atomic.AtomicBoolean

import akka.testkit.EventFilter
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.WordSpecLike

import scala.concurrent.duration._
import akka.actor.ActorInitializationException
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit

object InterceptSpec {
  final case class Msg(hello: String, replyTo: ActorRef[String])
  case object MyPoisonPill
}

class InterceptSpec extends ScalaTestWithActorTestKit(
  """
      akka.loggers = [akka.testkit.TestEventListener]
    """) with WordSpecLike {
  import BehaviorInterceptor._
  import InterceptSpec._

  // FIXME eventfilter support in typed testkit
  import scaladsl.adapter._
  implicit val untypedSystem = system.toUntyped

  private def snitchingInterceptor(probe: ActorRef[String]) = new BehaviorInterceptor[String, String] {
    override def aroundReceive(context: TypedActorContext[String], message: String, target: ReceiveTarget[String]): Behavior[String] = {
      probe ! ("before " + message)
      val b = target(context, message)
      probe ! ("after " + message)
      b
    }

    override def aroundSignal(context: TypedActorContext[String], signal: Signal, target: SignalTarget[String]): Behavior[String] = {
      target(context, signal)
    }

    // keeping the instance equality as "isSame" for these
  }

  "Intercept" must {

    "intercept messages" in {
      val probe = TestProbe[String]()
      val interceptor = snitchingInterceptor(probe.ref)

      val ref: ActorRef[String] = spawn(Behaviors.intercept(
        interceptor)(
        Behaviors.receiveMessage[String] { m ⇒
          probe.ref ! s"actual behavior $m"
          Behaviors.same
        }
      ))

      ref ! "message"

      probe.expectMessage("before message")
      probe.expectMessage("actual behavior message")
      probe.expectMessage("after message")

    }

    "intercept messages only using the outermost of the same interceptor (initially)" in {
      val probe = TestProbe[String]()

      val interceptor = snitchingInterceptor(probe.ref)
      def intercept(beh: Behavior[String]): Behavior[String] =
        Behaviors.intercept(
          interceptor)(beh)

      val beh: Behavior[String] =
        intercept(
          intercept(
            Behaviors.receiveMessage(m ⇒
              Behaviors.same
            )
          )
        )

      val ref = spawn(beh)

      ref ! "message 1"
      // here'd we get duplicates if both intercepts stayed in effect
      probe.expectMessage("before message 1")
      probe.expectMessage("after message 1")
    }

    "intercept messages only using the latest of the same interceptor (recursively)" in {
      val probe = TestProbe[String]()

      val interceptor = snitchingInterceptor(probe.ref)
      def next(count: Int): Behavior[String] =
        Behaviors.intercept(
          interceptor)(
          Behaviors.receiveMessage(m ⇒
            next(count + 1)
          )
        )

      val ref = spawn(next(1))

      ref ! "message 1"
      probe.expectMessage("before message 1")
      probe.expectMessage("after message 1")

      // here we'd get duplicates if it wasn't deduplicated
      // but instead of waiting for no-message we can confirm no duplicates by sending next
      ref ! "message 2"
      probe.expectMessage("before message 2")
      probe.expectMessage("after message 2")

      ref ! "message 3"
      probe.expectMessage("before message 3")
      probe.expectMessage("after message 3")
    }

    "intercept messages keeping all different interceptors (initially)" in {
      val probe = TestProbe[String]()

      def intercept(beh: Behavior[String]): Behavior[String] =
        // a new interceptor instance every call
        Behaviors.intercept(snitchingInterceptor(probe.ref))(beh)

      val beh: Behavior[String] =
        intercept(
          intercept(
            Behaviors.receiveMessage(m ⇒
              Behaviors.same
            )
          )
        )

      val ref = spawn(beh)

      ref ! "message 1"
      probe.expectMessage("before message 1")
      probe.expectMessage("before message 1")
      probe.expectMessage("after message 1")
      probe.expectMessage("after message 1")

    }

    "intercept messages keeping all different interceptors (recursively)" in {
      val probe = TestProbe[String]()

      def next(count: Int): Behavior[String] =
        Behaviors.intercept(
          // a new instance every "recursion"
          snitchingInterceptor(probe.ref))(
            Behaviors.receiveMessage(m ⇒
              next(count + 1)
            )
          )

      val ref = spawn(next(1))

      ref ! "message 1"
      probe.expectMessage("before message 1")
      probe.expectMessage("after message 1")

      ref ! "message 2"
      probe.expectMessage("before message 2")
      probe.expectMessage("before message 2")
      probe.expectMessage("after message 2")
      probe.expectMessage("after message 2")
    }

    "allow an interceptor to replace started behavior" in {
      val interceptor = new BehaviorInterceptor[String, String] {

        override def aroundStart(context: TypedActorContext[String], target: PreStartTarget[String]): Behavior[String] = {
          Behaviors.stopped
        }

        def aroundReceive(context: TypedActorContext[String], message: String, target: ReceiveTarget[String]): Behavior[String] =
          target(context, message)

        def aroundSignal(context: TypedActorContext[String], signal: Signal, target: SignalTarget[String]): Behavior[String] =
          target(context, signal)
      }

      val innerBehaviorStarted = new AtomicBoolean(false)
      val ref = spawn(Behaviors.intercept(interceptor)(Behaviors.setup { context ⇒
        innerBehaviorStarted.set(true)
        Behaviors.unhandled[String]
      }))

      val probe = TestProbe()
      probe.expectTerminated(ref, 3.seconds)
      innerBehaviorStarted.get should ===(false)
    }

    "intercept with nested setup" in {
      val probe = TestProbe[String]()
      val interceptor = snitchingInterceptor(probe.ref)

      val ref: ActorRef[String] = spawn(Behaviors.intercept(interceptor)(
        Behaviors.setup { _ ⇒
          var count = 0
          Behaviors.receiveMessage[String] { m ⇒
            count += 1
            probe.ref ! s"actual behavior $m-$count"
            Behaviors.same
          }
        }
      ))

      ref ! "a"
      probe.expectMessage("before a")
      probe.expectMessage("actual behavior a-1")
      probe.expectMessage("after a")

      ref ! "b"
      probe.expectMessage("before b")
      probe.expectMessage("actual behavior b-2")
      probe.expectMessage("after b")
    }

    "intercept with recursivly setup" in {
      val probe = TestProbe[String]()
      val interceptor = snitchingInterceptor(probe.ref)

      def next(count1: Int): Behavior[String] = {
        Behaviors.intercept(interceptor)(
          Behaviors.setup { _ ⇒
            var count2 = 0
            Behaviors.receiveMessage[String] { m ⇒
              count2 += 1
              probe.ref ! s"actual behavior $m-$count1-$count2"
              next(count1 + 1)
            }
          }
        )
      }

      val ref: ActorRef[String] = spawn(next(1))

      ref ! "a"
      probe.expectMessage("before a")
      probe.expectMessage("actual behavior a-1-1")
      probe.expectMessage("after a")

      ref ! "b"
      probe.expectMessage("before b")
      probe.expectMessage("actual behavior b-2-1")
      probe.expectMessage("after b")

      ref ! "c"
      probe.expectMessage("before c")
      probe.expectMessage("actual behavior c-3-1")
      probe.expectMessage("after c")
    }

    "not allow intercept setup(same)" in {
      val probe = TestProbe[String]()
      val interceptor = snitchingInterceptor(probe.ref)

      EventFilter[ActorInitializationException](occurrences = 1).intercept {
        val ref = spawn(Behaviors.intercept(interceptor)(
          Behaviors.setup[String] { _ ⇒ Behaviors.same[String] }))
        probe.expectTerminated(ref, probe.remainingOrDefault)
      }

    }

    "be useful for implementing PoisonPill" in {

      def inner(count: Int): Behavior[Msg] = Behaviors.receiveMessage {
        case Msg(hello, replyTo) ⇒
          replyTo ! s"$hello-$count"
          inner(count + 1)
      }

      val poisonInterceptor = new BehaviorInterceptor[Any, Msg] {
        override def aroundReceive(context: TypedActorContext[Any], message: Any, target: ReceiveTarget[Msg]): Behavior[Msg] =
          message match {
            case MyPoisonPill ⇒ Behaviors.stopped
            case m: Msg       ⇒ target(context, m)
            case _            ⇒ Behaviors.unhandled
          }

        override def aroundSignal(context: TypedActorContext[Any], signal: Signal, target: SignalTarget[Msg]): Behavior[Msg] =
          target.apply(context, signal)

      }

      val decorated: Behavior[Msg] =
        Behaviors.intercept(poisonInterceptor)(inner(0)).narrow

      val ref = spawn(decorated)
      val probe = TestProbe[String]()
      ref ! Msg("hello", probe.ref)
      probe.expectMessage("hello-0")
      ref ! Msg("hello", probe.ref)
      probe.expectMessage("hello-1")

      ref.unsafeUpcast[Any] ! MyPoisonPill

      probe.expectTerminated(ref, probe.remainingOrDefault)
    }

    "be able to intercept a subset of the messages" in {
      trait Message
      class A extends Message
      class B extends Message

      val interceptProbe = TestProbe[Message]()

      val partialInterceptor: BehaviorInterceptor[Message, Message] = new BehaviorInterceptor[Message, Message] {

        override def interceptMessageType = classOf[B]

        override def aroundReceive(ctx: TypedActorContext[Message], msg: Message, target: ReceiveTarget[Message]): Behavior[Message] = {
          interceptProbe.ref ! msg
          target(ctx, msg)
        }

        override def aroundSignal(ctx: TypedActorContext[Message], signal: Signal, target: SignalTarget[Message]): Behavior[Message] =
          target(ctx, signal)
      }

      val probe = TestProbe[Message]()
      val ref = spawn(Behaviors.intercept(partialInterceptor)(Behaviors.receiveMessage { msg ⇒
        probe.ref ! msg
        Behaviors.same
      }))

      ref ! new A
      ref ! new B

      probe.expectMessageType[A]
      interceptProbe.expectMessageType[B]
      probe.expectMessageType[B]
    }

  }

}
