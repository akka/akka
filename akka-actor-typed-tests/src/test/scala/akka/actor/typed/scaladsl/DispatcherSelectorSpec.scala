/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import akka.actor.BootstrapSetup
import akka.actor.setup.ActorSystemSetup
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Props
import akka.actor.typed.SpawnProtocol
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object DispatcherSelectorSpec {
  val config = ConfigFactory.parseString("""
      ping-pong-dispatcher {
        executor = thread-pool-executor
        type = PinnedDispatcher
      }
    """)

  object PingPong {
    case class Ping(replyTo: ActorRef[Pong])
    case class Pong(threadName: String)

    def apply(): Behavior[Ping] =
      Behaviors.receiveMessage[Ping] { message =>
        message.replyTo ! Pong(Thread.currentThread().getName)
        Behaviors.same
      }

  }

}

class DispatcherSelectorSpec
    extends ScalaTestWithActorTestKit(DispatcherSelectorSpec.config)
    with WordSpecLike
    with LogCapturing {
  import DispatcherSelectorSpec.PingPong
  import DispatcherSelectorSpec.PingPong._

  "DispatcherSelector" must {

    "select dispatcher from config" in {
      val probe = createTestProbe[Pong]()
      val pingPong = spawn(PingPong(), Props.empty.withDispatcherFromConfig("ping-pong-dispatcher"))
      pingPong ! Ping(probe.ref)

      val response = probe.receiveMessage()
      response.threadName should startWith("DispatcherSelectorSpec-ping-pong-dispatcher")
    }

    "select same dispatcher as parent" in {
      val parent = spawn(SpawnProtocol(), Props.empty.withDispatcherFromConfig("ping-pong-dispatcher"))
      val childProbe = createTestProbe[ActorRef[Ping]]()
      parent ! SpawnProtocol.Spawn(PingPong(), "child", Props.empty.withDispatcherSameAsParent, childProbe.ref)

      val probe = createTestProbe[Pong]()
      val child = childProbe.receiveMessage()
      child ! Ping(probe.ref)

      val response = probe.receiveMessage()
      response.threadName should startWith("DispatcherSelectorSpec-ping-pong-dispatcher")
    }

    "select same dispatcher as parent, several levels" in {
      val grandParent = spawn(SpawnProtocol(), Props.empty.withDispatcherFromConfig("ping-pong-dispatcher"))
      val parentProbe = createTestProbe[ActorRef[SpawnProtocol.Spawn[Ping]]]()
      grandParent ! SpawnProtocol.Spawn(
        SpawnProtocol(),
        "parent",
        Props.empty.withDispatcherSameAsParent,
        parentProbe.ref)

      val childProbe = createTestProbe[ActorRef[Ping]]()
      grandParent ! SpawnProtocol.Spawn(PingPong(), "child", Props.empty.withDispatcherSameAsParent, childProbe.ref)

      val probe = createTestProbe[Pong]()
      val child = childProbe.receiveMessage()
      child ! Ping(probe.ref)

      val response = probe.receiveMessage()
      response.threadName should startWith("DispatcherSelectorSpec-ping-pong-dispatcher")
    }

    "use default dispatcher if selecting parent dispatcher for user guardian" in {
      val sys = ActorSystem(
        PingPong(),
        "DispatcherSelectorSpec2",
        ActorSystemSetup.create(BootstrapSetup()),
        Props.empty.withDispatcherSameAsParent)
      try {
        val probe = TestProbe[Pong]()(sys)
        sys ! Ping(probe.ref)

        val response = probe.receiveMessage()
        response.threadName should startWith("DispatcherSelectorSpec2-akka.actor.default-dispatcher")
      } finally {
        ActorTestKit.shutdown(sys)
      }
    }

  }

}
