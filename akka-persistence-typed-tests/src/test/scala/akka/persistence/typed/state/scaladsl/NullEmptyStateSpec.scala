/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.state.scaladsl

/*
 * Copyright (C) 2018-2021 Lightbend Inc. <https://www.lightbend.com>
 */

import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.scaladsl._
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.persistence.typed.PersistenceId
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

import akka.persistence.testkit.PersistenceTestKitDurableStateStorePlugin

object NullEmptyStateSpec {

  def conf: Config = PersistenceTestKitDurableStateStorePlugin.config.withFallback(ConfigFactory.parseString(s"""
    akka.loglevel = INFO
    """))
}

class NullEmptyStateSpec
    extends ScalaTestWithActorTestKit(NullEmptyStateSpec.conf)
    with AnyWordSpecLike
    with LogCapturing {

  implicit val testSettings: TestKitSettings = TestKitSettings(system)

  def primitiveState(persistenceId: PersistenceId, probe: ActorRef[String]): Behavior[String] =
    DurableStateBehavior[String, String](persistenceId, emptyState = null, commandHandler = (_, command) => {
      if (command == "stop")
        Effect.stop()
      else
        Effect.persist(command).thenReply(probe)(_ => command)
    })

  "A typed persistent actor with primitive state" must {
    "persist events and update state" in {
      val probe = TestProbe[String]()
      val b = primitiveState(PersistenceId.ofUniqueId("a"), probe.ref)
      val ref1 = spawn(b)
      ref1 ! "one"
      probe.expectMessage("one")
      ref1 ! "two"
      probe.expectMessage("two")
      ref1 ! "stop"
      probe.expectTerminated(ref1)

      val _ = spawn(b)
      // no events, no replay and hence no messages
      probe.expectNoMessage()
    }
  }
}
