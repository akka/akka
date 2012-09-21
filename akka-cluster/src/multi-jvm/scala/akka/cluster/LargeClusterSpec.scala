/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.testkit.TestEvent._
import scala.concurrent.duration._
import akka.actor.ActorSystem
import java.util.concurrent.TimeoutException
import scala.collection.immutable.SortedSet
import scala.concurrent.Await
import java.util.concurrent.TimeUnit
import akka.remote.testconductor.RoleName
import akka.actor.Props
import akka.actor.Actor
import akka.cluster.MemberStatus._

object LargeClusterMultiJvmSpec extends MultiNodeConfig {
  // each jvm simulates a datacenter with many nodes
  val firstDatacenter = role("first-datacenter")
  val secondDatacenter = role("second-datacenter")
  val thirdDatacenter = role("third-datacenter")
  val fourthDatacenter = role("fourth-datacenter")
  val fifthDatacenter = role("fifth-datacenter")

  // Note that this test uses default configuration,
  // not MultiNodeClusterSpec.clusterConfig
  commonConfig(ConfigFactory.parseString("""
    # Number of ActorSystems in each jvm, can be specified as
    # system property when running real tests. Many nodes
    # will take long time and consume many threads.
    # 10 => 50 nodes is possible to run on one machine.
    akka.test.large-cluster-spec.nodes-per-datacenter = 2
    akka.cluster {
      gossip-interval = 500 ms
      auto-join = off
      auto-down = on
      failure-detector.acceptable-heartbeat-pause = 10s
      publish-stats-interval = 0 s # always, when it happens
    }
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    akka.loglevel = INFO
    akka.actor.provider = akka.cluster.ClusterActorRefProvider
    akka.actor.default-dispatcher.fork-join-executor {
      # when using nodes-per-datacenter=10 we need some extra
      # threads to keep up with netty connect blocking
      parallelism-min = 13
      parallelism-max = 13
    }
    akka.scheduler.tick-duration = 33 ms
    akka.remote.log-remote-lifecycle-events = off
    akka.remote.netty.execution-pool-size = 4
    #akka.remote.netty.reconnection-time-window = 1s
    akka.remote.netty.backoff-timeout = 500ms
    akka.remote.netty.connection-timeout = 500ms

    # don't use testconductor transport in this test, especially not
    # when using use-dispatcher-for-io
    akka.remote.transport = "akka.remote.netty.NettyRemoteTransport"

    # Using a separate dispatcher for netty io doesn't reduce number
    # of needed threads
    # akka.remote.netty.use-dispatcher-for-io=akka.test.io-dispatcher
    # akka.test.io-dispatcher.fork-join-executor {
    #   parallelism-min = 100
    #   parallelism-max = 100
    # }
    """))
}

class LargeClusterMultiJvmNode1 extends LargeClusterSpec
class LargeClusterMultiJvmNode2 extends LargeClusterSpec
class LargeClusterMultiJvmNode3 extends LargeClusterSpec
class LargeClusterMultiJvmNode4 extends LargeClusterSpec
class LargeClusterMultiJvmNode5 extends LargeClusterSpec

