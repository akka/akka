/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.implicitConversions
import org.scalatest.Suite
import org.scalatest.exceptions.TestFailedException
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{ STMultiNodeSpec, MultiNodeSpec }
import akka.testkit._
import akka.testkit.TestEvent._
import akka.actor.{ ActorSystem, Address }
import akka.event.Logging.ErrorLevel
import scala.concurrent.duration._
import scala.collection.immutable
import java.util.concurrent.ConcurrentHashMap
import akka.remote.DefaultFailureDetectorRegistry

object MultiNodeClusterSpec {

  def clusterConfigWithFailureDetectorPuppet: Config =
    ConfigFactory.parseString("akka.cluster.failure-detector.implementation-class = akka.cluster.FailureDetectorPuppet").
      withFallback(clusterConfig)

  def clusterConfig(failureDetectorPuppet: Boolean): Config =
    if (failureDetectorPuppet) clusterConfigWithFailureDetectorPuppet else clusterConfig

  def clusterConfig: Config = ConfigFactory.parseString("""
    akka.actor.provider = akka.cluster.ClusterActorRefProvider
    akka.cluster {
      auto-join                           = off
      auto-down                           = off
      jmx.enabled                         = off
      gossip-interval                     = 200 ms
      leader-actions-interval             = 200 ms
      unreachable-nodes-reaper-interval   = 200 ms
      periodic-tasks-initial-delay        = 300 ms
      publish-stats-interval              = 0 s # always, when it happens
      failure-detector.heartbeat-interval = 400 ms
    }
    akka.loglevel = INFO
    akka.remote.log-remote-lifecycle-events = off
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.test {
      single-expect-default = 5 s
    }
    """)
}

trait MultiNodeClusterSpec extends Suite with STMultiNodeSpec { self: MultiNodeSpec ⇒

  override def initialParticipants = roles.size

  private val cachedAddresses = new ConcurrentHashMap[RoleName, Address]

  override def atStartup(): Unit = {
    muteLog()
  }

  def muteLog(sys: ActorSystem = system): Unit = {
    if (!sys.log.isDebugEnabled) {
      Seq(".*Metrics collection has started successfully.*",
        ".*Metrics will be retreived from MBeans.*",
        ".*Cluster Node.* - registered cluster JMX MBean.*",
        ".*Cluster Node.* - is starting up.*",
        ".*Shutting down cluster Node.*",
        ".*Cluster node successfully shut down.*",
        ".*Using a dedicated scheduler for cluster.*") foreach { s ⇒
          sys.eventStream.publish(Mute(EventFilter.info(pattern = s)))
        }

      Seq(".*received dead letter from.*ClientDisconnected",
        ".*received dead letter from.*deadLetters.*PoisonPill",
        ".*installing context org.jboss.netty.channel.DefaultChannelPipeline.*") foreach { s ⇒
          sys.eventStream.publish(Mute(EventFilter.warning(pattern = s)))
        }
    }
  }

  def muteMarkingAsUnreachable(sys: ActorSystem = system): Unit =
    if (!sys.log.isDebugEnabled)
      sys.eventStream.publish(Mute(EventFilter.error(pattern = ".*Marking.* as UNREACHABLE.*")))

  def muteDeadLetters(sys: ActorSystem = system): Unit =
    if (!sys.log.isDebugEnabled)
      sys.eventStream.publish(Mute(EventFilter.warning(pattern = ".*received dead letter from.*")))

  override def afterAll(): Unit = {
    if (!log.isDebugEnabled) {
      muteDeadLetters()
      system.eventStream.setLogLevel(ErrorLevel)
    }
    super.afterAll()
  }

  /**
   * Lookup the Address for the role.
   *
   * Implicit conversion from RoleName to Address.
   *
   * It is cached, which has the implication that stopping
   * and then restarting a role (jvm) with another address is not
   * supported.
   */
  implicit def address(role: RoleName): Address = {
    cachedAddresses.get(role) match {
      case null ⇒
        val address = node(role).address
        cachedAddresses.put(role, address)
        address
      case address ⇒ address
    }
  }

  // Cluster tests are written so that if previous step (test method) failed
  // it will most likely not be possible to run next step. This ensures
  // fail fast of steps after the first failure.
  private var failed = false
  override protected def withFixture(test: NoArgTest): Unit = try {
    if (failed) {
      val e = new TestFailedException("Previous step failed", 0)
      // short stack trace
      e.setStackTrace(e.getStackTrace.take(1))
      throw e
    }
    super.withFixture(test)
  } catch {
    case t: Throwable ⇒
      failed = true
      throw t
  }

  def clusterView: ClusterReadView = cluster.readView

  /**
   * Get the cluster node to use.
   */
  def cluster: Cluster = Cluster(system)

  /**
   * Use this method for the initial startup of the cluster node.
   */
  def startClusterNode(): Unit = {
    if (clusterView.members.isEmpty) {
      cluster join myself
      awaitCond(clusterView.members.exists(_.address == address(myself)))
    } else
      clusterView.self
  }

