package akka.cluster.singleton

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.ClusterDomainEvent
import akka.cluster.ClusterEvent.CurrentClusterState

class ClusterEventLoggingActor extends Actor with ActorLogging {
  Cluster(context.system).subscribe(self, classOf[ClusterDomainEvent])
  log.info("Subscribed to cluster domain events")

  override def receive: Receive = {
    case state: CurrentClusterState ⇒
      log.info(state.toString)
    case event: ClusterDomainEvent ⇒
      log.info(event.toString)
  }
}
