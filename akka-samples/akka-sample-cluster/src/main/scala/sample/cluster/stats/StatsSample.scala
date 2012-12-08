package sample.cluster.stats

//#imports
import language.postfixOps
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.actor.RelativeActorPath
import akka.actor.RootActorPath
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.ClusterEvent.LeaderChanged
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.MemberStatus
import akka.routing.FromConfig
import akka.routing.ConsistentHashingRouter.ConsistentHashableEnvelope
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.Timeout
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

  var currentMaster: Option[ActorRef] = None
  var currentMasterCreatedByMe = false

  // subscribe to cluster changes, LeaderChanged
  // re-subscribe when restart
  override def preStart(): Unit = cluster.subscribe(self, classOf[LeaderChanged])
  override def postStop(): Unit = cluster.unsubscribe(self)

  def receive = {
    case job: StatsJob if currentMaster.isEmpty ⇒
      sender ! JobFailed("Service unavailable, try again later")
    case job: StatsJob ⇒
      implicit val timeout = Timeout(5.seconds)
      currentMaster foreach {
        _ ? job recover {
          case _ ⇒ JobFailed("Service unavailable, try again later")
        } pipeTo sender
      }
    case state: CurrentClusterState ⇒
      state.leader foreach updateCurrentMaster
    case LeaderChanged(Some(leaderAddress)) ⇒
      updateCurrentMaster(leaderAddress)
  }

  def updateCurrentMaster(leaderAddress: Address): Unit = {
    if (leaderAddress == cluster.selfAddress) {
      if (!currentMasterCreatedByMe) {
        log.info("Creating new statsService master at [{}]", leaderAddress)
        currentMaster = Some(context.actorOf(Props[StatsService],
          name = "statsService"))
        currentMasterCreatedByMe = true
      }
    } else {
      if (currentMasterCreatedByMe)
        currentMaster foreach { context.stop(_) }
      log.info("Using statsService master at [{}]", leaderAddress)
      currentMaster = Some(context.actorFor(
        self.path.toStringWithAddress(leaderAddress) + "/statsService"))
      currentMasterCreatedByMe = false
    }
  }

}
//#facade

object StatsSample {
  def main(args: Array[String]): Unit = {
    // Override the configuration of the port
    // when specified as program argument
    if (args.nonEmpty) System.setProperty("akka.remote.netty.port", args(0))

    //#start-router-lookup
    val system = ActorSystem("ClusterSystem", ConfigFactory.parseString("""
      akka.actor.deployment {
        /statsService/workerRouter {
            router = consistent-hashing
            nr-of-instances = 100
            cluster {
              enabled = on
              routees-path = "/user/statsWorker"
              allow-local-routees = on
            }
          }
      }
      """).withFallback(ConfigFactory.load()))

    system.actorOf(Props[StatsWorker], name = "statsWorker")
    system.actorOf(Props[StatsService], name = "statsService")
    //#start-router-lookup

  }
}

object StatsSampleOneMaster {
  def main(args: Array[String]): Unit = {
    // Override the configuration of the port
    // when specified as program argument
    if (args.nonEmpty) System.setProperty("akka.remote.netty.port", args(0))

    //#start-router-deploy
    val system = ActorSystem("ClusterSystem", ConfigFactory.parseString("""
      akka.actor.deployment {
        /statsFacade/statsService/workerRouter {
            router = consistent-hashing
            nr-of-instances = 100
            cluster {
              enabled = on
              max-nr-of-instances-per-node = 3
              allow-local-routees = off
            }
          }
      }
      """).withFallback(ConfigFactory.load()))
    //#start-router-deploy

    system.actorOf(Props[StatsFacade], name = "statsFacade")
  }
}

object StatsSampleClient {
  def main(args: Array[String]): Unit = {
    val system = ActorSystem("ClusterSystem")
    system.actorOf(Props(new StatsSampleClient("/user/statsService")), "client")
  }
}

object StatsSampleOneMasterClient {
  def main(args: Array[String]): Unit = {
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

  override def preStart(): Unit = cluster.subscribe(self, classOf[MemberEvent])
  override def postStop(): Unit = {
    cluster.unsubscribe(self)
    tickTask.cancel()
  }

  def receive = {
    case "tick" if nodes.nonEmpty ⇒
      // just pick any one
      val address = nodes.toIndexedSeq(ThreadLocalRandom.current.nextInt(nodes.size))
      val service = context.actorFor(RootActorPath(address) / servicePathElements)
      service ! StatsJob("this is the text that will be analyzed")
    case result: StatsResult ⇒
      println(result)
    case failed: JobFailed ⇒
      println(failed)
    case state: CurrentClusterState ⇒
      nodes = state.members.collect { case m if m.status == MemberStatus.Up ⇒ m.address }
    case MemberUp(m)        ⇒ nodes += m.address
    case other: MemberEvent ⇒ nodes -= other.member.address
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
      allowLocalRoutees = true))),
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
      allowLocalRoutees = false))),
    name = "workerRouter3")
  //#router-deploy-in-code
}
