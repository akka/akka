/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import scala.concurrent.util.duration._
import scala.language.postfixOps

object ClusterMetricsMultiJvmSpec extends MultiNodeConfig {

  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")

  commonConfig(ConfigFactory.parseString("""
    akka.cluster.auto-join = on
    akka.cluster.metrics.enabled = on
    akka.cluster.metrics.metrics-interval = 3 s
    akka.cluster.metrics.gossip-interval = 3 s
    akka.cluster.metrics.rate-of-decay = 10
    akka.loglevel = INFO
    akka.remote.log-sent-messages = off
    akka.remote.log-received-messages = off
    akka.actor.debug.receive = off
    akka.actor.debug.unhandled = off
    akka.actor.debug.lifecycle = off
    akka.actor.debug.autoreceive = off
    akka.actor.debug.fsm = off""")
    .withFallback(MultiNodeClusterSpec.clusterConfigWithFailureDetectorPuppet))
}

class ClusterMetricsMultiJvmNode1 extends ClusterMetricsSpec
class ClusterMetricsMultiJvmNode2 extends ClusterMetricsSpec
class ClusterMetricsMultiJvmNode3 extends ClusterMetricsSpec
class ClusterMetricsMultiJvmNode4 extends ClusterMetricsSpec
class ClusterMetricsMultiJvmNode5 extends ClusterMetricsSpec

abstract class ClusterMetricsSpec extends MultiNodeSpec(ClusterMetricsMultiJvmSpec) with MultiNodeClusterSpec {
  import ClusterMetricsMultiJvmSpec._
  private val within = 60 seconds

  "Cluster metrics" must {
    "periodically collect metrics on each node, publish ClusterMetricsChanged to the event stream, and gossip metrics around the node ring" taggedAs LongRunningTest in {
      awaitClusterUp(roles: _*)
      enterBarrier("cluster-started")
      awaitCond(clusterView.members.filter(_.status == MemberStatus.Up).size == roles.size)
      awaitCond(clusterView.metrics.size == roles.size, within)
      enterBarrier("after")
    }
    "reflect the correct number of node metrics in cluster view" taggedAs LongRunningTest in {
      val originalMembers = clusterView.members.size
      runOn(second) {
        cluster.leave(first)
      }
      enterBarrier("first-left")
      runOn(second, third, fourth, fifth) {
        awaitCond(clusterView.metrics.size == (originalMembers - 1), within)
      }
      enterBarrier("finished")
    }
  }
}