  /**
   * Initialize the cluster of the specified member
   * nodes (roles) and wait until all joined and `Up`.
   * First node will be started first  and others will join
   * the first.
   */
  def awaitClusterUp(roles: RoleName*): Unit = {
    runOn(roles.head) {
      // make sure that the node-to-join is started before other join
      startClusterNode()
    }
    enterBarrier(roles.head.name + "-started")
    if (roles.tail.contains(myself)) {
      cluster.join(roles.head)
    }
    if (roles.contains(myself)) {
      awaitMembersUp(numberOfMembers = roles.length)
    }
    enterBarrier(roles.map(_.name).mkString("-") + "-joined")
  }

  /**
   * Assert that the member addresses match the expected addresses in the
   * sort order used by the cluster.
   */
  def assertMembers(gotMembers: Iterable[Member], expectedAddresses: Address*): Unit = {
    import Member.addressOrdering
    val members = gotMembers.toIndexedSeq
    members.size must be(expectedAddresses.length)
    expectedAddresses.sorted.zipWithIndex.foreach { case (a, i) ⇒ members(i).address must be(a) }
  }

  def assertLeader(nodesInCluster: RoleName*): Unit =
    if (nodesInCluster.contains(myself)) assertLeaderIn(nodesInCluster.to[immutable.Seq])

  /**
   * Assert that the cluster has elected the correct leader
   * out of all nodes in the cluster. First
   * member in the cluster ring is expected leader.
   */
  def assertLeaderIn(nodesInCluster: immutable.Seq[RoleName]): Unit =
    if (nodesInCluster.contains(myself)) {
      nodesInCluster.length must not be (0)
      val expectedLeader = roleOfLeader(nodesInCluster)
      val leader = clusterView.leader
      val isLeader = leader == Some(clusterView.selfAddress)
      assert(isLeader == isNode(expectedLeader),
        "expectedLeader [%s], got leader [%s], members [%s]".format(expectedLeader, leader, clusterView.members))
      clusterView.status must (be(MemberStatus.Up) or be(MemberStatus.Leaving))
    }

  /**
   * Wait until the expected number of members has status Up has been reached.
   * Also asserts that nodes in the 'canNotBePartOfMemberRing' are *not* part of the cluster ring.
   */
  def awaitMembersUp(
    numberOfMembers: Int,
    canNotBePartOfMemberRing: Set[Address] = Set.empty,
    timeout: FiniteDuration = 20.seconds): Unit = {
    within(timeout) {
      if (!canNotBePartOfMemberRing.isEmpty) // don't run this on an empty set
        awaitCond(
          canNotBePartOfMemberRing forall (address ⇒ !(clusterView.members exists (_.address == address))))
      awaitCond(clusterView.members.size == numberOfMembers)
      awaitCond(clusterView.members.forall(_.status == MemberStatus.Up))
      // clusterView.leader is updated by LeaderChanged, await that to be updated also
      val expectedLeader = clusterView.members.headOption.map(_.address)
      awaitCond(clusterView.leader == expectedLeader)
    }
  }

  /**
   * Wait until the specified nodes have seen the same gossip overview.
   */
  def awaitSeenSameState(addresses: Address*): Unit =
    awaitCond((addresses.toSet -- clusterView.seenBy).isEmpty)

  def roleOfLeader(nodesInCluster: immutable.Seq[RoleName] = roles): RoleName = {
    nodesInCluster.length must not be (0)
    nodesInCluster.sorted.head
  }

  /**
   * Sort the roles in the order used by the cluster.
   */
  implicit val clusterOrdering: Ordering[RoleName] = new Ordering[RoleName] {
    import Member.addressOrdering
    def compare(x: RoleName, y: RoleName) = addressOrdering.compare(address(x), address(y))
  }

  def roleName(addr: Address): Option[RoleName] = roles.find(address(_) == addr)

  /**
   * Marks a node as available in the failure detector if
   * [[akka.cluster.FailureDetectorPuppet]] is used as
   * failure detector.
   */
  def markNodeAsAvailable(address: Address): Unit =
    failureDetectorPuppet(address) foreach (_.markNodeAsAvailable())

  /**
   * Marks a node as unavailable in the failure detector if
   * [[akka.cluster.FailureDetectorPuppet]] is used as
   * failure detector.
   */
  def markNodeAsUnavailable(address: Address): Unit = {
    if (isFailureDetectorPuppet) {
      // before marking it as unavailble there must be at least one heartbeat
      // to create the FailureDetectorPuppet in the FailureDetectorRegistry
      cluster.failureDetector.heartbeat(address)
      failureDetectorPuppet(address) foreach (_.markNodeAsUnavailable())
    }
  }

  private def isFailureDetectorPuppet: Boolean =
    cluster.settings.FailureDetectorImplementationClass == classOf[FailureDetectorPuppet].getName

  private def failureDetectorPuppet(address: Address): Option[FailureDetectorPuppet] =
    cluster.failureDetector match {
      case reg: DefaultFailureDetectorRegistry[Address] ⇒
        reg.failureDetector(address) collect { case p: FailureDetectorPuppet ⇒ p }
      case _ ⇒ None
    }

}

