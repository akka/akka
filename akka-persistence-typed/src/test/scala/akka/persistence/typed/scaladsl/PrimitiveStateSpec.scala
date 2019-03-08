/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.scaladsl._
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object PrimitiveStateSpec {

  private val conf = ConfigFactory.parseString(
    s"""
      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    """)
}

class PrimitiveStateSpec extends ScalaTestWithActorTestKit(PrimitiveStateSpec.conf) with WordSpecLike {

  implicit val testSettings = TestKitSettings(system)

  def primitiveState(persistenceId: PersistenceId, probe: ActorRef[String]): Behavior[Int] =
    EventSourcedBehavior[Int, Int, Int](
      persistenceId,
      emptyState = 0,
      commandHandler = (_, command) ⇒ {
        if (command < 0)
          Effect.stop()
        else
          Effect.persist(command)
      },
      eventHandler = (state, event) ⇒ {
        probe.tell("eventHandler:" + state + ":" + event)
        state + event
      }
    ).onRecoveryCompleted { n ⇒
        probe.tell("onRecoveryCompleted:" + n)
      }

  "A typed persistent actor with primitive state" must {
    "persist events and update state" in {
      val probe = TestProbe[String]()
      val b = primitiveState(PersistenceId("a"), probe.ref)
      val ref1 = spawn(b)
      probe.expectMessage("onRecoveryCompleted:0")
      ref1 ! 1
      probe.expectMessage("eventHandler:0:1")
      ref1 ! 2
      probe.expectMessage("eventHandler:1:2")

      ref1 ! -1
      probe.expectTerminated(ref1)

      val ref2 = testKit.spawn(b)
      // eventHandler from replay
      probe.expectMessage("eventHandler:0:1")
      probe.expectMessage("eventHandler:1:2")
      probe.expectMessage("onRecoveryCompleted:3")
      ref2 ! 3
      probe.expectMessage("eventHandler:3:3")
    }

  }
}
