/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorRef
import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.MemberStatus
import akka.cluster.MultiNodeClusterSpec
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.serialization.jackson.CborSerializable
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object PubSubSpecConfig extends MultiNodeConfig {
  val first: RoleName = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(ConfigFactory.parseString("""
        akka.loglevel = DEBUG
      """).withFallback(MultiNodeClusterSpec.clusterConfig))

  nodeConfig(first)(ConfigFactory.parseString("""
      akka.cluster.multi-data-center.self-data-center = "dc1"
    """))

  nodeConfig(second, third)(ConfigFactory.parseString("""
      akka.cluster.multi-data-center.self-data-center = "dc2"
    """))

  case class Message(msg: String) extends CborSerializable
}

class PubSubMultiJvmNode1 extends PubSubSpec
class PubSubMultiJvmNode2 extends PubSubSpec
class PubSubMultiJvmNode3 extends PubSubSpec

abstract class PubSubSpec extends MultiNodeSpec(PubSubSpecConfig) with MultiNodeTypedClusterSpec {

  import PubSubSpecConfig._

  val subscriberProbe = TestProbe[Message]()
  var topic: ActorRef[Topic.Command[Message]] = null

  "A cluster" must {
    "be able to form" in {
      runOn(first) {
        cluster.manager ! Join(cluster.selfMember.address)
      }
      runOn(second, third) {
        cluster.manager ! Join(first)
      }
      enterBarrier("form-cluster-join-attempt")
      runOn(first, second, third) {
        within(20.seconds) {
          awaitAssert(clusterView.members.filter(_.status == MemberStatus.Up) should have size 3)
        }
      }
      enterBarrier("cluster started")
    }

    "start a topic on each node" in {
      topic = system.actorOf(PropsAdapter(Topic[Message]("animals"))).toTyped[Topic.Command[Message]]
      topic ! Topic.Subscribe(subscriberProbe.ref)
      enterBarrier("topic started")
    }

    "publish to all nodes" in {
      // FIXME deterministic all nodes seen the subscribers
      Thread.sleep(1000)
      runOn(first) {
        topic ! Topic.Publish(Message("monkey"))
      }
      subscriberProbe.expectMessage(Message("monkey"))
      enterBarrier("publish seen")
    }

  }
}
