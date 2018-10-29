/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.actor.{ Address, ExtendedActorSystem }
import akka.cluster.InternalClusterAction.LeaderActionsTick
import akka.testkit.{ AkkaSpec, EventFilter, ImplicitSender }

object ClusterLogSpec {
  val config = """
    akka.cluster {
      auto-down-unreachable-after = 0s
      publish-stats-interval = 0 s # always, when it happens
      failure-detector.implementation-class = akka.cluster.FailureDetectorPuppet
    }
    akka.actor.provider = "cluster"
    akka.remote.log-remote-lifecycle-events = off
    akka.remote.netty.tcp.port = 0
    akka.remote.artery.canonical.port = 0
    akka.loglevel = "INFO"
    akka.loggers = ["akka.testkit.TestEventListener"]
    """

}

class ClusterLogSpec extends AkkaSpec(ClusterLogSpec.config) with ImplicitSender {

  val selfAddress: Address = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress

  val cluster = Cluster(system)
  def clusterView: ClusterReadView = cluster.readView

  "A Cluster" must {

    "Log a message when becoming and stopping being a leader" in {
      EventFilter
        .info(occurrences = 1, pattern = "is the new leader")
        .intercept {
          cluster.join(selfAddress)
        }

      awaitCond(clusterView.isSingletonCluster)
      clusterView.self.address should ===(selfAddress)
      clusterView.members.map(_.address) should ===(Set(selfAddress))
      awaitAssert(clusterView.status should ===(MemberStatus.Up))

      EventFilter
        .info(occurrences = 1, pattern = "is no longer the leader")
        .intercept {
          cluster.down(selfAddress)
        }
    }

  }
}
