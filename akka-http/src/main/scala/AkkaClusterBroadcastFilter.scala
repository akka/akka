/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.comet

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.remote.Cluster
import scala.reflect.BeanProperty
import org.atmosphere.cpr.{BroadcastFilter, ClusterBroadcastFilter, Broadcaster}

sealed trait ClusterCometMessageType
case class ClusterCometBroadcast(name: String, msg: AnyRef) extends ClusterCometMessageType

/**
 * Enables explicit clustering of Atmosphere (Comet) resources
 * Annotate the endpoint which has the @Broadcast annotation with
 * @org.atmosphere.annotation.Cluster(Array(classOf[AkkClusterBroadcastFilter])){ name = "someUniqueName" }
 * that's all folks!
 * Note: In the future, clustering comet will be transparent
 */

class AkkaClusterBroadcastFilter extends Actor with ClusterBroadcastFilter[AnyRef] {
  @BeanProperty var clusterName = ""
  @BeanProperty var broadcaster : Broadcaster = null

  /**
   * Stops the actor
   */
  def destroy: Unit = self.stop

  /**
   * Relays all non ClusterCometBroadcast messages to the other AkkaClusterBroadcastFilters in the cluster
   * ClusterCometBroadcasts are not broadcasted because they originate from the cluster,
   * otherwise we'd start a chain reaction.
   */
  def filter(o : AnyRef) = new BroadcastFilter.BroadcastAction(o match {
    case ClusterCometBroadcast(_,m) => m   //Do not re-broadcast, just unbox and pass along

    case m : AnyRef => {                   //Relay message to the cluster and pass along
      Cluster.relayMessage(classOf[AkkaClusterBroadcastFilter],ClusterCometBroadcast(clusterName,m))
      m
    }
  })

  def receive = {
    //Only handle messages intended for this particular instance
    case b @ ClusterCometBroadcast(c, _) if (c == clusterName) && (broadcaster ne null) => broadcaster broadcast b
    case _ =>
  }

  //Since this class is instantiated by Atmosphere, we need to make sure it's started
  self.start
}
