/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata

import akka.cluster.MultiNodeClusterSpec
import akka.remote.RARP
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec }
import akka.testkit._
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object ReplicatorMessageResilienceConfig extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.log-dead-letters-during-shutdown = off
    akka.remote.artery.advanced.maximum-frame-size = 32 KiB #default for now
    """))

  testTransport(on = true)
}

class ReplicatorMessageResilienceSpecMultiJvmNode1 extends ReplicatorMessageResilienceSpec
class ReplicatorMessageResilienceSpecMultiJvmNode2 extends ReplicatorMessageResilienceSpec
class ReplicatorMessageResilienceSpecMultiJvmNode3 extends ReplicatorMessageResilienceSpec
class ReplicatorMessageResilienceSpecMultiJvmNode4 extends ReplicatorMessageResilienceSpec

class ReplicatorMessageResilienceSpec
    extends MultiNodeSpec(ReplicatorMessageResilienceConfig)
    with MultiNodeClusterSpec
    with ImplicitSender {
  import ReplicatorMessageResilienceConfig._
  import Replicator._

  implicit val selfUniqueAddress = DistributedData(system).selfUniqueAddress

  // gossip interval long enough to accumulate, 1s longer than default
  private val replicator = system.actorOf(Replicator.props(ReplicatorSettings(system).withGossipInterval(3.seconds)))

  private val timeout = 5.seconds.dilated

  private val Key = ORSetKey[String]("load")

  private val maximumFrameSize = RARP(system).provider.remoteSettings.Artery.Advanced.MaximumFrameSize

  private val N = (maximumFrameSize / 1024) / 2 // 32768/1024=32 / 2 runs

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      cluster.join(node(to).address)
    }

    enterBarrier(from.name + "-joined")
  }

  private val random = new scala.util.Random()

  def payload(size: Int) = random.nextString(size)

  def update(): Unit = {

    val each = (maximumFrameSize / 1024) - 1

    (0 until N).foreach { _ =>
      replicator ! Update(Key, ORSet(), WriteLocal)(_ :+ payload(each))
    }

    receiveN(N).map(_.getClass).toSet shouldEqual Set(classOf[UpdateSuccess[_]])
  }

  "Replicator in cluster with network issues" must {

    "setup: replicate initial data" in {
      join(first, first)
      join(second, first)
      join(third, first)
      join(fourth, first)

      within(10.seconds) {
        awaitAssert {
          replicator ! GetReplicaCount
          expectMsg(ReplicaCount(roles.size))
        }
      }
      enterBarrier("all-joined")

      runOn(first, second, third) {
        update()
      }
      enterBarrier("update-1")

      within(timeout) {
        awaitAssert {
          replicator ! Get(Key, ReadLocal)
          expectMsgType[GetSuccess[ORSet[_]]].dataValue.size shouldEqual (roles.size - 1) * N
        }
      }
      enterBarrier("replicated-1")
    }

    "replicate changes without OversizedPayloadException" in {

      runOn(first, second, third) {
        update()
      }
      enterBarrier("update-2")

      within(5.seconds) {
        awaitAssert {
          replicator ! Get(Key, ReadLocal)
          expectMsgType[GetSuccess[ORSet[_]]].dataValue.size shouldEqual (roles.size - 1) * (N * 2)
        }
      }
      enterBarrier("replicated-2")

    }

    "raise OversizedPayloadException for Replicator.Internal.Gossip" in {
      // TODO testConductor.blackhole this for the realistic model of network connectivity.
      // This just points it out so far as a placeholder.
      // Gossip and delta propagation builds up. When these messages are triggered for replication,
      // the byte size has exceeded the max, thus it currently raises the error and the data is dropped.

      val oversized = maximumFrameSize + 1

      runOn(first) {
        within(timeout * 5) {
          awaitAssert {

            EventFilter
              .error(
                start = "Failed to serialize oversized message [akka.cluster.ddata.Replicator$Internal$Gossip]",
                occurrences = 1)
              .intercept {

                replicator ! Update(Key, ORSet(), WriteLocal)(_ :+ payload(oversized))
                expectMsgType[UpdateSuccess[ORSet[_]]]
              }
          }
        }
      }

      runOn(fourth) {
        // wait for replication from the above update attempt
        within(timeout) {
          awaitAssert {
            replicator ! Get(Key, ReadLocal)
            // which remains unchanged
            expectMsgType[GetSuccess[ORSet[_]]].dataValue.size shouldEqual (roles.size - 1) * (N * 2)
          }
        }
      }
      enterBarrier("oversized-gossip")

    }
    // TODO also add the Failed to serialize oversized message [ActorSelectionMessage(akka.cluster.ddata.Replicator$Internal$DeltaPropagation)]
    // TODO "replicate high throughput changes without OversizedPayloadException if..."
  }

}
