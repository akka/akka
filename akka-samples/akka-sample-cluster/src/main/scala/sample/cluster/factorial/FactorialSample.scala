package sample.cluster.factorial

//#imports
import scala.annotation.tailrec
import scala.concurrent.Future
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.pattern.pipe
import akka.routing.FromConfig

//#imports

object FactorialFrontend {
  def main(args: Array[String]): Unit = {
    val upToN = if (args.isEmpty) 200 else args(0).toInt

    val system = ActorSystem("ClusterSystem")
    val frontend = system.actorOf(Props[FactorialFrontend], name = "factorialFrontend")

    system.log.info("Starting up")
    // wait to let cluster converge and gather metrics
    Thread.sleep(10000)

    system.log.info("Starting many factorials up to [{}]", upToN)
    for (_ ← 1 to 1000; n ← 1 to upToN) {
      frontend ! n
    }
  }
}

//#frontend
class FactorialFrontend extends Actor with ActorLogging {

  val backend = context.actorOf(Props[FactorialBackend].withRouter(FromConfig),
    name = "factorialBackendRouter")

  def receive = {
    case n: Int ⇒ backend ! n
    case (n: Int, factorial: BigInt) ⇒
      log.info("{}! = {}", n, factorial)
  }
}
//#frontend

object FactorialBackend {
  def main(args: Array[String]): Unit = {
    // Override the configuration of the port
    // when specified as program argument
    if (args.nonEmpty) System.setProperty("akka.remote.netty.port", args(0))

    val system = ActorSystem("ClusterSystem")
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
    case ClusterMetricsChanged(nodeMetrics) ⇒
      nodeMetrics.filter(_.address == selfAddress) foreach { n ⇒
        n match {
          case HeapMemory(address, timestamp, used, committed, max) ⇒
            log.info("Used heap: {} MB", used.doubleValue / 1024 / 1024)
          case _ ⇒ // no heap info
        }
        n match {
          case Cpu(address, timestamp, Some(systemLoadAverage), cpuCombined, processors) ⇒
            log.info("Load: {} ({} processors)", systemLoadAverage, processors)
          case _ ⇒ // no cpu info
        }
      }
    case state: CurrentClusterState ⇒ // ignore
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