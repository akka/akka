package sample.cluster.factorial

//#imports
import scala.annotation.tailrec
import scala.concurrent.Future
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.pattern.pipe
import akka.routing.FromConfig

//#imports

import akka.cluster.Cluster
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.ClusterEvent.MemberUp

object FactorialFrontend {
  def main(args: Array[String]): Unit = {
    val upToN = if (args.isEmpty) 200 else args(0).toInt

    val system = ActorSystem("ClusterSystem", ConfigFactory.load("factorial"))
    system.log.info("Factorials will start when 3 members in the cluster.")
    //#registerOnUp
    Cluster(system) registerOnMemberUp {
      system.actorOf(Props(new FactorialFrontend(upToN, repeat = true)),
        name = "factorialFrontend")
    }
    //#registerOnUp
  }
}

//#frontend
class FactorialFrontend(upToN: Int, repeat: Boolean) extends Actor with ActorLogging {

  val backend = context.actorOf(Props[FactorialBackend].withRouter(FromConfig),
    name = "factorialBackendRouter")

  override def preStart(): Unit = sendJobs()

  def receive = {
    case (n: Int, factorial: BigInt) ⇒
      if (n == upToN) {
        log.debug("{}! = {}", n, factorial)
        if (repeat) sendJobs()
      }
  }

  def sendJobs(): Unit = {
    log.info("Starting batch of factorials up to [{}]", upToN)
    1 to upToN foreach { backend ! _ }
  }
}
//#frontend

object FactorialBackend {
  def main(args: Array[String]): Unit = {
    // Override the configuration of the port
    // when specified as program argument
    if (args.nonEmpty) System.setProperty("akka.remote.netty.port", args(0))

    val system = ActorSystem("ClusterSystem", ConfigFactory.load("factorial"))
    system.actorOf(Props[FactorialBackend], name = "factorialBackend")

    system.actorOf(Props[MetricsListener], name = "metricsListener")
  }
}

//#backend
class FactorialBackend extends Actor with ActorLogging {

  import context.dispatcher

  def receive = {
    case (n: Int) ⇒
      Future(factorial(n)) map { result ⇒ (n, result) } pipeTo sender
  }

  def factorial(n: Int): BigInt = {
    @tailrec def factorialAcc(acc: BigInt, n: Int): BigInt = {
      if (n <= 1) acc
      else factorialAcc(acc * n, n - 1)
    }
    factorialAcc(BigInt(1), n)
  }

}
//#backend

//#metrics-listener
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.ClusterMetricsChanged
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.NodeMetrics
import akka.cluster.StandardMetrics.HeapMemory
import akka.cluster.StandardMetrics.Cpu

class MetricsListener extends Actor with ActorLogging {
  val selfAddress = Cluster(context.system).selfAddress

  // subscribe to ClusterMetricsChanged
  // re-subscribe when restart
  override def preStart(): Unit =
    Cluster(context.system).subscribe(self, classOf[ClusterMetricsChanged])
  override def postStop(): Unit =
    Cluster(context.system).unsubscribe(self)

  def receive = {
    case ClusterMetricsChanged(clusterMetrics) ⇒
      clusterMetrics.filter(_.address == selfAddress) foreach { nodeMetrics ⇒
        logHeap(nodeMetrics)
        logCpu(nodeMetrics)
      }
    case state: CurrentClusterState ⇒ // ignore
  }

  def logHeap(nodeMetrics: NodeMetrics): Unit = nodeMetrics match {
    case HeapMemory(address, timestamp, used, committed, max) ⇒
      log.info("Used heap: {} MB", used.doubleValue / 1024 / 1024)
    case _ ⇒ // no heap info
  }

  def logCpu(nodeMetrics: NodeMetrics): Unit = nodeMetrics match {
    case Cpu(address, timestamp, Some(systemLoadAverage), cpuCombined, processors) ⇒
      log.info("Load: {} ({} processors)", systemLoadAverage, processors)
    case _ ⇒ // no cpu info
  }
}

//#metrics-listener

// not used, only for documentation
abstract class FactorialFrontend2 extends Actor {
  //#router-lookup-in-code
  import akka.cluster.routing.ClusterRouterConfig
  import akka.cluster.routing.ClusterRouterSettings
  import akka.cluster.routing.AdaptiveLoadBalancingRouter
  import akka.cluster.routing.HeapMetricsSelector

  val backend = context.actorOf(Props[FactorialBackend].withRouter(
    ClusterRouterConfig(AdaptiveLoadBalancingRouter(HeapMetricsSelector),
      ClusterRouterSettings(
        totalInstances = 100, routeesPath = "/user/statsWorker",
        allowLocalRoutees = true))),
    name = "factorialBackendRouter2")
  //#router-lookup-in-code
}

// not used, only for documentation
abstract class FactorialFrontend3 extends Actor {
  //#router-deploy-in-code
  import akka.cluster.routing.ClusterRouterConfig
  import akka.cluster.routing.ClusterRouterSettings
  import akka.cluster.routing.AdaptiveLoadBalancingRouter
  import akka.cluster.routing.SystemLoadAverageMetricsSelector

  val backend = context.actorOf(Props[FactorialBackend].withRouter(
    ClusterRouterConfig(AdaptiveLoadBalancingRouter(
      SystemLoadAverageMetricsSelector), ClusterRouterSettings(
      totalInstances = 100, maxInstancesPerNode = 3,
      allowLocalRoutees = false))),
    name = "factorialBackendRouter3")
  //#router-deploy-in-code
}