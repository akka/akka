/**
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
      periodic-tasks-initial-delay = 120 seconds // turn off scheduled tasks
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

  final case class GossipTo(address: Address)
}

class ClusterLogSpec extends AkkaSpec(ClusterLogSpec.config) with ImplicitSender {

  val selfAddress: Address = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress

  val cluster = Cluster(system)
  def clusterView: ClusterReadView = cluster.readView

  def leaderActions(): Unit = cluster.clusterCore ! LeaderActionsTick

  "A Cluster" must {

    "Log a message when becoming and stopping being a leader" in {
      clusterView.members.size should ===(0)
      cluster.join(selfAddress)
      EventFilter
        .info(occurrences = 1, pattern = "((^|, )(.+?(is the new leader)))+$")
        .intercept {
          leaderActions() // Joining -> Up
        }
      awaitCond(clusterView.isSingletonCluster)
      clusterView.self.address should ===(selfAddress)
      clusterView.members.map(_.address) should ===(Set(selfAddress))
      awaitAssert(clusterView.status should ===(MemberStatus.Up))
      cluster.down(selfAddress)
      EventFilter
        .info(occurrences = 1, pattern = "((^|, )(.+?(is no longer the leader)))+$")
        .intercept {
          leaderActions()
        }
    }

  }
}
