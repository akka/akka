package sample.cluster.stats

import scala.collection.immutable
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorSelection
import akka.actor.RootActorPath
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.ClusterEvent.MemberRemoved
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.Member

//#facade
class StatsFacade extends Actor with ActorLogging {
  import context.dispatcher
  val cluster = Cluster(context.system)

  // sort by age, oldest first
  val ageOrdering = Ordering.fromLessThan[Member] { (a, b) => a.isOlderThan(b) }
  var membersByAge: immutable.SortedSet[Member] = immutable.SortedSet.empty(ageOrdering)

  // subscribe to cluster changes
  // re-subscribe when restart
  override def preStart(): Unit = cluster.subscribe(self, classOf[MemberEvent])
  override def postStop(): Unit = cluster.unsubscribe(self)

  def receive = {
    case job: StatsJob if membersByAge.isEmpty =>
      sender ! JobFailed("Service unavailable, try again later")
    case job: StatsJob =>
      currentMaster.tell(job, sender)
    case state: CurrentClusterState =>
      membersByAge = immutable.SortedSet.empty(ageOrdering) ++ state.members.collect {
        case m if m.hasRole("compute") => m
      }
    case MemberUp(m)         => if (m.hasRole("compute")) membersByAge += m
    case MemberRemoved(m, _) => if (m.hasRole("compute")) membersByAge -= m
    case _: MemberEvent      => // not interesting
  }

  def currentMaster: ActorSelection =
    context.actorSelection(RootActorPath(membersByAge.head.address) /
      "user" / "singleton" / "statsService")

}
//#facade