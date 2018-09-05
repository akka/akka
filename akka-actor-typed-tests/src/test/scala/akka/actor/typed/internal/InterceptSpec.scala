/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal

import akka.actor.testkit.typed.scaladsl.{ ActorTestKit, TestProbe }
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ Behavior, WrappedBehaviorId, TypedAkkaSpecWithShutdown }
import org.scalatest.WordSpecLike

class InterceptSpec extends ActorTestKit with WordSpecLike with TypedAkkaSpecWithShutdown {

  "Intercept" should {

    "intercept messages" in {
      val probe = TestProbe[String]()

      val ref = spawn(BehaviorImpl.intercept[String, String](
        (ctx, m) ⇒ {
          probe.ref ! ("before " + m)
          m
        },
        (ctx, signal) ⇒ true,
        (ctc, m, b) ⇒ {
          probe.ref ! ("after " + m)
          b
        },
        (ctx, signal, b) ⇒ b,
        Behaviors.receiveMessage(m ⇒
          // FIXME returning same here causes exception in intercept for some reason
          Behaviors.same
        ),
        new WrappedBehaviorId
      ))

      ref ! "message"

      probe.expectMessage("before message")
      probe.expectMessage("after message")

    }

    "intercept messages only using the latest of the same intercept id" in {
      val probe = TestProbe[String]()

      val sameId = new WrappedBehaviorId

      def next(count: Int): Behavior[String] =
        BehaviorImpl.intercept[String, String](
          (ctx, m) ⇒ {
            probe.ref ! s"$count before $m"
            m
          },
          (ctx, signal) ⇒ true,
          (ctc, m, b) ⇒ {
            probe.ref ! s"$count after $m"
            b
          },
          (ctx, signal, b) ⇒ b,
          Behaviors.receiveMessage(m ⇒
            // FIXME returning same here causes exception in intercept for some reason
            next(count + 1)
          ),
          sameId
        )

      val ref = spawn(next(1))

      ref ! "message 1"

      probe.expectMessage("1 before message 1")
      probe.expectMessage("1 after message 1")

      ref ! "message 2"

      // here we'd get duplicates with
      probe.expectMessage("2 before message 2")
      probe.expectMessage("2 after message 2")

    }

    "intercept messages only using all when the intercept id is different" in {
      val probe = TestProbe[String]()

      def next(count: Int): Behavior[String] =
        BehaviorImpl.intercept[String, String](
          (ctx, m) ⇒ {
            probe.ref ! s"$count before $m"
            m
          },
          (ctx, signal) ⇒ true,
          (ctc, m, b) ⇒ {
            probe.ref ! s"$count after $m"
            b
          },
          (ctx, signal, b) ⇒ b,
          Behaviors.receiveMessage(m ⇒
            // FIXME returning same here causes exception in intercept for some reason
            next(count + 1)
          ),
          new WrappedBehaviorId
        )

      val ref = spawn(next(1))

      ref ! "message 1"

      probe.expectMessage("1 before message 1")
      probe.expectMessage("1 after message 1")

      ref ! "message 2"

      probe.expectMessage("1 before message 2")
      probe.expectMessage("2 before message 2")
      probe.expectMessage("2 after message 2")
      probe.expectMessage("1 after message 2")

    }

  }

}
