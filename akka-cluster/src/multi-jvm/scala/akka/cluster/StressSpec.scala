/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
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
import akka.cluster.ClusterEvent.ClusterMetricsChanged
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.StandardMetrics.Cpu
import akka.cluster.StandardMetrics.HeapMemory
import akka.remote.DefaultFailureDetectorRegistry
import akka.remote.PhiAccrualFailureDetector
import akka.remote.RemoteScope
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.routing.FromConfig
import akka.testkit._
import akka.testkit.TestEvent._

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
 * 7. leave and shutdown nodes in various ways
 * 8. while nodes are removed remote death watch is also exercised
 * 9. while nodes are removed a few cluster aware routers are also working
 */
object StressMultiJvmSpec extends MultiNodeConfig {

  // Note that this test uses default configuration,
  // not MultiNodeClusterSpec.clusterConfig
  commonConfig(ConfigFactory.parseString("""
    akka.test.cluster-stress-spec {
      # scale the nr-of-nodes* settings with this factor
      nr-of-nodes-factor = 1
      nr-of-nodes = 13
      # not scaled
      nr-of-seed-nodes = 3
      nr-of-nodes-joining-to-seed-initally = 2
      nr-of-nodes-joining-one-by-one-small = 2
      nr-of-nodes-joining-one-by-one-large = 2
      nr-of-nodes-joining-to-one = 2
      nr-of-nodes-joining-to-seed = 2
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
      expected-test-duration = 600s
      # actors are created in a tree structure defined
      # by tree-width (number of children for each actor) and
      # tree-levels, total number of actors can be calculated by
      # (width * math.pow(width, levels) - 1) / (width - 1)
      tree-width = 5
      tree-levels = 4
      report-metrics-interval = 10s
      # scale convergence within timeouts with this factor
      convergence-within-factor = 1.0
    }

    akka.actor.provider = akka.cluster.ClusterActorRefProvider
    akka.cluster {
      auto-join = off
      auto-down = on
      publish-stats-interval = 0 s # always, when it happens
    }
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel = INFO
    akka.remote.log-remote-lifecycle-events = off

    akka.actor.deployment {
      /master-node-1/workers {
        router = round-robin
        nr-of-instances = 100
        cluster {
          enabled = on
          max-nr-of-instances-per-node = 1
          allow-local-routees = off
        }
      }
      /master-node-2/workers {
        router = round-robin
        nr-of-instances = 100
        cluster {
          enabled = on
          routees-path = "/user/worker"
          allow-local-routees = off
        }
      }
      /master-node-3/workers = {
        router = adaptive
        nr-of-instances = 100
        cluster {
          enabled = on
          max-nr-of-instances-per-node = 1
          allow-local-routees = off
        }
      }
    }
    """))

  class Settings(conf: Config) {
    private val testConfig = conf.getConfig("akka.test.cluster-stress-spec")
    import testConfig._

    private def getDuration(name: String): FiniteDuration = Duration(getMilliseconds(name), MILLISECONDS)

    val nFactor = getInt("nr-of-nodes-factor")
    val totalNumberOfNodes = getInt("nr-of-nodes") * nFactor ensuring (
      _ >= 10, "nr-of-nodes must be >= 10")
    val numberOfSeedNodes = getInt("nr-of-seed-nodes") // not scaled by nodes factor
    val numberOfNodesJoiningToSeedNodesInitially = getInt("nr-of-nodes-joining-to-seed-initally") * nFactor
    val numberOfNodesJoiningOneByOneSmall = getInt("nr-of-nodes-joining-one-by-one-small") * nFactor
    val numberOfNodesJoiningOneByOneLarge = getInt("nr-of-nodes-joining-one-by-one-large") * nFactor
    val numberOfNodesJoiningToOneNode = getInt("nr-of-nodes-joining-to-one") * nFactor
    val numberOfNodesJoiningToSeedNodes = getInt("nr-of-nodes-joining-to-seed") * nFactor
    val numberOfNodesLeavingOneByOneSmall = getInt("nr-of-nodes-leaving-one-by-one-small") * nFactor
    val numberOfNodesLeavingOneByOneLarge = getInt("nr-of-nodes-leaving-one-by-one-large") * nFactor
    val numberOfNodesLeaving = getInt("nr-of-nodes-leaving") * nFactor
    val numberOfNodesShutdownOneByOneSmall = getInt("nr-of-nodes-shutdown-one-by-one-small") * nFactor
    val numberOfNodesShutdownOneByOneLarge = getInt("nr-of-nodes-shutdown-one-by-one-large") * nFactor
    val numberOfNodesShutdown = getInt("nr-of-nodes-shutdown") * nFactor
    val numberOfNodesJoinRemove = getInt("nr-of-nodes-join-remove") // not scaled by nodes factor

