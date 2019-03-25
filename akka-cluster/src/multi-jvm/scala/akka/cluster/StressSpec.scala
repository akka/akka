/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import language.postfixOps
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration._
import java.util.concurrent.ThreadLocalRandom
import org.scalatest.BeforeAndAfterEach
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.Deploy
import akka.actor.OneForOneStrategy
import akka.actor.Props
import akka.actor.RootActorPath
import akka.actor.SupervisorStrategy._
import akka.actor.Terminated
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.ClusterEvent.CurrentInternalStats
import akka.cluster.ClusterEvent.MemberEvent
import akka.remote.DefaultFailureDetectorRegistry
import akka.remote.PhiAccrualFailureDetector
import akka.remote.RemoteScope
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.routing.FromConfig
import akka.testkit._
import akka.testkit.TestEvent._
import akka.actor.Identify
import akka.actor.ActorIdentity
import akka.util.Helpers.ConfigOps
import akka.util.Helpers.Requiring
import java.lang.management.ManagementFactory
import akka.remote.RARP

/**
 * This test is intended to be used as long running stress test
 * of cluster related features. Number of nodes and duration of
 * the test steps can be configured. The test scenario is organized as
 * follows:
 * 1. join nodes in various ways up to the configured total number of nodes
 * 2  while nodes are joining a few cluster aware routers are also working
 * 3. exercise concurrent joining and shutdown of nodes repeatedly
 * 4. exercise cluster aware routers, including high throughput
 * 5. exercise many actors in a tree structure
 * 6. exercise remote supervision
 * 7. gossip without any changes to the membership
 * 8. leave and shutdown nodes in various ways
 * 9. while nodes are removed remote death watch is also exercised
 * 10. while nodes are removed a few cluster aware routers are also working
 *
 * By default it uses 13 nodes.
 * Example of sbt command line parameters to double that:
 * `-DMultiJvm.akka.cluster.Stress.nrOfNodes=26 -Dmultinode.Dakka.test.cluster-stress-spec.nr-of-nodes-factor=2`
 */
