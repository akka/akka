/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.ActorRef
import akka.actor.Identify
import akka.actor.Props
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.testkit._
import testkit.{ STMultiNodeSpec, MultiNodeConfig, MultiNodeSpec }
import akka.actor.PoisonPill

object AttemptSysMsgRedeliveryMultiJvmSpec extends MultiNodeConfig {

  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false))

  testTransport(on = true)

  class Echo extends Actor {
    def receive = {
      case m ⇒ sender ! m
    }
  }
}

class AttemptSysMsgRedeliveryMultiJvmNode1 extends AttemptSysMsgRedeliverySpec
class AttemptSysMsgRedeliveryMultiJvmNode2 extends AttemptSysMsgRedeliverySpec
class AttemptSysMsgRedeliveryMultiJvmNode3 extends AttemptSysMsgRedeliverySpec

class AttemptSysMsgRedeliverySpec extends MultiNodeSpec(AttemptSysMsgRedeliveryMultiJvmSpec)
  with STMultiNodeSpec with ImplicitSender with DefaultTimeout {
  import AttemptSysMsgRedeliveryMultiJvmSpec._

  def initialParticipants = roles.size

  "AttemptSysMsgRedelivery" must {
    "redeliver system message after inactivity" taggedAs LongRunningTest in {
      system.actorOf(Props[Echo], "echo")
      enterBarrier("echo-started")

      system.actorSelection(node(first) / "user" / "echo") ! Identify(None)
      val firstRef: ActorRef = expectMsgType[ActorIdentity].ref.get
      system.actorSelection(node(second) / "user" / "echo") ! Identify(None)
      val secondRef: ActorRef = expectMsgType[ActorIdentity].ref.get
      enterBarrier("refs-retrieved")

      runOn(first) {
        testConductor.blackhole(first, second, Direction.Both).await
      }
      enterBarrier("blackhole")

      runOn(first, third) {
        watch(secondRef)
      }
      runOn(second) {
        watch(firstRef)
      }
      enterBarrier("watch-established")

      runOn(first) {
        testConductor.passThrough(first, second, Direction.Both).await
      }
      enterBarrier("pass-through")

      system.actorSelection("/user/echo") ! PoisonPill

      runOn(first, third) {
        expectTerminated(secondRef, 10.seconds)
      }
      runOn(second) {
        expectTerminated(firstRef, 10.seconds)
      }

      enterBarrier("done")
    }
  }

}
