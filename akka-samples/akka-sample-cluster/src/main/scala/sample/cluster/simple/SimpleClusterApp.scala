package sample.cluster.simple

import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._

object SimpleClusterApp {

  def main(args: Array[String]): Unit = {

    // Override the configuration of the port 
    // when specified as program argument
    if (args.nonEmpty) System.setProperty("akka.remoting.transports.tcp.port", args(0))

    // Create an Akka system
    val system = ActorSystem("ClusterSystem")
    val clusterListener = system.actorOf(Props(new Actor with ActorLogging {
      def receive = {
        case state: CurrentClusterState ⇒
          log.info("Current members: {}", state.members)
        case MemberJoined(member) ⇒
          log.info("Member joined: {}", member)
        case MemberUp(member) ⇒
          log.info("Member is Up: {}", member)
        case UnreachableMember(member) ⇒
          log.info("Member detected as unreachable: {}", member)
        case _: ClusterDomainEvent ⇒ // ignore

      }
    }), name = "clusterListener")

    Cluster(system).subscribe(clusterListener, classOf[ClusterDomainEvent])
  }

}