    val workBatchSize = getInt("work-batch-size")
    val workBatchInterval = Duration(getMilliseconds("work-batch-interval"), MILLISECONDS)
    val payloadSize = getInt("payload-size")
    val dFactor = getInt("duration-factor")
    val joinRemoveDuration = getDuration("join-remove-duration") * dFactor
    val normalThroughputDuration = getDuration("normal-throughput-duration") * dFactor
    val highThroughputDuration = getDuration("high-throughput-duration") * dFactor
    val supervisionDuration = getDuration("supervision-duration") * dFactor
    val supervisionOneIteration = getDuration("supervision-one-iteration") * dFactor
    val expectedTestDuration = getDuration("expected-test-duration") * dFactor
    val treeWidth = getInt("tree-width")
    val treeLevels = getInt("tree-levels")
    val reportMetricsInterval = getDuration("report-metrics-interval")
    val convergenceWithinFactor = getDouble("convergence-within-factor")

    require(numberOfSeedNodes + numberOfNodesJoiningToSeedNodesInitially + numberOfNodesJoiningOneByOneSmall +
      numberOfNodesJoiningOneByOneLarge + numberOfNodesJoiningToOneNode + numberOfNodesJoiningToSeedNodes <= totalNumberOfNodes,
      s"specified number of joining nodes <= ${totalNumberOfNodes}")

    // don't shutdown the 3 nodes hosting the master actors
    require(numberOfNodesLeavingOneByOneSmall + numberOfNodesLeavingOneByOneLarge + numberOfNodesLeaving +
      numberOfNodesShutdownOneByOneSmall + numberOfNodesShutdownOneByOneLarge + numberOfNodesShutdown <= totalNumberOfNodes - 3,
      s"specified number of leaving/shutdown nodes <= ${totalNumberOfNodes - 3}")

    require(numberOfNodesJoinRemove <= totalNumberOfNodes, s"nr-of-nodes-join-remove must be <= ${totalNumberOfNodes}")
  }

  // FIXME configurable number of nodes
  for (n ← 1 to 13) role("node-" + n)

  implicit class FormattedDouble(val d: Double) extends AnyVal {
    def form: String = d.formatted("%.2f")
  }

  case class ClusterResult(
    address: Address,
    duration: Duration,
    clusterStats: ClusterStats)

  case class AggregatedClusterResult(title: String, duration: Duration, clusterStats: ClusterStats)

  /**
   * Central aggregator of cluster statistics and metrics.
   * Reports the result via log periodically and when all
   * expected results has been collected. It shuts down
   * itself when expected results has been collected.
   */
  class ClusterResultAggregator(title: String, expectedResults: Int, reportMetricsInterval: FiniteDuration) extends Actor with ActorLogging {
    val cluster = Cluster(context.system)
    var reportTo: Option[ActorRef] = None
    var results = Vector.empty[ClusterResult]
    var nodeMetrics = Set.empty[NodeMetrics]
    var phiValuesObservedByNode = {
      import akka.cluster.Member.addressOrdering
      immutable.SortedMap.empty[Address, immutable.SortedSet[PhiValue]]
    }
    var clusterStatsObservedByNode = {
      import akka.cluster.Member.addressOrdering
      immutable.SortedMap.empty[Address, ClusterStats]
    }

    import context.dispatcher
    val reportMetricsTask = context.system.scheduler.schedule(
      reportMetricsInterval, reportMetricsInterval, self, ReportTick)

    // subscribe to ClusterMetricsChanged, re-subscribe when restart
    override def preStart(): Unit = cluster.subscribe(self, classOf[ClusterMetricsChanged])
    override def postStop(): Unit = {
      cluster.unsubscribe(self)
      reportMetricsTask.cancel()
      super.postStop()
    }

    def receive = {
      case ClusterMetricsChanged(clusterMetrics) ⇒ nodeMetrics = clusterMetrics
      case PhiResult(from, phiValues)            ⇒ phiValuesObservedByNode += from -> phiValues
      case StatsResult(from, stats)              ⇒ clusterStatsObservedByNode += from -> stats
      case ReportTick ⇒
        log.info(s"[${title}] in progress\n${formatMetrics}\n\n${formatPhi}\n\n${formatStats}")
      case r: ClusterResult ⇒
        results :+= r
        if (results.size == expectedResults) {
          val aggregated = AggregatedClusterResult(title, maxDuration, totalClusterStats)
          log.info(s"[${title}] completed in [${aggregated.duration.toMillis}] ms\n${aggregated.clusterStats}\n${formatMetrics}\n\n${formatPhi}\n\n${formatStats}")
          reportTo foreach { _ ! aggregated }
          context stop self
        }
      case _: CurrentClusterState ⇒
      case ReportTo(ref)          ⇒ reportTo = ref
    }

