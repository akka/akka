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

object NullEmptyStateSpec {

  private val conf = ConfigFactory.parseString(
    s"""
      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    """)
}

class NullEmptyStateSpec extends ScalaTestWithActorTestKit(NullEmptyStateSpec.conf) with WordSpecLike {

  implicit val testSettings = TestKitSettings(system)

  def primitiveState(persistenceId: PersistenceId, probe: ActorRef[String]): Behavior[String] =
    EventSourcedBehavior[String, String, String](
      persistenceId,
      emptyState = null,
      commandHandler = (_, command) ⇒ {
        if (command == "stop")
          Effect.stop()
        else
          Effect.persist(command)
      },
      eventHandler = (state, event) ⇒ {
        probe.tell("eventHandler:" + state + ":" + event)
        if (state == null) event else state + event
      }
    ).onRecoveryCompleted { s ⇒
        probe.tell("onRecoveryCompleted:" + s)
      }

  "A typed persistent actor with primitive state" must {
    "persist events and update state" in {
      val probe = TestProbe[String]()
      val b = primitiveState(PersistenceId("a"), probe.ref)
      val ref1 = spawn(b)
      probe.expectMessage("onRecoveryCompleted:null")
      ref1 ! "one"
      probe.expectMessage("eventHandler:null:one")
      ref1 ! "two"
      probe.expectMessage("eventHandler:one:two")

      ref1 ! "stop"
      val ref2 = testKit.spawn(b)
      // eventHandler from reply
      probe.expectMessage("eventHandler:null:one")
      probe.expectMessage("eventHandler:one:two")
      probe.expectMessage("onRecoveryCompleted:onetwo")
      ref2 ! "three"
      probe.expectMessage("eventHandler:onetwo:three")
    }

  }
}