private[cluster] object StressMultiJvmSpec extends MultiNodeConfig {

  val totalNumberOfNodes =
    System.getProperty("MultiJvm.akka.cluster.Stress.nrOfNodes") match {
      case null  => 13
      case value => value.toInt.requiring(_ >= 10, "nrOfNodes should be >= 10")
    }

  for (n <- 1 to totalNumberOfNodes) role("node-" + n)

  // Note that this test uses default configuration,
  // not MultiNodeClusterSpec.clusterConfig
  commonConfig(ConfigFactory.parseString("""
    akka.test.cluster-stress-spec {
      infolog = off
      # scale the nr-of-nodes* settings with this factor
      nr-of-nodes-factor = 1
      # not scaled
      nr-of-seed-nodes = 3
      nr-of-nodes-joining-to-seed-initially = 2
      nr-of-nodes-joining-one-by-one-small = 2
      nr-of-nodes-joining-one-by-one-large = 2
      nr-of-nodes-joining-to-one = 2
      nr-of-nodes-leaving-one-by-one-small = 1
      nr-of-nodes-leaving-one-by-one-large = 2
      nr-of-nodes-leaving = 2
      nr-of-nodes-shutdown-one-by-one-small = 1
      nr-of-nodes-shutdown-one-by-one-large = 2
      nr-of-nodes-shutdown = 2
      nr-of-nodes-join-remove = 2
      # not scaled
      # scale the *-duration settings with this factor
      duration-factor = 1
      join-remove-duration = 90s
      work-batch-size = 100
      work-batch-interval = 2s
      payload-size = 1000
      normal-throughput-duration = 30s
      high-throughput-duration = 10s
      supervision-duration = 10s
      supervision-one-iteration = 2.5s
      idle-gossip-duration = 10s
      expected-test-duration = 600s
      # actors are created in a tree structure defined
      # by tree-width (number of children for each actor) and
      # tree-levels, total number of actors can be calculated by
      # (width * math.pow(width, levels) - 1) / (width - 1)
      tree-width = 4
      tree-levels = 4
      # scale convergence within timeouts with this factor
      convergence-within-factor = 1.0
      # set to off to only test cluster membership
      exercise-actors = on
    }

    akka.actor.serialize-messages = off
    akka.actor.serialize-creators = off
    akka.actor.provider = cluster
    akka.cluster {
      failure-detector.acceptable-heartbeat-pause =  10s
      auto-down-unreachable-after = 1s
      publish-stats-interval = 1s
    }
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel = INFO
    akka.remote.log-remote-lifecycle-events = off

    akka.remote.artery.advanced {
      idle-cpu-level = 1
      embedded-media-driver = off
      aeron-dir = "target/aeron-StressSpec"
    }

    akka.actor.default-dispatcher.fork-join-executor {
      parallelism-min = 8
      parallelism-max = 8
    }

    akka.actor.deployment {
      /master-node-1/workers {
        router = round-robin-pool
        cluster {
          enabled = on
          max-nr-of-instances-per-node = 1
          allow-local-routees = on
        }
      }
      /master-node-2/workers {
        router = round-robin-group
        routees.paths = ["/user/worker"]
        cluster {
          enabled = on
          allow-local-routees = on
        }
      }
      /master-node-3/workers = {
        router = round-robin-pool
        cluster {
          enabled = on
          max-nr-of-instances-per-node = 1
          allow-local-routees = on
        }
      }
    }
    """))

  class Settings(conf: Config) {
    private val testConfig = conf.getConfig("akka.test.cluster-stress-spec")
    import testConfig._

    val infolog = getBoolean("infolog")
    val nFactor = getInt("nr-of-nodes-factor")
    val numberOfSeedNodes = getInt("nr-of-seed-nodes") // not scaled by nodes factor
    val numberOfNodesJoiningToSeedNodesInitially = getInt("nr-of-nodes-joining-to-seed-initially") * nFactor
    val numberOfNodesJoiningOneByOneSmall = getInt("nr-of-nodes-joining-one-by-one-small") * nFactor
    val numberOfNodesJoiningOneByOneLarge = getInt("nr-of-nodes-joining-one-by-one-large") * nFactor
    val numberOfNodesJoiningToOneNode = getInt("nr-of-nodes-joining-to-one") * nFactor
    // remaining will join to seed nodes
    val numberOfNodesJoiningToSeedNodes = (totalNumberOfNodes - numberOfSeedNodes -
    numberOfNodesJoiningToSeedNodesInitially - numberOfNodesJoiningOneByOneSmall -
    numberOfNodesJoiningOneByOneLarge - numberOfNodesJoiningToOneNode)
      .requiring(_ >= 0, s"too many configured nr-of-nodes-joining-*, total should be <= ${totalNumberOfNodes}")
    val numberOfNodesLeavingOneByOneSmall = getInt("nr-of-nodes-leaving-one-by-one-small") * nFactor
    val numberOfNodesLeavingOneByOneLarge = getInt("nr-of-nodes-leaving-one-by-one-large") * nFactor
    val numberOfNodesLeaving = getInt("nr-of-nodes-leaving") * nFactor
    val numberOfNodesShutdownOneByOneSmall = getInt("nr-of-nodes-shutdown-one-by-one-small") * nFactor
    val numberOfNodesShutdownOneByOneLarge = getInt("nr-of-nodes-shutdown-one-by-one-large") * nFactor
    val numberOfNodesShutdown = getInt("nr-of-nodes-shutdown") * nFactor
    val numberOfNodesJoinRemove = getInt("nr-of-nodes-join-remove") // not scaled by nodes factor

    val workBatchSize = getInt("work-batch-size")
    val workBatchInterval = testConfig.getMillisDuration("work-batch-interval")
    val payloadSize = getInt("payload-size")
    val dFactor = getInt("duration-factor")
    val joinRemoveDuration = testConfig.getMillisDuration("join-remove-duration") * dFactor
    val normalThroughputDuration = testConfig.getMillisDuration("normal-throughput-duration") * dFactor
    val highThroughputDuration = testConfig.getMillisDuration("high-throughput-duration") * dFactor
    val supervisionDuration = testConfig.getMillisDuration("supervision-duration") * dFactor
    val supervisionOneIteration = testConfig.getMillisDuration("supervision-one-iteration") * dFactor
    val idleGossipDuration = testConfig.getMillisDuration("idle-gossip-duration") * dFactor
    val expectedTestDuration = testConfig.getMillisDuration("expected-test-duration") * dFactor
    val treeWidth = getInt("tree-width")
    val treeLevels = getInt("tree-levels")
    val convergenceWithinFactor = getDouble("convergence-within-factor")
    val exerciseActors = getBoolean("exercise-actors")

    require(
      numberOfSeedNodes + numberOfNodesJoiningToSeedNodesInitially + numberOfNodesJoiningOneByOneSmall +
      numberOfNodesJoiningOneByOneLarge + numberOfNodesJoiningToOneNode + numberOfNodesJoiningToSeedNodes <= totalNumberOfNodes,
      s"specified number of joining nodes <= ${totalNumberOfNodes}")

    // don't shutdown the 3 nodes hosting the master actors
    require(
      numberOfNodesLeavingOneByOneSmall + numberOfNodesLeavingOneByOneLarge + numberOfNodesLeaving +
      numberOfNodesShutdownOneByOneSmall + numberOfNodesShutdownOneByOneLarge + numberOfNodesShutdown <= totalNumberOfNodes - 3,
      s"specified number of leaving/shutdown nodes <= ${totalNumberOfNodes - 3}")

    require(
      numberOfNodesJoinRemove <= totalNumberOfNodes,
      s"nr-of-nodes-join-remove should be <= ${totalNumberOfNodes}")

    override def toString: String = {
      testConfig.withFallback(ConfigFactory.parseString(s"nrOfNodes=${totalNumberOfNodes}")).root.render
    }
  }

  implicit class FormattedDouble(val d: Double) extends AnyVal {
    def form: String = d.formatted("%.2f")
  }

  final case class ClusterResult(address: Address, duration: Duration, clusterStats: GossipStats)

  final case class AggregatedClusterResult(title: String, duration: Duration, clusterStats: GossipStats)

  /**
   * Central aggregator of cluster statistics and metrics.
   * Reports the result via log periodically and when all
   * expected results has been collected. It shuts down
   * itself when expected results has been collected.
   */
  class ClusterResultAggregator(title: String, expectedResults: Int, settings: Settings)
      extends Actor
      with ActorLogging {
    import settings.infolog
    private val cluster = Cluster(context.system)
    private var reportTo: Option[ActorRef] = None
    private var results = Vector.empty[ClusterResult]
    private var phiValuesObservedByNode = {
      import akka.cluster.Member.addressOrdering
      immutable.SortedMap.empty[Address, immutable.SortedSet[PhiValue]]
    }
    private var clusterStatsObservedByNode = {
      import akka.cluster.Member.addressOrdering
      immutable.SortedMap.empty[Address, CurrentInternalStats]
    }

    def receive = {
      case PhiResult(from, phiValues) => phiValuesObservedByNode += from -> phiValues
      case StatsResult(from, stats)   => clusterStatsObservedByNode += from -> stats
      case ReportTick =>
        if (infolog)
          log.info(s"[${title}] in progress\n\n${formatPhi}\n\n${formatStats}")
      case r: ClusterResult =>
        results :+= r
        if (results.size == expectedResults) {
          val aggregated = AggregatedClusterResult(title, maxDuration, totalGossipStats)
          if (infolog)
            log.info(
              s"[${title}] completed in [${aggregated.duration.toMillis}] ms\n${aggregated.clusterStats}\n\n${formatPhi}\n\n${formatStats}")
          reportTo.foreach { _ ! aggregated }
          context.stop(self)
        }
      case _: CurrentClusterState =>
      case ReportTo(ref)          => reportTo = ref
    }

    def maxDuration = results.map(_.duration).max

    def totalGossipStats = results.foldLeft(GossipStats()) { _ :+ _.clusterStats }

    def format(opt: Option[Double]) = opt match {
      case None    => "N/A"
      case Some(x) => x.form
    }

    def formatPhi: String = {
      if (phiValuesObservedByNode.isEmpty) ""
      else {
        val lines =
          for {
            (monitor, phiValues) <- phiValuesObservedByNode
            phi <- phiValues
          } yield formatPhiLine(monitor, phi.address, phi)

        lines.mkString(formatPhiHeader + "\n", "\n", "")
      }
    }

    def formatPhiHeader: String = "[Monitor]\t[Subject]\t[count]\t[count phi > 1.0]\t[max phi]"

    def formatPhiLine(monitor: Address, subject: Address, phi: PhiValue): String =
      s"${monitor}\t${subject}\t${phi.count}\t${phi.countAboveOne}\t${phi.max.form}"

    def formatStats: String = {
      def f(stats: CurrentInternalStats) = {
        import stats.gossipStats._
        import stats.vclockStats._
        s"ClusterStats($receivedGossipCount, $mergeCount, $sameCount, $newerCount, $olderCount, $versionSize, $seenLatest)"
      }
      clusterStatsObservedByNode
        .map { case (monitor, stats) => s"${monitor}\t${f(stats)}" }
        .mkString("ClusterStats(gossip, merge, same, newer, older, vclockSize, seenLatest)\n", "\n", "")
    }

  }

  /**
   * Keeps cluster statistics and metrics reported by
   * ClusterResultAggregator. Logs the list of historical
   * results when a new AggregatedClusterResult is received.
   */
  class ClusterResultHistory extends Actor with ActorLogging {
    var history = Vector.empty[AggregatedClusterResult]

    def receive = {
      case result: AggregatedClusterResult =>
        history :+= result
        log.info("Cluster result history\n" + formatHistory)
    }

    def formatHistory: String =
      (formatHistoryHeader +: (history.map(formatHistoryLine))).mkString("\n")

    def formatHistoryHeader: String = "[Title]\t[Duration (ms)]\t[GossipStats(gossip, merge, same, newer, older)]"

    def formatHistoryLine(result: AggregatedClusterResult): String =
      s"${result.title}\t${result.duration.toMillis}\t${result.clusterStats}"

  }

  /**
   * Collect phi values of the failure detector and report to the
   * central ClusterResultAggregator.
   */
  class PhiObserver extends Actor with ActorLogging {
    val cluster = Cluster(context.system)
    var reportTo: Option[ActorRef] = None
    val emptyPhiByNode: Map[Address, PhiValue] =
      Map.empty[Address, PhiValue].withDefault(address => PhiValue(address, 0, 0, 0.0))
    var phiByNode = emptyPhiByNode
    var nodes = Set.empty[Address]

    def phi(address: Address): Double = cluster.failureDetector match {
      case reg: DefaultFailureDetectorRegistry[Address] =>
        reg.failureDetector(address) match {
          case Some(fd: PhiAccrualFailureDetector) => fd.phi
          case _                                   => 0.0
        }
      case _ => 0.0
    }

    import context.dispatcher
    val checkPhiTask = context.system.scheduler.schedule(1.second, 1.second, self, PhiTick)

    // subscribe to MemberEvent, re-subscribe when restart
    override def preStart(): Unit = cluster.subscribe(self, classOf[MemberEvent])
    override def postStop(): Unit = {
      cluster.unsubscribe(self)
      checkPhiTask.cancel()
      super.postStop()
    }

    def receive = {
      case PhiTick =>
        nodes.foreach { node =>
          val previous = phiByNode(node)
          val φ = phi(node)
          if (φ > 0 || cluster.failureDetector.isMonitoring(node)) {
            val aboveOne = if (!φ.isInfinite && φ > 1.0) 1 else 0
            phiByNode += node -> PhiValue(
              node,
              previous.countAboveOne + aboveOne,
              previous.count + 1,
              math.max(previous.max, φ))
          }
        }
        val phiSet = immutable.SortedSet.empty[PhiValue] ++ phiByNode.values
        reportTo.foreach { _ ! PhiResult(cluster.selfAddress, phiSet) }
      case state: CurrentClusterState => nodes = state.members.map(_.address)
      case memberEvent: MemberEvent   => nodes += memberEvent.member.address
      case ReportTo(ref) =>
        reportTo.foreach(context.unwatch)
        reportTo = ref
        reportTo.foreach(context.watch)
      case Terminated(ref) =>
        reportTo match {
          case Some(`ref`) => reportTo = None
          case _           =>
        }
      case Reset =>
        phiByNode = emptyPhiByNode
        nodes = Set.empty[Address]
        cluster.unsubscribe(self)
        cluster.subscribe(self, classOf[MemberEvent])

    }
  }

  class StatsObserver extends Actor {
    private val cluster = Cluster(context.system)
    private var reportTo: Option[ActorRef] = None
    private var startStats: Option[GossipStats] = None

    override def preStart(): Unit = cluster.subscribe(self, classOf[CurrentInternalStats])
    override def postStop(): Unit = cluster.unsubscribe(self)

    def receive = {
      case CurrentInternalStats(gossipStats, vclockStats) =>
        val diff = startStats match {
          case None        => { startStats = Some(gossipStats); gossipStats }
          case Some(start) => gossipStats :- start
        }
        val res = StatsResult(cluster.selfAddress, CurrentInternalStats(diff, vclockStats))
        reportTo.foreach { _ ! res }
      case ReportTo(ref) =>
        reportTo.foreach(context.unwatch)
        reportTo = ref
        reportTo.foreach(context.watch)
      case Terminated(ref) =>
        reportTo match {
          case Some(`ref`) => reportTo = None
          case _           =>
        }
      case Reset =>
        startStats = None
      case _: CurrentClusterState => // not interesting here
    }
  }

  /**
   * Master of routers
   *
   * Flow control, to not flood the consumers, is handled by scheduling a
   * batch of messages to be sent to the router when half of the number
   * of outstanding messages remains.
   *
   * It uses a simple message retry mechanism. If an ack of a sent message
   * is not received within a timeout, that message will be resent to the router,
   * infinite number of times.
   *
   * When it receives the `End` command it will stop sending messages to the router,
   * resends continuous, until all outstanding acks have been received, and then
   * finally it replies with `WorkResult` to the sender of the `End` command, and stops
   * itself.
   */
  class Master(settings: StressMultiJvmSpec.Settings, batchInterval: FiniteDuration, tree: Boolean) extends Actor {
    val workers = context.actorOf(FromConfig.props(Props[Worker]), "workers")
    val payload = Array.fill(settings.payloadSize)(ThreadLocalRandom.current.nextInt(127).toByte)
    val retryTimeout = 5.seconds.dilated(context.system)
    val idCounter = Iterator.from(0)
    var sendCounter = 0L
    var ackCounter = 0L
    var outstanding = Map.empty[JobId, JobState]
    var startTime = 0L

    import context.dispatcher
    val resendTask = context.system.scheduler.schedule(3.seconds, 3.seconds, self, RetryTick)

    override def postStop(): Unit = {
      resendTask.cancel()
      super.postStop()
    }

    def receive = {
      case Begin =>
        startTime = System.nanoTime
        self ! SendBatch
        context.become(working)
      case RetryTick =>
    }

    def working: Receive = {
      case Ack(id) =>
        outstanding -= id
        ackCounter += 1
        if (outstanding.size == settings.workBatchSize / 2)
          if (batchInterval == Duration.Zero) self ! SendBatch
          else context.system.scheduler.scheduleOnce(batchInterval, self, SendBatch)
      case SendBatch => sendJobs()
      case RetryTick => resend()
      case End =>
        done(sender())
        context.become(ending(sender()))
    }

    def ending(replyTo: ActorRef): Receive = {
      case Ack(id) =>
        outstanding -= id
        ackCounter += 1
        done(replyTo)
      case SendBatch =>
      case RetryTick => resend()
    }

    def done(replyTo: ActorRef): Unit =
      if (outstanding.isEmpty) {
        val duration = (System.nanoTime - startTime).nanos
        replyTo ! WorkResult(duration, sendCounter, ackCounter)
        context.stop(self)
      }

    def sendJobs(): Unit = {
      (0 until settings.workBatchSize).foreach { _ =>
        send(createJob())
      }
    }

    def createJob(): Job = {
      if (tree)
        TreeJob(
          idCounter.next(),
          payload,
          ThreadLocalRandom.current.nextInt(settings.treeWidth),
          settings.treeLevels,
          settings.treeWidth)
      else SimpleJob(idCounter.next(), payload)
    }

    def resend(): Unit = {
      outstanding.values.foreach { jobState =>
        if (jobState.deadline.isOverdue)
          send(jobState.job)
      }
    }

    def send(job: Job): Unit = {
      outstanding += job.id -> JobState(Deadline.now + retryTimeout, job)
      sendCounter += 1
      workers ! job
    }
  }

  /**
   * Used by Master as routee
   */
  class Worker extends Actor with ActorLogging {
    def receive = {
      case SimpleJob(id, payload)                   => sender() ! Ack(id)
      case TreeJob(id, payload, idx, levels, width) =>
        // create the actors when first TreeJob message is received
        val totalActors = ((width * math.pow(width, levels) - 1) / (width - 1)).toInt
        log.debug(
          "Creating [{}] actors in a tree structure of [{}] levels and each actor has [{}] children",
          totalActors,
          levels,
          width)
        val tree = context.actorOf(Props(classOf[TreeNode], levels, width), "tree")
        tree.forward((idx, SimpleJob(id, payload)))
        context.become(treeWorker(tree))
    }

    def treeWorker(tree: ActorRef): Receive = {
      case SimpleJob(id, payload) => sender() ! Ack(id)
      case TreeJob(id, payload, idx, _, _) =>
        tree.forward((idx, SimpleJob(id, payload)))
    }
  }

  class TreeNode(level: Int, width: Int) extends Actor {
    require(level >= 1)
    def createChild(): Actor = if (level == 1) new Leaf else new TreeNode(level - 1, width)
    val indexedChildren =
      (0 until width).map { i =>
        context.actorOf(Props(createChild()).withDeploy(Deploy.local), name = i.toString)
      } toVector

    def receive = {
      case (idx: Int, job: SimpleJob) if idx < width => indexedChildren(idx).forward((idx, job))
    }
  }

  class Leaf extends Actor {
    def receive = {
      case (_: Int, job: SimpleJob) => sender() ! Ack(job.id)
    }
  }

  /**
   * Used for remote death watch testing
   */
  class Watchee extends Actor {
    def receive = Actor.emptyBehavior
  }

  /**
   * Used for remote supervision testing
   */
  class Supervisor extends Actor {

    var restartCount = 0

    override val supervisorStrategy =
      OneForOneStrategy(maxNrOfRetries = 5, withinTimeRange = 1 minute) {
        case _: Exception =>
          restartCount += 1
          Restart
      }

    def receive = {
      case props: Props     => context.actorOf(props)
      case e: Exception     => context.children.foreach { _ ! e }
      case GetChildrenCount => sender() ! ChildrenCount(context.children.size, restartCount)
      case Reset =>
        require(
          context.children.isEmpty,
          s"ResetChildrenCount not allowed when children exists, [${context.children.size}]")
        restartCount = 0
    }
  }

  /**
   * Child of Supervisor for remote supervision testing
   */
  class RemoteChild extends Actor {
    def receive = {
      case e: Exception => throw e
    }
  }

  case object Begin
  case object End
  case object RetryTick
  case object ReportTick
  case object PhiTick
  final case class PhiResult(from: Address, phiValues: immutable.SortedSet[PhiValue])
  final case class PhiValue(address: Address, countAboveOne: Int, count: Int, max: Double) extends Ordered[PhiValue] {
    import akka.cluster.Member.addressOrdering
    def compare(that: PhiValue) = addressOrdering.compare(this.address, that.address)
  }
  final case class ReportTo(ref: Option[ActorRef])
  final case class StatsResult(from: Address, stats: CurrentInternalStats)

  type JobId = Int
  trait Job { def id: JobId }
  final case class SimpleJob(id: JobId, payload: Any) extends Job
  final case class TreeJob(id: JobId, payload: Any, idx: Int, levels: Int, width: Int) extends Job
  final case class Ack(id: JobId)
  final case class JobState(deadline: Deadline, job: Job)
  final case class WorkResult(duration: Duration, sendCount: Long, ackCount: Long) {
    def retryCount: Long = sendCount - ackCount
    def jobsPerSecond: Double = ackCount * 1000.0 / duration.toMillis
  }
  case object SendBatch
  final case class CreateTree(levels: Int, width: Int)

  case object GetChildrenCount
  final case class ChildrenCount(numberOfChildren: Int, numberOfChildRestarts: Int)
  case object Reset

}

