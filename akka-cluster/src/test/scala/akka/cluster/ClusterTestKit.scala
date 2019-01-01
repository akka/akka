/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.actor.ActorSystem
import akka.cluster.MemberStatus.Removed
import akka.testkit.TestKitBase
import com.typesafe.config.{ Config, ConfigFactory }

/**
 * Builds on TestKitBase to provide some extra utilities to run cluster test.
 *
 * All functionality is provided through ClusterTest stateful class.
 */
trait ClusterTestKit extends TestKitBase {

  /**
   * Cluster test util to help manage ActorSystem creation and cluster formation.
   * Useful for writing single jvm, but multi ActorSystem tests.
   *
   * NOTE: This class is stateful and not thread safe. A new instance must be created per test method.
   */
  class ClusterTestUtil(val name: String) {

    private var actorSystems: List[ActorSystem] = List.empty

    /**
     * Register an [[ActorSystem]].
     */
    def register(actorSystem: ActorSystem) = {
      actorSystems = actorSystems :+ actorSystem
      actorSystem
    }

    /**
     * Register an [[ActorSystem]].
     *
     * The [[ActorSystem]] will be prepended to list and be considered the first node
     */
    def registerAsFirst(actorSystem: ActorSystem) = {
      actorSystems = actorSystem +: actorSystems
      actorSystem
    }

    /**
     * Creates a new [[ActorSystem]] using the passed [[Config]] and register it.
     */
    def newActorSystem(config: Config): ActorSystem =
      register(ActorSystem(name, config))

    /**
     * Creates a new [[ActorSystem]] using the passed [[Config]] and register it.
     *
     * This newly created [[ActorSystem]] will be prepended to list and be considered the first node
     */
    def newActorSystemAsFirst(config: Config): ActorSystem =
      registerAsFirst(ActorSystem(name, config))

    /**
     * Create a cluster using the registered [[ActorSystem]]s.
     *
     * The head of the list is considered the first node and will first create a cluster with itself.
     * Other nodes will join the cluster after the first one.
     */
    def formCluster(): Unit = {
      require(actorSystems.nonEmpty, "Can't form a cluster with an empty list of ActorSystems")

      val firstCluster = Cluster(actorSystems.head)
      firstCluster.join(firstCluster.selfAddress)

      val firstNode = firstCluster.readView
      awaitCond(firstNode.isSingletonCluster)

      // let the others join
      actorSystems
        .drop(1) // <- safe tail access
        .foreach(joinCluster)
    }

    /**
     * Makes the passed [[ActorSystem]] joins the cluster.
     * The passed system must have been previously registered on this [[ClusterTestUtil]].
     */
    def joinCluster(actorSystem: ActorSystem): Unit = {
      require(isRegistered(actorSystem), "Unknown actor system")

      val addresses = actorSystems.map(s ⇒ Cluster(s).selfAddress)

      val joiningNodeCluster = Cluster(actorSystem)
      joiningNodeCluster.joinSeedNodes(addresses)
    }

    private def isRegistered(actorSystem: ActorSystem): Boolean =
      actorSystems.contains(actorSystem)

    /** Shuts down all registered [[ActorSystem]]s */
    def shutdownAll(): Unit = actorSystems.foreach(sys ⇒ shutdown(sys))

    /**
     * Force the passed [[ActorSystem]] to quit the cluster and shutdown.
     * Once original system is removed, a new [[ActorSystem]] is started using the same address.
     */
    def quitAndRestart(actorSystem: ActorSystem, config: Config): ActorSystem = {
      require(isRegistered(actorSystem), "Unknown actor system")

      // is this first seed node?
      val firstSeedNode = actorSystems.headOption.contains(actorSystem)

      val cluster = Cluster(actorSystem)
      val port = cluster.selfAddress.port.get

      // remove old before starting the new one
      cluster.leave(cluster.readView.selfAddress)
      awaitCond(cluster.readView.status == Removed, message = s"awaiting node [${cluster.readView.selfAddress}] to be 'Removed'. Current status: [${cluster.readView.status}]")

      shutdown(actorSystem)
      awaitCond(cluster.isTerminated)

      // remove from internal list
      actorSystems = actorSystems.filterNot(_ == actorSystem)

      val newConfig = ConfigFactory.parseString(
        s"""
          akka.remote.netty.tcp.port = $port
          akka.remote.artery.canonical.port = $port
          """
      ).withFallback(config)

      if (firstSeedNode) newActorSystemAsFirst(newConfig)
      else newActorSystem(newConfig)
    }
  }
}
