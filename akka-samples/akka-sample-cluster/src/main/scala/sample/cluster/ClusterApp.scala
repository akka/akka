package sample.cluster

import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._

object ClusterApp {

  def main(args: Array[String]): Unit = {

    if (args.nonEmpty) System.setProperty("akka.remote.netty.port", args(0))

    // Create an Akka system
    val system = ActorSystem("ClusterSystem")
    val clusterListener = system.actorOf(Props(new Actor {
      def receive = {
        case state: CurrentClusterState ⇒
          println("Current members: " + state.members)
        case MembersChanged(members) ⇒
          println("Current members: " + members)

      }
    }))

    Cluster(system).subscribe(clusterListener, classOf[MembersChanged])
  }

}