class StressMultiJvmNode1 extends StressSpec
class StressMultiJvmNode2 extends StressSpec
class StressMultiJvmNode3 extends StressSpec
class StressMultiJvmNode4 extends StressSpec
class StressMultiJvmNode5 extends StressSpec
class StressMultiJvmNode6 extends StressSpec
class StressMultiJvmNode7 extends StressSpec
class StressMultiJvmNode8 extends StressSpec
class StressMultiJvmNode9 extends StressSpec
class StressMultiJvmNode10 extends StressSpec
class StressMultiJvmNode11 extends StressSpec
class StressMultiJvmNode12 extends StressSpec
class StressMultiJvmNode13 extends StressSpec

abstract class StressSpec
    extends MultiNodeSpec({
      // Aeron media driver must be started before ActorSystem
      SharedMediaDriverSupport.startMediaDriver(StressMultiJvmSpec)
      StressMultiJvmSpec
    })
    with MultiNodeClusterSpec
    with BeforeAndAfterEach
    with ImplicitSender {

  import StressMultiJvmSpec._

  val settings = new Settings(system.settings.config)
  import settings._

  val identifyProbe = TestProbe()

  var step = 0
  var nbrUsedRoles = 0

  override def beforeEach(): Unit = { step += 1 }

  override def expectedTestDuration = settings.expectedTestDuration

  override def shutdownTimeout: FiniteDuration = 30.seconds.dilated

  override def muteLog(sys: ActorSystem = system): Unit = {
    super.muteLog(sys)
    sys.eventStream.publish(Mute(EventFilter[RuntimeException](pattern = ".*Simulated exception.*")))
    muteDeadLetters(
      classOf[SimpleJob],
      classOf[AggregatedClusterResult],
      SendBatch.getClass,
      classOf[StatsResult],
      classOf[PhiResult],
      RetryTick.getClass)(sys)
  }

  override protected def afterTermination(): Unit = {
    SharedMediaDriverSupport.stopMediaDriver(StressMultiJvmSpec)
    super.afterTermination()
  }

  Runtime.getRuntime.addShutdownHook(new Thread {
    override def run(): Unit = {
      if (SharedMediaDriverSupport.isMediaDriverRunningByThisNode)
        println("Abrupt exit of JVM without closing media driver. This should not happen and may cause test failure.")
    }
  })

  def isArteryEnabled: Boolean = RARP(system).provider.remoteSettings.Artery.Enabled

  def jvmInfo(): String = {
    val runtime = ManagementFactory.getRuntimeMXBean
    val os = ManagementFactory.getOperatingSystemMXBean
    val threads = ManagementFactory.getThreadMXBean
    val mem = ManagementFactory.getMemoryMXBean
    val heap = mem.getHeapMemoryUsage

    val sb = new StringBuilder

    sb.append("Operating system: ")
      .append(os.getName)
      .append(", ")
      .append(os.getArch)
      .append(", ")
      .append(os.getVersion)
    sb.append("\n")
    sb.append("JVM: ")
      .append(runtime.getVmName)
      .append(" ")
      .append(runtime.getVmVendor)
      .append(" ")
      .append(runtime.getVmVersion)
    sb.append("\n")
    sb.append("Processors: ").append(os.getAvailableProcessors)
    sb.append("\n")
    sb.append("Load average: ").append(os.getSystemLoadAverage)
    sb.append("\n")
    sb.append("Thread count: ")
      .append(threads.getThreadCount)
      .append(" (")
      .append(threads.getPeakThreadCount)
      .append(")")
    sb.append("\n")
    sb.append("Heap: ")
      .append((heap.getUsed.toDouble / 1024 / 1024).form)
      .append(" (")
      .append((heap.getInit.toDouble / 1024 / 1024).form)
      .append(" - ")
      .append((heap.getMax.toDouble / 1024 / 1024).form)
      .append(")")
      .append(" MB")
    sb.append("\n")

    import scala.collection.JavaConverters._
    val args = runtime.getInputArguments.asScala.filterNot(_.contains("classpath")).mkString("\n  ")
    sb.append("Args:\n  ").append(args)
    sb.append("\n")

    sb.toString
  }

  val seedNodes = roles.take(numberOfSeedNodes)

  def latestGossipStats = cluster.readView.latestStats.gossipStats

  override def cluster: Cluster = {
    createWorker
    super.cluster
  }

  // always create one worker when the cluster is started
  lazy val createWorker: Unit =
    system.actorOf(Props[Worker], "worker")

  def createResultAggregator(title: String, expectedResults: Int, includeInHistory: Boolean): Unit = {
    runOn(roles.head) {
      val aggregator = system.actorOf(
        Props(classOf[ClusterResultAggregator], title, expectedResults, settings).withDeploy(Deploy.local),
        name = "result" + step)
      if (includeInHistory && infolog) aggregator ! ReportTo(Some(clusterResultHistory))
      else aggregator ! ReportTo(None)
    }
    enterBarrier("result-aggregator-created-" + step)
    runOn(roles.take(nbrUsedRoles): _*) {
      phiObserver ! ReportTo(clusterResultAggregator)
      statsObserver ! Reset
      statsObserver ! ReportTo(clusterResultAggregator)
    }
  }

  def clusterResultAggregator: Option[ActorRef] = {
    system.actorSelection(node(roles.head) / "user" / ("result" + step)).tell(Identify(step), identifyProbe.ref)
    identifyProbe.expectMsgType[ActorIdentity].ref
  }

  lazy val clusterResultHistory =
    if (settings.infolog) system.actorOf(Props[ClusterResultHistory], "resultHistory")
    else system.deadLetters

  lazy val phiObserver = system.actorOf(Props[PhiObserver], "phiObserver")

  lazy val statsObserver = system.actorOf(Props[StatsObserver], "statsObserver")

  def awaitClusterResult(): Unit = {
    runOn(roles.head) {
      clusterResultAggregator match {
        case Some(r) =>
          watch(r)
          expectMsgPF() { case Terminated(a) if a.path == r.path => true }
        case None => // ok, already terminated
      }
    }
    enterBarrier("cluster-result-done-" + step)
  }

  def joinOneByOne(numberOfNodes: Int): Unit = {
    (0 until numberOfNodes).foreach { _ =>
      joinOne()
      nbrUsedRoles += 1
      step += 1
    }
  }

  def convergenceWithin(base: FiniteDuration, nodes: Int): FiniteDuration =
    (base.toMillis * convergenceWithinFactor * nodes).millis

  def joinOne(): Unit = within(5.seconds + convergenceWithin(2.seconds, nbrUsedRoles + 1)) {
    val currentRoles = roles.take(nbrUsedRoles + 1)
    val title = s"join one to ${nbrUsedRoles} nodes cluster"
    createResultAggregator(title, expectedResults = currentRoles.size, includeInHistory = true)
    runOn(currentRoles: _*) {
      reportResult {
        runOn(currentRoles.last) {
          cluster.join(roles.head)
        }
        awaitMembersUp(currentRoles.size, timeout = remainingOrDefault)
      }

    }
    awaitClusterResult()
    enterBarrier("join-one-" + step)
  }

  def joinSeveral(numberOfNodes: Int, toSeedNodes: Boolean): Unit =
    within(10.seconds + convergenceWithin(3.seconds, nbrUsedRoles + numberOfNodes)) {
      val currentRoles = roles.take(nbrUsedRoles + numberOfNodes)
      val joiningRoles = currentRoles.takeRight(numberOfNodes)
      val title =
        s"join ${numberOfNodes} to ${if (toSeedNodes) "seed nodes" else "one node"}, in ${nbrUsedRoles} nodes cluster"
      createResultAggregator(title, expectedResults = currentRoles.size, includeInHistory = true)
      runOn(currentRoles: _*) {
        reportResult {
          runOn(joiningRoles: _*) {
            if (toSeedNodes) cluster.joinSeedNodes(seedNodes.toIndexedSeq.map(address))
            else cluster.join(roles.head)
          }
          awaitMembersUp(currentRoles.size, timeout = remainingOrDefault)
        }

      }
      awaitClusterResult()
      enterBarrier("join-several-" + step)
    }

  def removeOneByOne(numberOfNodes: Int, shutdown: Boolean): Unit = {
    (0 until numberOfNodes).foreach { _ =>
      removeOne(shutdown)
      nbrUsedRoles -= 1
      step += 1
    }
  }

  def removeOne(shutdown: Boolean): Unit = within(25.seconds + convergenceWithin(3.seconds, nbrUsedRoles - 1)) {
    val currentRoles = roles.take(nbrUsedRoles - 1)
    val title = s"${if (shutdown) "shutdown" else "remove"} one from ${nbrUsedRoles} nodes cluster"
    createResultAggregator(title, expectedResults = currentRoles.size, includeInHistory = true)
    val removeRole = roles(nbrUsedRoles - 1)
    val removeAddress = address(removeRole)
    runOn(removeRole) {
      system.actorOf(Props[Watchee], "watchee")
      if (!shutdown) cluster.leave(myself)
    }
    enterBarrier("watchee-created-" + step)
    runOn(roles.head) {
      system.actorSelection(node(removeRole) / "user" / "watchee").tell(Identify("watchee"), identifyProbe.ref)
      val watchee = identifyProbe.expectMsgType[ActorIdentity].ref.get
      watch(watchee)
    }
    enterBarrier("watch-estabilished-" + step)

    runOn(currentRoles: _*) {
      reportResult {
        runOn(roles.head) {
          if (shutdown) {
            if (infolog)
              log.info("Shutting down [{}]", removeAddress)
            testConductor.exit(removeRole, 0).await
          }
        }
        awaitMembersUp(currentRoles.size, timeout = remainingOrDefault)
        awaitAllReachable()
      }
    }

    runOn(roles.head) {
      val expectedPath = RootActorPath(removeAddress) / "user" / "watchee"
      expectMsgPF() {
        case Terminated(a) if a.path == expectedPath => true
      }
    }
    enterBarrier("watch-verified-" + step)

    awaitClusterResult()
    enterBarrier("remove-one-" + step)
  }

  def removeSeveral(numberOfNodes: Int, shutdown: Boolean): Unit =
    within(25.seconds + convergenceWithin(5.seconds, nbrUsedRoles - numberOfNodes)) {
      val currentRoles = roles.take(nbrUsedRoles - numberOfNodes)
      val removeRoles = roles.slice(currentRoles.size, nbrUsedRoles)
      val title = s"${if (shutdown) "shutdown" else "leave"} ${numberOfNodes} in ${nbrUsedRoles} nodes cluster"
      createResultAggregator(title, expectedResults = currentRoles.size, includeInHistory = true)
      runOn(removeRoles: _*) {
        if (!shutdown) cluster.leave(myself)
      }
      runOn(currentRoles: _*) {
        reportResult {
          runOn(roles.head) {
            if (shutdown) removeRoles.foreach { r =>
              if (infolog)
                log.info("Shutting down [{}]", address(r))
              testConductor.exit(r, 0).await
            }
          }
          awaitMembersUp(currentRoles.size, timeout = remainingOrDefault)
          awaitAllReachable()
        }
      }
      awaitClusterResult()
      enterBarrier("remove-several-" + step)
    }

  def reportResult[T](thunk: => T): T = {
    val startTime = System.nanoTime
    val startStats = clusterView.latestStats.gossipStats

    val returnValue = thunk

    clusterResultAggregator.foreach {
      _ ! ClusterResult(cluster.selfAddress, (System.nanoTime - startTime).nanos, latestGossipStats :- startStats)
    }

    returnValue
  }

  def exerciseJoinRemove(title: String, duration: FiniteDuration): Unit = {
    val activeRoles = roles.take(numberOfNodesJoinRemove)
    val loopDuration = 10.seconds + convergenceWithin(4.seconds, nbrUsedRoles + activeRoles.size)
    val rounds = ((duration - loopDuration).toMillis / loopDuration.toMillis).max(1).toInt
    val usedRoles = roles.take(nbrUsedRoles)
    val usedAddresses = usedRoles.map(address(_)).toSet

    @tailrec def loop(
        counter: Int,
        previousAS: Option[ActorSystem],
        allPreviousAddresses: Set[Address]): Option[ActorSystem] = {
      if (counter > rounds) previousAS
      else {
        val t = title + " round " + counter
        runOn(usedRoles: _*) {
          phiObserver ! Reset
          statsObserver ! Reset
        }
        createResultAggregator(t, expectedResults = nbrUsedRoles, includeInHistory = true)
        val (nextAS, nextAddresses) = within(loopDuration) {
          reportResult {
            val nextAS =
              if (activeRoles contains myself) {
                previousAS.foreach { as =>
                  TestKit.shutdownActorSystem(as)
                }
                val sys = ActorSystem(system.name, system.settings.config)
                muteLog(sys)
                Cluster(sys).joinSeedNodes(seedNodes.toIndexedSeq.map(address))
                Some(sys)
              } else previousAS
            runOn(usedRoles: _*) {
              awaitMembersUp(
                nbrUsedRoles + activeRoles.size,
                canNotBePartOfMemberRing = allPreviousAddresses,
                timeout = remainingOrDefault)
              awaitAllReachable()
            }
            val nextAddresses = clusterView.members.map(_.address).diff(usedAddresses)
            runOn(usedRoles: _*) {
              nextAddresses.size should ===(numberOfNodesJoinRemove)
            }

            enterBarrier("join-remove-" + step)
            (nextAS, nextAddresses)
          }
        }
        awaitClusterResult()

        step += 1
        loop(counter + 1, nextAS, nextAddresses)
      }
    }

    loop(1, None, Set.empty).foreach { as =>
      TestKit.shutdownActorSystem(as)
    }
    within(loopDuration) {
      runOn(usedRoles: _*) {
        awaitMembersUp(nbrUsedRoles, timeout = remainingOrDefault)
        awaitAllReachable()
        phiObserver ! Reset
        statsObserver ! Reset
      }
    }
    enterBarrier("join-remove-shutdown-" + step)

  }

  def masterName: String = "master-" + myself.name

  def master: Option[ActorRef] = {
    system.actorSelection("/user/" + masterName).tell(Identify("master"), identifyProbe.ref)
    identifyProbe.expectMsgType[ActorIdentity].ref
  }

  def exerciseRouters(
      title: String,
      duration: FiniteDuration,
      batchInterval: FiniteDuration,
      expectDroppedMessages: Boolean,
      tree: Boolean): Unit =
    within(duration + 10.seconds) {
      nbrUsedRoles should ===(totalNumberOfNodes)
      createResultAggregator(title, expectedResults = nbrUsedRoles, includeInHistory = false)

      val (masterRoles, otherRoles) = roles.take(nbrUsedRoles).splitAt(3)
      runOn(masterRoles: _*) {
        reportResult {
          val m = system.actorOf(
            Props(classOf[Master], settings, batchInterval, tree).withDeploy(Deploy.local),
            name = masterName)
          m ! Begin
          import system.dispatcher
          system.scheduler.scheduleOnce(duration) {
            m.tell(End, testActor)
          }
          val workResult = awaitWorkResult(m)
          workResult.sendCount should be > (0L)
          workResult.ackCount should be > (0L)
          if (!expectDroppedMessages)
            workResult.retryCount should ===(0)

          enterBarrier("routers-done-" + step)
        }
      }
      runOn(otherRoles: _*) {
        reportResult {
          enterBarrier("routers-done-" + step)
        }
      }

      awaitClusterResult()
    }

  def awaitWorkResult(m: ActorRef): WorkResult = {
    val workResult = expectMsgType[WorkResult]
    if (settings.infolog)
      log.info(
        "{} result, [{}] jobs/s, retried [{}] of [{}] msg",
        masterName,
        workResult.jobsPerSecond.form,
        workResult.retryCount,
        workResult.sendCount)
    watch(m)
    expectTerminated(m)
    workResult
  }

  def exerciseSupervision(title: String, duration: FiniteDuration, oneIteration: Duration): Unit =
    within(duration + 10.seconds) {
      val rounds = (duration.toMillis / oneIteration.toMillis).max(1).toInt
      val supervisor = system.actorOf(Props[Supervisor], "supervisor")
      for (count <- 0 until rounds) {
        createResultAggregator(title, expectedResults = nbrUsedRoles, includeInHistory = false)

        val (masterRoles, otherRoles) = roles.take(nbrUsedRoles).splitAt(3)
        runOn(masterRoles: _*) {
          reportResult {
            roles.take(nbrUsedRoles).foreach { r =>
              supervisor ! Props[RemoteChild].withDeploy(Deploy(scope = RemoteScope(address(r))))
            }
            supervisor ! GetChildrenCount
            expectMsgType[ChildrenCount] should ===(ChildrenCount(nbrUsedRoles, 0))

            (1 to 5).foreach { _ =>
              supervisor ! new RuntimeException("Simulated exception")
            }
            awaitAssert {
              supervisor ! GetChildrenCount
              val c = expectMsgType[ChildrenCount]
              c should ===(ChildrenCount(nbrUsedRoles, 5 * nbrUsedRoles))
            }

            // after 5 restart attempts the children should be stopped
            supervisor ! new RuntimeException("Simulated exception")
            awaitAssert {
              supervisor ! GetChildrenCount
              val c = expectMsgType[ChildrenCount]
              // zero children
              c should ===(ChildrenCount(0, 6 * nbrUsedRoles))
            }
            supervisor ! Reset

          }
          enterBarrier("supervision-done-" + step)
        }

        runOn(otherRoles: _*) {
          reportResult {
            enterBarrier("supervision-done-" + step)
          }
        }

        awaitClusterResult()
        step += 1
      }
    }

  def idleGossip(title: String): Unit = {
    createResultAggregator(title, expectedResults = nbrUsedRoles, includeInHistory = true)
    reportResult {
      clusterView.members.size should ===(nbrUsedRoles)
      Thread.sleep(idleGossipDuration.toMillis)
      clusterView.members.size should ===(nbrUsedRoles)
    }
    awaitClusterResult()
  }

  "A cluster under stress" must {

    "log settings" taggedAs LongRunningTest in {
      if (infolog) {
        log.info("StressSpec JVM:\n{}", jvmInfo)
        runOn(roles.head) {
          log.info("StressSpec settings:\n{}", settings)
        }
      }
      enterBarrier("after-" + step)
    }

    // FIXME issue #21810
    // note: there must be one test step before pending, otherwise afterTermination will not run
    if (isArteryEnabled) pending

    "join seed nodes" taggedAs LongRunningTest in within(30 seconds) {

      val otherNodesJoiningSeedNodes =
        roles.slice(numberOfSeedNodes, numberOfSeedNodes + numberOfNodesJoiningToSeedNodesInitially)
      val size = seedNodes.size + otherNodesJoiningSeedNodes.size

      createResultAggregator("join seed nodes", expectedResults = size, includeInHistory = true)

      runOn((seedNodes ++ otherNodesJoiningSeedNodes): _*) {
        reportResult {
          cluster.joinSeedNodes(seedNodes.toIndexedSeq.map(address))
          awaitMembersUp(size, timeout = remainingOrDefault)
        }
      }

      awaitClusterResult()

      nbrUsedRoles += size
      enterBarrier("after-" + step)
    }

    "start routers that are running while nodes are joining" taggedAs LongRunningTest in {
      runOn(roles.take(3): _*) {
        system.actorOf(
          Props(classOf[Master], settings, settings.workBatchInterval, false).withDeploy(Deploy.local),
          name = masterName) ! Begin
      }
    }

    "join nodes one-by-one to small cluster" taggedAs LongRunningTest in {
      joinOneByOne(numberOfNodesJoiningOneByOneSmall)
      enterBarrier("after-" + step)
    }

    "join several nodes to one node" taggedAs LongRunningTest in {
      joinSeveral(numberOfNodesJoiningToOneNode, toSeedNodes = false)
      nbrUsedRoles += numberOfNodesJoiningToOneNode
      enterBarrier("after-" + step)
    }

    "join several nodes to seed nodes" taggedAs LongRunningTest in {
      if (numberOfNodesJoiningToSeedNodes > 0) {
        joinSeveral(numberOfNodesJoiningToSeedNodes, toSeedNodes = true)
        nbrUsedRoles += numberOfNodesJoiningToSeedNodes
      }
      enterBarrier("after-" + step)
    }

    "join nodes one-by-one to large cluster" taggedAs LongRunningTest in {
      joinOneByOne(numberOfNodesJoiningOneByOneLarge)
      enterBarrier("after-" + step)
    }

    "end routers that are running while nodes are joining" taggedAs LongRunningTest in within(30.seconds) {
      if (exerciseActors) {
        runOn(roles.take(3): _*) {
          master match {
            case Some(m) =>
              m.tell(End, testActor)
              val workResult = awaitWorkResult(m)
              workResult.retryCount should ===(0)
              workResult.sendCount should be > (0L)
              workResult.ackCount should be > (0L)
            case None => fail("master not running")
          }
        }
      }
      enterBarrier("after-" + step)
    }

    "use routers with normal throughput" taggedAs LongRunningTest in {
      if (exerciseActors) {
        exerciseRouters(
          "use routers with normal throughput",
          normalThroughputDuration,
          batchInterval = workBatchInterval,
          expectDroppedMessages = false,
          tree = false)
      }
      enterBarrier("after-" + step)
    }

    "use routers with high throughput" taggedAs LongRunningTest in {
      if (exerciseActors) {
        exerciseRouters(
          "use routers with high throughput",
          highThroughputDuration,
          batchInterval = Duration.Zero,
          expectDroppedMessages = false,
          tree = false)
      }
      enterBarrier("after-" + step)
    }

    "use many actors with normal throughput" taggedAs LongRunningTest in {
      if (exerciseActors) {
        exerciseRouters(
          "use many actors with normal throughput",
          normalThroughputDuration,
          batchInterval = workBatchInterval,
          expectDroppedMessages = false,
          tree = true)
      }
      enterBarrier("after-" + step)
    }

    "use many actors with high throughput" taggedAs LongRunningTest in {
      if (exerciseActors) {
        exerciseRouters(
          "use many actors with high throughput",
          highThroughputDuration,
          batchInterval = Duration.Zero,
          expectDroppedMessages = false,
          tree = true)
      }
      enterBarrier("after-" + step)
    }

    "exercise join/remove/join/remove" taggedAs LongRunningTest in {
      exerciseJoinRemove("exercise join/remove", joinRemoveDuration)
      enterBarrier("after-" + step)
    }

    "exercise supervision" taggedAs LongRunningTest in {
      if (exerciseActors) {
        exerciseSupervision("exercise supervision", supervisionDuration, supervisionOneIteration)
      }
      enterBarrier("after-" + step)
    }

    "gossip when idle" taggedAs LongRunningTest in {
      idleGossip("idle gossip")
      enterBarrier("after-" + step)
    }

    "start routers that are running while nodes are removed" taggedAs LongRunningTest in {
      if (exerciseActors) {
        runOn(roles.take(3): _*) {
          system.actorOf(
            Props(classOf[Master], settings, settings.workBatchInterval, false).withDeploy(Deploy.local),
            name = masterName) ! Begin
        }
      }
      enterBarrier("after-" + step)
    }

    "leave nodes one-by-one from large cluster" taggedAs LongRunningTest in {
      removeOneByOne(numberOfNodesLeavingOneByOneLarge, shutdown = false)
      enterBarrier("after-" + step)
    }

    "shutdown nodes one-by-one from large cluster" taggedAs LongRunningTest in {
      removeOneByOne(numberOfNodesShutdownOneByOneLarge, shutdown = true)
      enterBarrier("after-" + step)
    }

    "leave several nodes" taggedAs LongRunningTest in {
      removeSeveral(numberOfNodesLeaving, shutdown = false)
      nbrUsedRoles -= numberOfNodesLeaving
      enterBarrier("after-" + step)
    }

    "shutdown several nodes" taggedAs LongRunningTest in {
      removeSeveral(numberOfNodesShutdown, shutdown = true)
      nbrUsedRoles -= numberOfNodesShutdown
      enterBarrier("after-" + step)
    }

    "shutdown nodes one-by-one from small cluster" taggedAs LongRunningTest in {
      removeOneByOne(numberOfNodesShutdownOneByOneSmall, shutdown = true)
      enterBarrier("after-" + step)
    }

    "leave nodes one-by-one from small cluster" taggedAs LongRunningTest in {
      removeOneByOne(numberOfNodesLeavingOneByOneSmall, shutdown = false)
      enterBarrier("after-" + step)
    }

    "end routers that are running while nodes are removed" taggedAs LongRunningTest in within(30.seconds) {
      if (exerciseActors) {
        runOn(roles.take(3): _*) {
          master match {
            case Some(m) =>
              m.tell(End, testActor)
              val workResult = awaitWorkResult(m)
              workResult.sendCount should be > (0L)
              workResult.ackCount should be > (0L)
            case None => fail("master not running")
          }
        }
      }
      enterBarrier("after-" + step)
    }

    "log jvm info" taggedAs LongRunningTest in {
      if (infolog) {
        log.info("StressSpec JVM:\n{}", jvmInfo)
      }
      enterBarrier("after-" + step)
    }
  }
}
