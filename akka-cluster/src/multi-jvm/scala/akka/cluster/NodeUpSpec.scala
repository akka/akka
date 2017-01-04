/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import scala.concurrent.duration._
import scala.collection.immutable.SortedSet
import java.util.concurrent.atomic.AtomicReference
import akka.actor.Props
import akka.actor.Actor

object NodeUpMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfigWithFailureDetectorPuppet))
}

class NodeUpMultiJvmNode1 extends NodeUpSpec
class NodeUpMultiJvmNode2 extends NodeUpSpec

abstract class NodeUpSpec
  extends MultiNodeSpec(NodeUpMultiJvmSpec)
  with MultiNodeClusterSpec {

  import NodeUpMultiJvmSpec._
  import ClusterEvent._

  "A cluster node that is joining another cluster" must {
    "not be able to join a node that is not a cluster member" in {

      runOn(first) {
        cluster.join(second)
      }
      enterBarrier("first-join-attempt")

      Thread.sleep(2000)
      clusterView.members should ===(Set.empty)

      enterBarrier("after-0")
    }

    "be moved to UP by the leader after a convergence" in {
      awaitClusterUp(first, second)
      enterBarrier("after-1")
    }

    "be unaffected when joining again" in {

      val unexpected = new AtomicReference[SortedSet[Member]](SortedSet.empty)
      cluster.subscribe(system.actorOf(Props(new Actor {
        def receive = {
          case event: MemberEvent ⇒
            unexpected.set(unexpected.get + event.member)
          case _: CurrentClusterState ⇒ // ignore
        }
      })), classOf[MemberEvent])
      enterBarrier("listener-registered")

      runOn(second) {
        cluster.join(first)
      }
      enterBarrier("joined-again")

      // let it run for a while to make sure that nothing bad happens
      for (n ← 1 to 20) {
        Thread.sleep(100.millis.dilated.toMillis)
        unexpected.get should ===(SortedSet.empty)
        clusterView.members.forall(_.status == MemberStatus.Up) should ===(true)
      }

      enterBarrier("after-2")
    }
  }
}