    def maxDuration = results.map(_.duration).max

    def totalClusterStats = results.foldLeft(ClusterStats()) { _ :+ _.clusterStats }

    def formatMetrics: String = {
      import akka.cluster.Member.addressOrdering
      (formatMetricsHeader +: (nodeMetrics.toSeq.sortBy(_.address) map formatMetricsLine)).mkString("\n")
    }

    def formatMetricsHeader: String = "Node\tHeap (MB)\tCPU (%)\tLoad"

    def formatMetricsLine(nodeMetrics: NodeMetrics): String = {
      val heap = nodeMetrics match {
        case HeapMemory(address, timestamp, used, committed, max) ⇒
          (used.doubleValue / 1024 / 1024).form
        case _ ⇒ ""
      }
      val cpuAndLoad = nodeMetrics match {
        case Cpu(address, timestamp, loadOption, cpuOption, processors) ⇒
          format(cpuOption) + "\t" + format(loadOption)
        case _ ⇒ "N/A\tN/A"
      }
      s"${nodeMetrics.address}\t${heap}\t${cpuAndLoad}"
    }

    def format(opt: Option[Double]) = opt match {
      case None    ⇒ "N/A"
      case Some(x) ⇒ x.form
    }

    def formatPhi: String = {
      if (phiValuesObservedByNode.isEmpty) ""
      else {
        import akka.cluster.Member.addressOrdering
        val lines =
          for {
            (monitor, phiValues) ← phiValuesObservedByNode
            phi ← phiValues
          } yield formatPhiLine(monitor, phi.address, phi)

        lines.mkString(formatPhiHeader + "\n", "\n", "")
      }
    }

    def formatPhiHeader: String = "Monitor\tSubject\tcount\tcount phi > 1.0\tmax phi"

    def formatPhiLine(monitor: Address, subject: Address, phi: PhiValue): String =
      s"${monitor}\t${subject}\t${phi.count}\t${phi.countAboveOne}\t${phi.max.form}"

    def formatStats: String =
      (clusterStatsObservedByNode map { case (monitor, stats) ⇒ s"${monitor}\t${stats}" }).
        mkString("ClusterStats\n", "\n", "")
  }

  /**
   * Keeps cluster statistics and metrics reported by
   * ClusterResultAggregator. Logs the list of historical
   * results when a new AggregatedClusterResult is received.
   */
  class ClusterResultHistory extends Actor with ActorLogging {
    var history = Vector.empty[AggregatedClusterResult]

    def receive = {
      case result: AggregatedClusterResult ⇒
        history :+= result
        log.info("Cluster result history\n" + formatHistory)
    }

    def formatHistory: String =
      (formatHistoryHeader +: (history map formatHistoryLine)).mkString("\n")

    def formatHistoryHeader: String = "title\tduration (ms)\tcluster stats"

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
    val emptyPhiByNode = Map.empty[Address, PhiValue].withDefault(address ⇒ PhiValue(address, 0, 0, 0.0))
    var phiByNode = emptyPhiByNode
    var nodes = Set.empty[Address]

    def phi(address: Address): Double = cluster.failureDetector match {
      case reg: DefaultFailureDetectorRegistry[Address] ⇒ reg.failureDetector(address) match {
        case Some(fd: PhiAccrualFailureDetector) ⇒ fd.phi
        case _                                   ⇒ 0.0
      }
      case _ ⇒ 0.0
    }

    import context.dispatcher
    val checkPhiTask = context.system.scheduler.schedule(
      1.second, 1.second, self, PhiTick)

    // subscribe to MemberEvent, re-subscribe when restart
    override def preStart(): Unit = cluster.subscribe(self, classOf[MemberEvent])
    override def postStop(): Unit = {
      cluster.unsubscribe(self)
      checkPhiTask.cancel()
      super.postStop()
    }

