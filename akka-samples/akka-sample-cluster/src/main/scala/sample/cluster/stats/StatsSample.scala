package sample.cluster.stats

//#imports
import language.postfixOps
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSelection
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.actor.RelativeActorPath
import akka.actor.RootActorPath
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus
import akka.contrib.pattern.ClusterSingletonManager
import akka.routing.FromConfig
import akka.routing.ConsistentHashingRouter.ConsistentHashableEnvelope
//#imports

//#messages
case class StatsJob(text: String)
case class StatsResult(meanWordLength: Double)
case class JobFailed(reason: String)
//#messages

//#service
class StatsService extends Actor {
  val workerRouter = context.actorOf(Props[StatsWorker].withRouter(FromConfig),
    name = "workerRouter")

  def receive = {
    case StatsJob(text) if text != "" ⇒
      val words = text.split(" ")
      val replyTo = sender // important to not close over sender
      // create actor that collects replies from workers
      val aggregator = context.actorOf(Props(
        new StatsAggregator(words.size, replyTo)))
      words foreach { word ⇒
        workerRouter.tell(
          ConsistentHashableEnvelope(word, word), aggregator)
      }
  }
}

class StatsAggregator(expectedResults: Int, replyTo: ActorRef) extends Actor {
  var results = IndexedSeq.empty[Int]
  context.setReceiveTimeout(3 seconds)

  def receive = {
    case wordCount: Int ⇒
      results = results :+ wordCount
      if (results.size == expectedResults) {
        val meanWordLength = results.sum.toDouble / results.size
        replyTo ! StatsResult(meanWordLength)
        context.stop(self)
      }
    case ReceiveTimeout ⇒
      replyTo ! JobFailed("Service unavailable, try again later")
      context.stop(self)
  }
}
//#service

//#worker
class StatsWorker extends Actor {
  var cache = Map.empty[String, Int]
  def receive = {
    case word: String ⇒
      val length = cache.get(word) match {
        case Some(x) ⇒ x
        case None ⇒
          val x = word.length
          cache += (word -> x)
          x
      }

      sender ! length
  }
}
//#worker

//#facade
class StatsFacade extends Actor with ActorLogging {
  import context.dispatcher
  val cluster = Cluster(context.system)

  var currentMaster: Option[ActorSelection] = None

  // subscribe to cluster changes, RoleLeaderChanged
  // re-subscribe when restart
  override def preStart(): Unit = cluster.subscribe(self, classOf[RoleLeaderChanged])
  override def postStop(): Unit = cluster.unsubscribe(self)

  def receive = {
    case job: StatsJob if currentMaster.isEmpty ⇒
      sender ! JobFailed("Service unavailable, try again later")
    case job: StatsJob ⇒
      currentMaster foreach { _.tell(job, sender) }
    case state: CurrentClusterState ⇒
      setCurrentMaster(state.roleLeader("compute"))
    case RoleLeaderChanged(role, leader) ⇒
      if (role == "compute")
        setCurrentMaster(leader)
  }

  def setCurrentMaster(address: Option[Address]): Unit = {
    currentMaster = address.map(a ⇒ context.actorSelection(RootActorPath(a) /
      "user" / "singleton" / "statsService"))
  }

}
//#facade

object StatsSample {
  def main(args: Array[String]): Unit = {
    // Override the configuration of the port when specified as program argument
    val config =
      (if (args.nonEmpty) ConfigFactory.parseString(s"akka.remote.netty.tcp.port=${args(0)}")
      else ConfigFactory.empty).withFallback(
        ConfigFactory.parseString("akka.cluster.roles = [compute]")).
        withFallback(ConfigFactory.load())

    val system = ActorSystem("ClusterSystem", config)

    system.actorOf(Props[StatsWorker], name = "statsWorker")
    system.actorOf(Props[StatsService], name = "statsService")
  }
}

