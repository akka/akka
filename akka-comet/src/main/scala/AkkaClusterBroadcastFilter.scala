/**
 * Copyright (C) 2009 Scalable Solutions.
 */
 
package se.scalablesolutions.akka.comet

import se.scalablesolutions.akka.actor.{Actor}
import se.scalablesolutions.akka.remote.{Cluster}
import org.atmosphere.cpr.{ClusterBroadcastFilter,Broadcaster}
import scala.reflect.{BeanProperty}

sealed trait ClusterCometMessageType
case class ClusterCometBroadcast(val name : String, val msg : AnyRef) extends ClusterCometMessageType

class AkkaClusterBroadcastFilter extends Actor with ClusterBroadcastFilter[AnyRef] {
  @BeanProperty var clusterName = ""
  @BeanProperty var broadcaster : Broadcaster = null

  override def init : Unit = ()

  def destroy : Unit = stop

  def filter(o : AnyRef) : AnyRef = o match { 
    case ClusterCometBroadcast(_,m) => m   //Do not re-broadcast, just unbox and pass along
 
    case m : AnyRef => {                   //Relay message to the cluster and pass along
      Cluster.relayMessage(classOf[AkkaClusterBroadcastFilter],ClusterCometBroadcast(clusterName,m))
      m
    }
  }

  def receive = { 
    //Only handle messages intended for this particular instance
    case b@ClusterCometBroadcast(c,_) if (c == clusterName) && (broadcaster ne null) => broadcaster broadcast b
    case _ =>
  }

  //Since this class is instantiated by Atmosphere, we need to make sure it's started
  start
}