    def receive = {
      case PhiTick ⇒
        nodes foreach { node ⇒
          val previous = phiByNode(node)
          val φ = phi(node)
          if (φ > 0 || cluster.failureDetector.isMonitoring(node)) {
            val aboveOne = if (!φ.isInfinite && φ > 1.0) 1 else 0
            phiByNode += node -> PhiValue(node, previous.countAboveOne + aboveOne, previous.count + 1,
              math.max(previous.max, φ))
          }
        }
        val phiSet = immutable.SortedSet.empty[PhiValue] ++ phiByNode.values
        reportTo foreach { _ ! PhiResult(cluster.selfAddress, phiSet) }
      case state: CurrentClusterState ⇒ nodes = state.members.map(_.address)
      case memberEvent: MemberEvent   ⇒ nodes += memberEvent.member.address
      case ReportTo(ref)              ⇒ reportTo = ref
      case Reset ⇒
        phiByNode = emptyPhiByNode
        nodes = Set.empty[Address]
        cluster.unsubscribe(self)
        cluster.subscribe(self, classOf[MemberEvent])

    }
  }

  class StatsObserver extends Actor {
    val cluster = Cluster(context.system)
    var reportTo: Option[ActorRef] = None
    var startStats = cluster.readView.latestStats

    import context.dispatcher
    val checkStatsTask = context.system.scheduler.schedule(
      1.second, 1.second, self, StatsTick)

    override def postStop(): Unit = {
      checkStatsTask.cancel()
      super.postStop()
    }

    def receive = {
      case StatsTick ⇒
        val res = StatsResult(cluster.selfAddress, cluster.readView.latestStats :- startStats)
        reportTo foreach { _ ! res }
      case ReportTo(ref) ⇒
        reportTo = ref
      case Reset ⇒
        startStats = cluster.readView.latestStats
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
    val workers = context.actorOf(Props[Worker].withRouter(FromConfig), "workers")
    val payload = Array.fill(settings.payloadSize)(ThreadLocalRandom.current.nextInt(127).toByte)
    val retryTimeout = 5.seconds.dilated(context.system)
    val idCounter = Iterator from 0
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
      case Begin ⇒
        startTime = System.nanoTime
        self ! SendBatch
        context.become(working)
      case RetryTick ⇒
    }

    def working: Receive = {
      case Ack(id) ⇒
        outstanding -= id
        ackCounter += 1
        if (outstanding.size == settings.workBatchSize / 2)
          if (batchInterval == Duration.Zero) self ! SendBatch
          else context.system.scheduler.scheduleOnce(batchInterval, self, SendBatch)
      case SendBatch ⇒ sendJobs()
      case RetryTick ⇒ resend()
      case End ⇒
        done(sender)
        context.become(ending(sender))
    }

    def ending(replyTo: ActorRef): Receive = {
      case Ack(id) ⇒
        outstanding -= id
        ackCounter += 1
        done(replyTo)
      case SendBatch ⇒
      case RetryTick ⇒ resend()
    }

    def done(replyTo: ActorRef): Unit =
      if (outstanding.isEmpty) {
        val duration = (System.nanoTime - startTime).nanos
        replyTo ! WorkResult(duration, sendCounter, ackCounter)
        context stop self
      }

    def sendJobs(): Unit = {
      0 until settings.workBatchSize foreach { _ ⇒
        send(createJob())
      }
    }

    def createJob(): Job = {
      if (tree) TreeJob(idCounter.next(), payload, ThreadLocalRandom.current.nextInt(settings.treeWidth),
        settings.treeLevels, settings.treeWidth)
      else SimpleJob(idCounter.next(), payload)
    }

    def resend(): Unit = {
      outstanding.values foreach { jobState ⇒
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
      case SimpleJob(id, payload) ⇒ sender ! Ack(id)
      case TreeJob(id, payload, idx, levels, width) ⇒
        // create the actors when first TreeJob message is received
        val totalActors = ((width * math.pow(width, levels) - 1) / (width - 1)).toInt
        log.info("Creating [{}] actors in a tree structure of [{}] levels and each actor has [{}] children",
          totalActors, levels, width)
        val tree = context.actorOf(Props(new TreeNode(levels, width)), "tree")
        tree forward (idx, SimpleJob(id, payload))
        context.become(treeWorker(tree))
    }

    def treeWorker(tree: ActorRef): Receive = {
      case SimpleJob(id, payload) ⇒ sender ! Ack(id)
      case TreeJob(id, payload, idx, _, _) ⇒
        tree forward (idx, SimpleJob(id, payload))
    }
  }

  class TreeNode(level: Int, width: Int) extends Actor {
    require(level >= 1)
    def createChild(): Actor = if (level == 1) new Leaf else new TreeNode(level - 1, width)
    val indexedChildren =
      0 until width map { i ⇒ context.actorOf(Props(createChild()), name = i.toString) } toVector

    def receive = {
      case (idx: Int, job: SimpleJob) if idx < width ⇒ indexedChildren(idx) forward (idx, job)
    }
  }

