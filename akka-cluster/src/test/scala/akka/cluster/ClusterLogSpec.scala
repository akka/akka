/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.actor.{ Address, ExtendedActorSystem }
import akka.testkit.{ AkkaSpec, EventFilter, ImplicitSender }
import com.typesafe.config.{ Config, ConfigFactory }

object ClusterLogSpec {
  val config =
    """
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

abstract class ClusterLogSpec(config: Config) extends AkkaSpec(config) with ImplicitSender {

  def this(s: String) = this(ConfigFactory.parseString(s))

  protected val selfAddress: Address = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress

  protected val cluster = Cluster(system)

  protected def clusterView: ClusterReadView = cluster.readView

  protected def awaitUp(): Unit = {
    awaitCond(clusterView.isSingletonCluster)
    clusterView.self.address should ===(selfAddress)
    clusterView.members.map(_.address) should ===(Set(selfAddress))
    awaitAssert(clusterView.status should ===(MemberStatus.Up))
  }

  /** The expected log info pattern to intercept. */
  protected def join(expected: String): Unit =
    EventFilter.
      info(occurrences = 1, pattern = expected).
      intercept(cluster.join(selfAddress))

  /** The expected log info pattern to intercept. */
  protected def down(expected: String): Unit =
    EventFilter.
      info(occurrences = 1, pattern = expected).
      intercept(cluster.down(selfAddress))
}

class ClusterLogDefaultSpec extends ClusterLogSpec(ClusterLogSpec.config) {

  "A Cluster" must {

    "Log a message when becoming and stopping being a leader" in {
      join("is the new leader")
      awaitUp()
      down("is no longer the leader")
    }
  }
}

abstract class ClusterLogVerboseSpec(config: Config) extends ClusterLogSpec(config) {

  def this(s: String) = this(ConfigFactory.parseString(s))

  protected val upLogMessage = " - event MemberUp"

  protected val downLogMessage = " - event MemberDowned"
}

class ClusterLogVerboseDefaultSpec extends ClusterLogVerboseSpec(ClusterLogSpec.config) {

  "A Cluster" must {

    "not log verbose cluster events by default" in {
      cluster.settings.LogInfoVerbose should ===(false)
      intercept[AssertionError](join(upLogMessage))
      awaitUp()
      intercept[AssertionError](down(downLogMessage))
    }
  }
}

class ClusterLogVerboseEnabledSpec extends ClusterLogVerboseSpec(
  ConfigFactory.parseString("akka.cluster.log-info-verbose = on").
    withFallback(ConfigFactory.parseString(ClusterLogSpec.config))) {

  "A Cluster" must {

    "log verbose cluster events when 'log-info-verbose = on'" in {
      cluster.settings.LogInfoVerbose should ===(true)
      join(upLogMessage)
      awaitUp()
      down(downLogMessage)
    }
  }
}

