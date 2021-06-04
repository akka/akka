/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.state.scaladsl

/*
 * Copyright (C) 2018-2021 Lightbend Inc. <https://www.lightbend.com>
 */

import akka.actor.testkit.typed.scaladsl._
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.persistence.typed.PersistenceId
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object PrimitiveStateSpec {

  private def conf: Config = ConfigFactory.parseString(s"""
    akka.loglevel = INFO
    akka.persistence.state.plugin = "akka.persistence.state.inmem"
    akka.persistence.state.inmem {
      class = "akka.persistence.state.inmem.InmemDurableStateStoreProvider"
      recovery-timeout = 30s
    }
    """)
}

class PrimitiveStateSpec
    extends ScalaTestWithActorTestKit(PrimitiveStateSpec.conf)
    with AnyWordSpecLike
    with LogCapturing {

  def primitiveState(persistenceId: PersistenceId, probe: ActorRef[String]): Behavior[Int] =
    DurableStateBehavior[Int, Int](persistenceId, emptyState = 0, commandHandler = (_, command) => {
      if (command < 0)
        Effect.stop()
      else
        Effect.persist(command).thenReply(probe)(_ => command.toString)
    }).withDurableStateStorePluginId("akka.persistence.state.inmem")

  "A typed persistent actor with primitive state" must {
    "persist primitive events and update state" in {
      val probe = TestProbe[String]()
      val b = primitiveState(PersistenceId.ofUniqueId("a"), probe.ref)
      val ref1 = spawn(b)
      ref1 ! 1
      probe.expectMessage("1")
      ref1 ! 2
      probe.expectMessage("2")

      ref1 ! -1
      probe.expectTerminated(ref1)

      val ref2 = spawn(b)
      // no events, no replay and hence no messages
      probe.expectNoMessage()
      ref2 ! 3
      probe.expectMessage("3")
    }

  }
}
