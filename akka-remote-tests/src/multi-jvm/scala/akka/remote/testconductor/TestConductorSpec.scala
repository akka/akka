/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.testconductor

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.Deploy
import akka.actor.Identify
import akka.actor.Props
import akka.remote.RemotingMultiNodeSpec
import akka.remote.testkit.MultiNodeConfig
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.testkit.LongRunningTest

object TestConductorMultiJvmSpec extends MultiNodeConfig {
  commonConfig(debugConfig(on = false).withFallback(RemotingMultiNodeSpec.commonConfig))

  val leader = role("leader")
  val follower = role("follower")

  testTransport(on = true)
}

class TestConductorMultiJvmNode1 extends TestConductorSpec
class TestConductorMultiJvmNode2 extends TestConductorSpec

class TestConductorSpec extends RemotingMultiNodeSpec(TestConductorMultiJvmSpec) {

  import TestConductorMultiJvmSpec._

  def initialParticipants = 2

  lazy val echo = {
    system.actorSelection(node(leader) / "user" / "echo") ! Identify(None)
    expectMsgType[ActorIdentity].ref.get
  }

  "A TestConductor" must {

    "enter a barrier" taggedAs LongRunningTest in {
      runOn(leader) {
        system.actorOf(Props(new Actor {
          def receive = {
            case x => testActor ! x; sender() ! x
          }
        }).withDeploy(Deploy.local), "echo")
      }

      enterBarrier("name")
    }

    "support throttling of network connections" taggedAs LongRunningTest in {

      runOn(follower) {
        // start remote network connection so that it can be throttled
        echo ! "start"
      }

      expectMsg("start")

      runOn(leader) {
        testConductor.throttle(follower, leader, Direction.Send, rateMBit = 0.01).await
      }

      enterBarrier("throttled_send")

      runOn(follower) {
        for (i <- 0 to 9) echo ! i
      }

      within(0.5 seconds, 2 seconds) {
        expectMsg(500 millis, 0)
        receiveN(9) should ===(1 to 9)
      }

      enterBarrier("throttled_send2")

      runOn(leader) {
        testConductor.throttle(follower, leader, Direction.Send, -1).await
        testConductor.throttle(follower, leader, Direction.Receive, rateMBit = 0.01).await
      }

      enterBarrier("throttled_recv")

      runOn(follower) {
        for (i <- 10 to 19) echo ! i
      }

      val (min, max) =
        if (isNode(leader)) (0 seconds, 500 millis)
        else (0.3 seconds, 3 seconds)

      within(min, max) {
        expectMsg(500 millis, 10)
        receiveN(9) should ===(11 to 19)
      }

      enterBarrier("throttled_recv2")

      runOn(leader) {
        testConductor.throttle(follower, leader, Direction.Receive, -1).await
      }

      enterBarrier("after")
    }

  }

}
