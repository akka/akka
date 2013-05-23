package sample.cluster.simple

import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._

object SimpleClusterApp {
  def main(args: Array[String]): Unit = {

    // Override the configuration of the port
    // when specified as program argument
    if (args.nonEmpty) System.setProperty("akka.remote.netty.tcp.port", args(0))

    // Create an Akka system
    val system = ActorSystem("ClusterSystem")
    val clusterListener = system.actorOf(Props[SimpleClusterListener],
      name = "clusterListener")

    Cluster(system).subscribe(clusterListener, classOf[ClusterDomainEvent])
  }
}

class SimpleClusterListener extends Actor with ActorLogging {
  def receive = {
    case state: CurrentClusterState ⇒
      log.info("Current members: {}", state.members.mkString(", "))
    case MemberUp(member) ⇒
      log.info("Member is Up: {}", member.address)
    case UnreachableMember(member) ⇒
      log.info("Member detected as unreachable: {}", member)
    case MemberRemoved(member, previousStatus) ⇒
      log.info("Member is Removed: {} after {}",
        member.address, previousStatus)
    case _: ClusterDomainEvent ⇒ // ignore
  }
}