  class Leaf extends Actor {
    def receive = {
      case (_: Int, job: SimpleJob) ⇒ sender ! Ack(job.id)
    }
  }

  /**
   * Used for remote death watch testing
   */
  class Watchee extends Actor {
    def receive = {
      case Ping ⇒ sender ! Pong
    }
  }

  /**
   * Used for remote supervision testing
   */
  class Supervisor extends Actor {

    var restartCount = 0

    override val supervisorStrategy =
      OneForOneStrategy(maxNrOfRetries = 5, withinTimeRange = 1 minute) {
        case _: Exception ⇒
          restartCount += 1
          Restart
      }

    def receive = {
      case props: Props     ⇒ context.actorOf(props)
      case e: Exception     ⇒ context.children foreach { _ ! e }
      case GetChildrenCount ⇒ sender ! ChildrenCount(context.children.size, restartCount)
      case Reset ⇒
        require(context.children.isEmpty,
          s"ResetChildrenCount not allowed when children exists, [${context.children.size}]")
        restartCount = 0
    }
  }

  /**
   * Child of Supervisor for remote supervision testing
   */
  class RemoteChild extends Actor {
    def receive = {
      case e: Exception ⇒ throw e
    }
  }

  case object Begin
  case object End
  case object RetryTick
  case object ReportTick
  case object PhiTick
  case class PhiResult(from: Address, phiValues: immutable.SortedSet[PhiValue])
  case class PhiValue(address: Address, countAboveOne: Int, count: Int, max: Double) extends Ordered[PhiValue] {
    import akka.cluster.Member.addressOrdering
    def compare(that: PhiValue) = addressOrdering.compare(this.address, that.address)
  }
  case class ReportTo(ref: Option[ActorRef])
  case object StatsTick
  case class StatsResult(from: Address, stats: ClusterStats)

  type JobId = Int
  trait Job { def id: JobId }
  case class SimpleJob(id: JobId, payload: Any) extends Job
  case class TreeJob(id: JobId, payload: Any, idx: Int, levels: Int, width: Int) extends Job
  case class Ack(id: JobId)
  case class JobState(deadline: Deadline, job: Job)
  case class WorkResult(duration: Duration, sendCount: Long, ackCount: Long) {
    def retryCount: Long = sendCount - ackCount
    def jobsPerSecond: Double = ackCount * 1000.0 / duration.toMillis
  }
  case object SendBatch
  case class CreateTree(levels: Int, width: Int)

  case object GetChildrenCount
  case class ChildrenCount(numberOfChildren: Int, numberOfChildRestarts: Int)
  case object Reset

  case object Ping
  case object Pong

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
  extends MultiNodeSpec(StressMultiJvmSpec)
  with MultiNodeClusterSpec with BeforeAndAfterEach with ImplicitSender {

  import StressMultiJvmSpec._
  import ClusterEvent._

  val settings = new Settings(system.settings.config)
  import settings._

  var step = 0
  var nbrUsedRoles = 0

  override def beforeEach(): Unit = { step += 1 }

  override def expectedTestDuration = settings.expectedTestDuration

  override def muteLog(sys: ActorSystem = system): Unit = {
    super.muteLog(sys)
    sys.eventStream.publish(Mute(EventFilter[RuntimeException](pattern = ".*Simulated exception.*")))
    sys.eventStream.publish(Mute(EventFilter.warning(pattern = ".*PhiResult.*")))
    sys.eventStream.publish(Mute(EventFilter.warning(pattern = ".*SendBatch.*")))
    sys.eventStream.publish(Mute(EventFilter.warning(pattern = ".*ClusterStats.*")))
  }

  val seedNodes = roles.take(numberOfSeedNodes)

  override def cluster: Cluster = {
    createWorker
    super.cluster
  }

  // always create one worker when the cluster is started
  lazy val createWorker: Unit =
    system.actorOf(Props[Worker], "worker")

  def createResultAggregator(title: String, expectedResults: Int, includeInHistory: Boolean): Unit = {
    runOn(roles.head) {
      val aggregator = system.actorOf(Props(new ClusterResultAggregator(title, expectedResults, reportMetricsInterval)),
        name = "result" + step)
      if (includeInHistory) aggregator ! ReportTo(Some(clusterResultHistory))
      else aggregator ! ReportTo(None)
    }
    enterBarrier("result-aggregator-created-" + step)
    runOn(roles.take(nbrUsedRoles): _*) {
      phiObserver ! ReportTo(Some(clusterResultAggregator))
      statsObserver ! Reset
      statsObserver ! ReportTo(Some(clusterResultAggregator))
    }
  }

  def clusterResultAggregator: ActorRef = system.actorFor(node(roles.head) / "user" / ("result" + step))

  lazy val clusterResultHistory = system.actorOf(Props[ClusterResultHistory], "resultHistory")

  lazy val phiObserver = system.actorOf(Props[PhiObserver], "phiObserver")

  lazy val statsObserver = system.actorOf(Props[StatsObserver], "statsObserver")

  def awaitClusterResult: Unit = {
    runOn(roles.head) {
      val r = clusterResultAggregator
      watch(r)
      expectMsgPF(remaining) { case Terminated(a) if a.path == r.path ⇒ true }
    }
    enterBarrier("cluster-result-done-" + step)
  }

  def joinOneByOne(numberOfNodes: Int): Unit = {
    0 until numberOfNodes foreach { _ ⇒
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
        awaitMembersUp(currentRoles.size, timeout = remaining)
      }

    }
    awaitClusterResult
    enterBarrier("join-one-" + step)
  }