object StatsSampleOneMaster {
  def main(args: Array[String]): Unit = {
    // Override the configuration of the port when specified as program argument
    val config =
      (if (args.nonEmpty) ConfigFactory.parseString(s"akka.remote.netty.tcp.port=${args(0)}")
      else ConfigFactory.empty).withFallback(
        ConfigFactory.parseString("akka.cluster.roles = [compute]")).
        withFallback(ConfigFactory.load())

    val system = ActorSystem("ClusterSystem", config)

    //#create-singleton-manager
    system.actorOf(Props(new ClusterSingletonManager(
      singletonProps = _ ⇒ Props[StatsService], singletonName = "statsService",
      terminationMessage = PoisonPill, role = Some("compute"))),
      name = "singleton")
    //#create-singleton-manager
    system.actorOf(Props[StatsFacade], name = "statsFacade")
  }
}

object StatsSampleClient {
  def main(args: Array[String]): Unit = {
    // note that client is not a compute node, role not defined
    val system = ActorSystem("ClusterSystem")
    system.actorOf(Props(new StatsSampleClient("/user/statsService")), "client")
  }
}

object StatsSampleOneMasterClient {
  def main(args: Array[String]): Unit = {
    // note that client is not a compute node, role not defined
    val system = ActorSystem("ClusterSystem")
    system.actorOf(Props(new StatsSampleClient("/user/statsFacade")), "client")
  }
}

class StatsSampleClient(servicePath: String) extends Actor {
  val cluster = Cluster(context.system)
  val servicePathElements = servicePath match {
    case RelativeActorPath(elements) ⇒ elements
    case _ ⇒ throw new IllegalArgumentException(
      "servicePath [%s] is not a valid relative actor path" format servicePath)
  }
  import context.dispatcher
  val tickTask = context.system.scheduler.schedule(2 seconds, 2 seconds, self, "tick")

  var nodes = Set.empty[Address]

  override def preStart(): Unit = {
    cluster.subscribe(self, classOf[MemberEvent])
    cluster.subscribe(self, classOf[UnreachableMember])
  }
  override def postStop(): Unit = {
    cluster.unsubscribe(self)
    tickTask.cancel()
  }

  def receive = {
    case "tick" if nodes.nonEmpty ⇒
      // just pick any one
      val address = nodes.toIndexedSeq(ThreadLocalRandom.current.nextInt(nodes.size))
      val service = context.actorSelection(RootActorPath(address) / servicePathElements)
      service ! StatsJob("this is the text that will be analyzed")
    case result: StatsResult ⇒
      println(result)
    case failed: JobFailed ⇒
      println(failed)
    case state: CurrentClusterState ⇒
      nodes = state.members.collect {
        case m if m.hasRole("compute") && m.status == MemberStatus.Up ⇒ m.address
      }
    case MemberUp(m) if m.hasRole("compute") ⇒ nodes += m.address
    case other: MemberEvent                  ⇒ nodes -= other.member.address
    case UnreachableMember(m)                ⇒ nodes -= m.address
  }

}

// not used, only for documentation
abstract class StatsService2 extends Actor {
  //#router-lookup-in-code
  import akka.cluster.routing.ClusterRouterConfig
  import akka.cluster.routing.ClusterRouterSettings
  import akka.routing.ConsistentHashingRouter

  val workerRouter = context.actorOf(Props[StatsWorker].withRouter(
    ClusterRouterConfig(ConsistentHashingRouter(), ClusterRouterSettings(
      totalInstances = 100, routeesPath = "/user/statsWorker",
      allowLocalRoutees = true, useRole = Some("compute")))),
    name = "workerRouter2")
  //#router-lookup-in-code
}

// not used, only for documentation
abstract class StatsService3 extends Actor {
  //#router-deploy-in-code
  import akka.cluster.routing.ClusterRouterConfig
  import akka.cluster.routing.ClusterRouterSettings
  import akka.routing.ConsistentHashingRouter

  val workerRouter = context.actorOf(Props[StatsWorker].withRouter(
    ClusterRouterConfig(ConsistentHashingRouter(), ClusterRouterSettings(
      totalInstances = 100, maxInstancesPerNode = 3,
      allowLocalRoutees = false, useRole = None))),
    name = "workerRouter3")
  //#router-deploy-in-code
}
