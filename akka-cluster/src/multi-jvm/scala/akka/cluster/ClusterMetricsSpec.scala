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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch

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
    # Turning off the noise
    akka.remote.log-remote-lifecycle-events = off
    akka.remote.log-sent-messages = off
    akka.remote.log-received-messages = off
    akka.actor.debug.receive = off
    akka.actor.debug.unhandled = off
    akka.actor.debug.lifecycle = off
    akka.actor.debug.autoreceive = off
    akka.actor.debug.fsm = off"""))
}

class ClusterMetricsMultiJvmNode1 extends ClusterMetricsSpec with AccrualFailureDetectorStrategy
class ClusterMetricsMultiJvmNode2 extends ClusterMetricsSpec with AccrualFailureDetectorStrategy
class ClusterMetricsMultiJvmNode3 extends ClusterMetricsSpec with AccrualFailureDetectorStrategy
class ClusterMetricsMultiJvmNode4 extends ClusterMetricsSpec with AccrualFailureDetectorStrategy
class ClusterMetricsMultiJvmNode5 extends ClusterMetricsSpec with AccrualFailureDetectorStrategy

abstract class ClusterMetricsSpec extends MultiNodeSpec(ClusterMetricsMultiJvmSpec) with MultiNodeClusterSpec {

  import ClusterMetricsMultiJvmSpec._
  import ClusterEvent._
  import system.dispatcher

  val duration = 60 seconds // for long running tests

  "Cluster metrics" must {
    "periodically collect metrics on each node and publish ClusterMetricsChanged event to the event stream for router consumption" taggedAs LongRunningTest in {
      awaitClusterUp(roles: _*)
      enterBarrier("cluster-started")
      val received = new AtomicReference[Set[NodeMetrics]](Set.empty)
      val latch = new CountDownLatch(roles.size)
      val cancellable = system.scheduler.schedule(3 seconds, 1000 millis) {
        awaitCond(clusterView.metrics.size == clusterView.members.filter(_.status == MemberStatus.Up).size)
        received.set(clusterView.metrics)
        latch.countDown()
      }
      awaitCond(latch.getCount == 0, duration)
      cancellable.cancel()
      received.get.size must be (clusterView.members.filter(_.status == MemberStatus.Up).size)
      enterBarrier("after")
    }

    "periodically gossip metrics around the node ring with peers that are 'MemberStatus.Up'" taggedAs LongRunningTest in {
      awaitClusterUp(roles: _*)
      enterBarrier("cluster-started")
      awaitCond(clusterView.metrics.size == clusterView.members.filter(_.status == MemberStatus.Up).size)
      enterBarrier("after")
    }
  }
}