  def joinSeveral(numberOfNodes: Int, toSeedNodes: Boolean): Unit =
    within(10.seconds + convergenceWithin(3.seconds, nbrUsedRoles + numberOfNodes)) {
      val currentRoles = roles.take(nbrUsedRoles + numberOfNodes)
      val joiningRoles = currentRoles.takeRight(numberOfNodes)
      val title = s"join ${numberOfNodes} to ${if (toSeedNodes) "seed nodes" else "one node"}, in ${nbrUsedRoles} nodes cluster"
      createResultAggregator(title, expectedResults = currentRoles.size, includeInHistory = true)
      runOn(currentRoles: _*) {
        reportResult {
          runOn(joiningRoles: _*) {
            if (toSeedNodes) cluster.joinSeedNodes(seedNodes.toIndexedSeq map address)
            else cluster.join(roles.head)
          }
          awaitMembersUp(currentRoles.size, timeout = remaining)
        }

      }
      awaitClusterResult
      enterBarrier("join-several-" + step)
    }

  def removeOneByOne(numberOfNodes: Int, shutdown: Boolean): Unit = {
    0 until numberOfNodes foreach { _ ⇒
      removeOne(shutdown)
      nbrUsedRoles -= 1
      step += 1
    }
  }

  def removeOne(shutdown: Boolean): Unit = within(10.seconds + convergenceWithin(3.seconds, nbrUsedRoles - 1)) {
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
      system.actorFor(node(removeRole) / "user" / "watchee") ! Ping
      expectMsg(Pong)
      watch(lastSender)
    }
    enterBarrier("watch-estabilished-" + step)

    runOn(currentRoles: _*) {
      reportResult {
        runOn(roles.head) {
          if (shutdown) {
            log.info("Shutting down [{}]", removeAddress)
            testConductor.shutdown(removeRole, 0).await
          }
        }
        awaitMembersUp(currentRoles.size, timeout = remaining)
      }
    }

    runOn(roles.head) {
      val expectedPath = RootActorPath(removeAddress) / "user" / "watchee"
      expectMsgPF(remaining) {
        case Terminated(a) if a.path == expectedPath ⇒ true
      }
    }
    enterBarrier("watch-verified-" + step)

    awaitClusterResult
    enterBarrier("remove-one-" + step)
  }