abstract class LargeClusterSpec
  extends MultiNodeSpec(LargeClusterMultiJvmSpec)
  with MultiNodeClusterSpec {

  import LargeClusterMultiJvmSpec._
  import ClusterEvent._

  override def muteLog(sys: ActorSystem = system): Unit = {
    super.muteLog(sys)
    muteMarkingAsUnreachable(sys)
    muteDeadLetters(sys)
  }

  var systems: IndexedSeq[ActorSystem] = IndexedSeq(system)
  val nodesPerDatacenter = system.settings.config.getInt(
    "akka.test.large-cluster-spec.nodes-per-datacenter")

  /**
   * Since we start some ActorSystems/Clusters outside of the
   * MultiNodeClusterSpec control we can't use use the mechanism
   * defined in MultiNodeClusterSpec to inject failure detector etc.
   * Use ordinary Cluster extension with default AccrualFailureDetector.
   */
  override def cluster: Cluster = Cluster(system)

  override def atTermination(): Unit = {
    systems foreach { _.shutdown }
    val shutdownTimeout = 20.seconds
    val deadline = Deadline.now + shutdownTimeout
    systems.foreach { sys ⇒
      if (sys.isTerminated)
        () // already done
      else if (deadline.isOverdue)
        sys.log.warning("Failed to shutdown [{}] within [{}]", sys.name, shutdownTimeout)
      else {
        try sys.awaitTermination(deadline.timeLeft) catch {
          case _: TimeoutException ⇒ sys.log.warning("Failed to shutdown [{}] within [{}]", sys.name, shutdownTimeout)
        }
      }
    }
  }

  def startupSystems(): Unit = {
    // one system is already started by the multi-node test
    for (n ← 2 to nodesPerDatacenter) {
      val sys = ActorSystem(myself.name + "-" + n, system.settings.config)
      muteLog(sys)
      systems :+= sys
    }

    // Initialize the Cluster extensions, i.e. startup the clusters
    systems foreach { Cluster(_) }
  }

  def expectedMaxDuration(totalNodes: Int): FiniteDuration = 5.seconds + 2.seconds * totalNodes

  def joinAll(from: RoleName, to: RoleName, totalNodes: Int, runOnRoles: RoleName*): Unit = {
    val joiningClusters = systems.map(Cluster(_)).toSet
    join(joiningClusters, from, to, totalNodes, runOnRoles: _*)
  }

  def join(joiningClusterNodes: Set[Cluster], from: RoleName, to: RoleName, totalNodes: Int, runOnRoles: RoleName*): Unit = {
    runOnRoles must contain(from)
    runOnRoles must contain(to)

    runOn(runOnRoles: _*) {
      systems.size must be(nodesPerDatacenter) // make sure it is initialized

      val clusterNodes = ifNode(from)(joiningClusterNodes)(systems.map(Cluster(_)).toSet)
      val startGossipCounts = Map.empty[Cluster, Long] ++
        clusterNodes.map(c ⇒ (c -> c.readView.latestStats.receivedGossipCount))
      def gossipCount(c: Cluster): Long = {
        c.readView.latestStats.receivedGossipCount - startGossipCounts(c)
      }
      val startTime = System.nanoTime
      def tookMillis: String = TimeUnit.NANOSECONDS.toMillis(System.nanoTime - startTime) + " ms"

      val latch = TestLatch(clusterNodes.size)
      clusterNodes foreach { c ⇒
        c.subscribe(system.actorOf(Props(new Actor {
          var upCount = 0
          def receive = {
            case state: CurrentClusterState ⇒
              upCount = state.members.count(_.status == Up)
            case MemberUp(_) if !latch.isOpen ⇒
              upCount += 1
              if (upCount == totalNodes) {
                log.debug("All [{}] nodes Up in [{}], it took [{}], received [{}] gossip messages",
                  totalNodes, c.selfAddress, tookMillis, gossipCount(c))
                latch.countDown()
              }
            case _ ⇒ // ignore
          }
        })), classOf[MemberEvent])
      }

      runOn(from) {
        clusterNodes foreach { _ join to }
      }

      Await.ready(latch, remaining)

      awaitCond(clusterNodes.forall(_.readView.convergence))
      val counts = clusterNodes.map(gossipCount(_))
      val formattedStats = "mean=%s min=%s max=%s".format(counts.sum / clusterNodes.size, counts.min, counts.max)
      log.info("Convergence of [{}] nodes reached, it took [{}], received [{}] gossip messages per node",
        totalNodes, tookMillis, formattedStats)

    }
  }

  "A large cluster" must {

    "join all nodes in first-datacenter to first-datacenter" taggedAs LongRunningTest in {
      runOn(firstDatacenter) {
        startupSystems()
        startClusterNode()
      }
      enterBarrier("first-datacenter-started")

      val totalNodes = nodesPerDatacenter
      within(expectedMaxDuration(totalNodes)) {
        joinAll(from = firstDatacenter, to = firstDatacenter, totalNodes, runOnRoles = firstDatacenter)
        enterBarrier("first-datacenter-joined")
      }
    }

    "join all nodes in second-datacenter to first-datacenter" taggedAs LongRunningTest in {
      runOn(secondDatacenter) {
        startupSystems()
      }
      enterBarrier("second-datacenter-started")

      val totalNodes = nodesPerDatacenter * 2
      within(expectedMaxDuration(totalNodes)) {
        joinAll(from = secondDatacenter, to = firstDatacenter, totalNodes, runOnRoles = firstDatacenter, secondDatacenter)
        enterBarrier("second-datacenter-joined")
      }
    }

    "join all nodes in third-datacenter to first-datacenter" taggedAs LongRunningTest in {
      runOn(thirdDatacenter) {
        startupSystems()
      }
      enterBarrier("third-datacenter-started")

      val totalNodes = nodesPerDatacenter * 3
      within(expectedMaxDuration(totalNodes)) {
        joinAll(from = thirdDatacenter, to = firstDatacenter, totalNodes,
          runOnRoles = firstDatacenter, secondDatacenter, thirdDatacenter)
        enterBarrier("third-datacenter-joined")
      }
    }

    "join all nodes in fourth-datacenter to first-datacenter" taggedAs LongRunningTest in {
      runOn(fourthDatacenter) {
        startupSystems()
      }
      enterBarrier("fourth-datacenter-started")

      val totalNodes = nodesPerDatacenter * 4
      within(expectedMaxDuration(totalNodes)) {
        joinAll(from = fourthDatacenter, to = firstDatacenter, totalNodes,
          runOnRoles = firstDatacenter, secondDatacenter, thirdDatacenter, fourthDatacenter)
        enterBarrier("fourth-datacenter-joined")
      }
    }

    "join nodes one by one from fifth-datacenter to first-datacenter" taggedAs LongRunningTest in {
      runOn(fifthDatacenter) {
        startupSystems()
      }
      enterBarrier("fifth-datacenter-started")

      // enough to join a few one-by-one (takes too long time otherwise)
      val (bulk, oneByOne) = systems.splitAt(systems.size - 3)

      if (bulk.nonEmpty) {
        val totalNodes = nodesPerDatacenter * 4 + bulk.size
        within(expectedMaxDuration(totalNodes)) {
          val joiningClusters = ifNode(fifthDatacenter)(bulk.map(Cluster(_)).toSet)(Set.empty)
          join(joiningClusters, from = fifthDatacenter, to = firstDatacenter, totalNodes,
            runOnRoles = firstDatacenter, secondDatacenter, thirdDatacenter, fourthDatacenter, fifthDatacenter)
          enterBarrier("fifth-datacenter-joined-" + bulk.size)
        }
      }

      for (i ← 0 until oneByOne.size) {
        val totalNodes = nodesPerDatacenter * 4 + bulk.size + i + 1
        within(expectedMaxDuration(totalNodes)) {
          val joiningClusters = ifNode(fifthDatacenter)(Set(Cluster(oneByOne(i))))(Set.empty)
          join(joiningClusters, from = fifthDatacenter, to = firstDatacenter, totalNodes,
            runOnRoles = firstDatacenter, secondDatacenter, thirdDatacenter, fourthDatacenter, fifthDatacenter)
          enterBarrier("fifth-datacenter-joined-" + (bulk.size + i))
        }
      }
    }

    "detect failure and auto-down crashed nodes in second-datacenter" taggedAs LongRunningTest in {
      val unreachableNodes = nodesPerDatacenter
      val liveNodes = nodesPerDatacenter * 4

      within(30.seconds + 3.seconds * liveNodes) {
        val startGossipCounts = Map.empty[Cluster, Long] ++
          systems.map(sys ⇒ (Cluster(sys) -> Cluster(sys).readView.latestStats.receivedGossipCount))
        def gossipCount(c: Cluster): Long = {
          c.readView.latestStats.receivedGossipCount - startGossipCounts(c)
        }
        val startTime = System.nanoTime
        def tookMillis: String = TimeUnit.NANOSECONDS.toMillis(System.nanoTime - startTime) + " ms"

        val latch = TestLatch(nodesPerDatacenter)
        systems foreach { sys ⇒
          Cluster(sys).subscribe(sys.actorOf(Props(new Actor {
            var gotUnreachable = Set.empty[Member]
            def receive = {
              case state: CurrentClusterState ⇒
                gotUnreachable = state.unreachable
                checkDone()
              case MemberUnreachable(m) if !latch.isOpen ⇒
                gotUnreachable = gotUnreachable + m
                checkDone()
              case MemberDowned(m) if !latch.isOpen ⇒
                gotUnreachable = gotUnreachable + m
                checkDone()
              case _ ⇒ // not interesting
            }
            def checkDone(): Unit = if (gotUnreachable.size == unreachableNodes) {
              log.info("Detected [{}] unreachable nodes in [{}], it took [{}], received [{}] gossip messages",
                unreachableNodes, Cluster(sys).selfAddress, tookMillis, gossipCount(Cluster(sys)))
              latch.countDown()
            }
          })), classOf[ClusterDomainEvent])
        }

        runOn(firstDatacenter) {
          testConductor.shutdown(secondDatacenter, 0)
        }

        enterBarrier("second-datacenter-shutdown")

        runOn(firstDatacenter, thirdDatacenter, fourthDatacenter, fifthDatacenter) {
          Await.ready(latch, remaining)
          awaitCond(systems.forall(Cluster(_).readView.convergence))
          val mergeCount = systems.map(sys ⇒ Cluster(sys).readView.latestStats.mergeCount).sum
          val counts = systems.map(sys ⇒ gossipCount(Cluster(sys)))
          val formattedStats = "mean=%s min=%s max=%s".format(counts.sum / nodesPerDatacenter, counts.min, counts.max)
          log.info("Convergence of [{}] nodes reached after failure, it took [{}], received [{}] gossip messages per node, merged [{}] times",
            liveNodes, tookMillis, formattedStats, mergeCount)
        }

        enterBarrier("after-6")
      }

    }

  }
}