  def removeSeveral(numberOfNodes: Int, shutdown: Boolean): Unit =
    within(10.seconds + convergenceWithin(5.seconds, nbrUsedRoles - numberOfNodes)) {
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
            if (shutdown) removeRoles.foreach { r ⇒
              log.info("Shutting down [{}]", address(r))
              testConductor.shutdown(r, 0).await
            }
          }
          awaitMembersUp(currentRoles.size, timeout = remaining)
        }
      }
      awaitClusterResult
      enterBarrier("remove-several-" + step)
    }

  def reportResult[T](thunk: ⇒ T): T = {
    val startTime = System.nanoTime
    val startStats = clusterView.latestStats

    val returnValue = thunk

    clusterResultAggregator !
      ClusterResult(cluster.selfAddress, (System.nanoTime - startTime).nanos, cluster.readView.latestStats :- startStats)

    returnValue
  }

  def exerciseJoinRemove(title: String, duration: FiniteDuration): Unit = {
    val activeRoles = roles.take(numberOfNodesJoinRemove)
    val loopDuration = 10.seconds + convergenceWithin(4.seconds, nbrUsedRoles + activeRoles.size)
    val rounds = ((duration - loopDuration).toMillis / loopDuration.toMillis).max(1).toInt
    val usedRoles = roles.take(nbrUsedRoles)
    val usedAddresses = usedRoles.map(address(_)).toSet

    @tailrec def loop(counter: Int, previousAS: Option[ActorSystem], allPreviousAddresses: Set[Address]): Option[ActorSystem] = {
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
                previousAS foreach { _.shutdown() }
                val sys = ActorSystem(system.name, system.settings.config)
                muteLog(sys)
                Cluster(sys).joinSeedNodes(seedNodes.toIndexedSeq map address)
                Some(sys)
              } else previousAS
            runOn(usedRoles: _*) {
              awaitMembersUp(
                nbrUsedRoles + activeRoles.size,
                canNotBePartOfMemberRing = allPreviousAddresses,
                timeout = remaining)
            }
            val nextAddresses = clusterView.members.map(_.address) -- usedAddresses
            runOn(usedRoles: _*) {
              nextAddresses.size must be(numberOfNodesJoinRemove)
            }

            enterBarrier("join-remove-" + step)
            (nextAS, nextAddresses)
          }
        }
        awaitClusterResult

        step += 1
        loop(counter + 1, nextAS, nextAddresses)
      }
    }

    loop(1, None, Set.empty) foreach { _.shutdown }
    within(loopDuration) {
      runOn(usedRoles: _*) {
        awaitMembersUp(nbrUsedRoles, timeout = remaining)
        phiObserver ! Reset
        statsObserver ! Reset
      }
    }
    enterBarrier("join-remove-shutdown-" + step)

  }

  def master: ActorRef = system.actorFor("/user/master-" + myself.name)

  def exerciseRouters(title: String, duration: FiniteDuration, batchInterval: FiniteDuration,
                      expectDroppedMessages: Boolean, tree: Boolean): Unit =
    within(duration + 10.seconds) {
      createResultAggregator(title, expectedResults = nbrUsedRoles, includeInHistory = false)

      val (masterRoles, otherRoles) = roles.take(nbrUsedRoles).splitAt(3)
      runOn(masterRoles: _*) {
        reportResult {
          val m = system.actorOf(Props(new Master(settings, batchInterval, tree)),
            name = "master-" + myself.name)
          m ! Begin
          import system.dispatcher
          system.scheduler.scheduleOnce(duration) {
            m.tell(End, testActor)
          }
          val workResult = awaitWorkResult
          workResult.sendCount must be > (0L)
          workResult.ackCount must be > (0L)
          if (!expectDroppedMessages)
            workResult.retryCount must be(0)

          enterBarrier("routers-done-" + step)
        }
      }
      runOn(otherRoles: _*) {
        reportResult {
          enterBarrier("routers-done-" + step)
        }
      }

      awaitClusterResult
    }

  def awaitWorkResult: WorkResult = {
    val m = master
    val workResult = expectMsgType[WorkResult]
    log.info("{} result, [{}] jobs/s, retried [{}] of [{}] msg", m.path.name,
      workResult.jobsPerSecond.form,
      workResult.retryCount, workResult.sendCount)
    watch(m)
    expectMsgPF(remaining) { case Terminated(a) if a.path == m.path ⇒ true }
    workResult
  }

  def exerciseSupervision(title: String, duration: FiniteDuration, oneIteration: Duration): Unit =
    within(duration + 10.seconds) {
      val rounds = (duration.toMillis / oneIteration.toMillis).max(1).toInt
      val supervisor = system.actorOf(Props[Supervisor], "supervisor")
      for (count ← 0 until rounds) {
        createResultAggregator(title, expectedResults = nbrUsedRoles, includeInHistory = false)

        reportResult {
          roles.take(nbrUsedRoles) foreach { r ⇒
            supervisor ! Props[RemoteChild].withDeploy(Deploy(scope = RemoteScope(address(r))))
          }
          supervisor ! GetChildrenCount
          expectMsgType[ChildrenCount] must be(ChildrenCount(nbrUsedRoles, 0))

          1 to 5 foreach { _ ⇒ supervisor ! new RuntimeException("Simulated exception") }
          awaitCond {
            supervisor ! GetChildrenCount
            val c = expectMsgType[ChildrenCount]
            c == ChildrenCount(nbrUsedRoles, 5 * nbrUsedRoles)
          }

          // after 5 restart attempts the children should be stopped
          supervisor ! new RuntimeException("Simulated exception")
          awaitCond {
            supervisor ! GetChildrenCount
            val c = expectMsgType[ChildrenCount]
            // zero children
            c == ChildrenCount(0, 6 * nbrUsedRoles)
          }
          supervisor ! Reset

        }

        awaitClusterResult
        step += 1
      }
    }

  "A cluster under stress" must {

    "join seed nodes" taggedAs LongRunningTest in within(30 seconds) {

      val otherNodesJoiningSeedNodes = roles.slice(numberOfSeedNodes, numberOfSeedNodes + numberOfNodesJoiningToSeedNodesInitially)
      val size = seedNodes.size + otherNodesJoiningSeedNodes.size

      createResultAggregator("join seed nodes", expectedResults = size, includeInHistory = true)

      runOn((seedNodes ++ otherNodesJoiningSeedNodes): _*) {
        reportResult {
          cluster.joinSeedNodes(seedNodes.toIndexedSeq map address)
          awaitMembersUp(size, timeout = remaining)
        }
      }

      awaitClusterResult

      nbrUsedRoles += size
      enterBarrier("after-" + step)
    }

    "start routers that are running while nodes are joining" taggedAs LongRunningTest in {
      runOn(roles.take(3): _*) {
        system.actorOf(Props(new Master(settings, settings.workBatchInterval, tree = false)),
          name = "master-" + myself.name) ! Begin
      }
      enterBarrier("after-" + step)
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
      joinSeveral(numberOfNodesJoiningToOneNode, toSeedNodes = true)
      nbrUsedRoles += numberOfNodesJoiningToSeedNodes
      enterBarrier("after-" + step)
    }

    "join nodes one-by-one to large cluster" taggedAs LongRunningTest in {
      joinOneByOne(numberOfNodesJoiningOneByOneLarge)
      enterBarrier("after-" + step)
    }

    "end routers that are running while nodes are joining" taggedAs LongRunningTest in within(30.seconds) {
      runOn(roles.take(3): _*) {
        val m = master
        m.tell(End, testActor)
        val workResult = awaitWorkResult
        workResult.retryCount must be(0)
        workResult.sendCount must be > (0L)
        workResult.ackCount must be > (0L)
      }
      enterBarrier("after-" + step)
    }

    "use routers with normal throughput" taggedAs LongRunningTest in {
      exerciseRouters("use routers with normal throughput", normalThroughputDuration,
        batchInterval = workBatchInterval, expectDroppedMessages = false, tree = false)
      enterBarrier("after-" + step)
    }

    "use routers with high throughput" taggedAs LongRunningTest in {
      exerciseRouters("use routers with high throughput", highThroughputDuration,
        batchInterval = Duration.Zero, expectDroppedMessages = false, tree = false)
      enterBarrier("after-" + step)
    }

    "use many actors with normal throughput" taggedAs LongRunningTest in {
      exerciseRouters("use many actors with normal throughput", normalThroughputDuration,
        batchInterval = workBatchInterval, expectDroppedMessages = false, tree = true)
      enterBarrier("after-" + step)
    }

    "use many actors with high throughput" taggedAs LongRunningTest in {
      exerciseRouters("use many actors with high throughput", highThroughputDuration,
        batchInterval = Duration.Zero, expectDroppedMessages = false, tree = true)
      enterBarrier("after-" + step)
    }

    "exercise join/remove/join/remove" taggedAs LongRunningTest in {
      exerciseJoinRemove("exercise join/remove", joinRemoveDuration)
      enterBarrier("after-" + step)
    }

    "exercise supervision" taggedAs LongRunningTest in {
      exerciseSupervision("exercise supervision", supervisionDuration, supervisionOneIteration)
      enterBarrier("after-" + step)
    }

    "start routers that are running while nodes are removed" taggedAs LongRunningTest in {
      runOn(roles.take(3): _*) {
        system.actorOf(Props(new Master(settings, settings.workBatchInterval, tree = false)),
          name = "master-" + myself.name) ! Begin
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

    "leave nodes one-by-one from small cluster" taggedAs LongRunningTest in {
      removeOneByOne(numberOfNodesLeavingOneByOneSmall, shutdown = false)
      enterBarrier("after-" + step)
    }

    "shutdown nodes one-by-one from small cluster" taggedAs LongRunningTest in {
      removeOneByOne(numberOfNodesShutdownOneByOneSmall, shutdown = true)
      enterBarrier("after-" + step)
    }

    "end routers that are running while nodes are removed" taggedAs LongRunningTest in within(30.seconds) {
      runOn(roles.take(3): _*) {
        val m = master
        m.tell(End, testActor)
        val workResult = awaitWorkResult
        workResult.sendCount must be > (0L)
        workResult.ackCount must be > (0L)
      }
      enterBarrier("after-" + step)
    }

  }
}
