/**
 * Copyright (C) 2009 Scalable Solutions.
 */
 
package se.scalablesolutions.akka.comet

import se.scalablesolutions.akka.actor.{Actor}
import se.scalablesolutions.akka.remote.{Cluster}
import org.atmosphere.cpr.{ClusterBroadcastFilter,Broadcaster}
import scala.reflect.{BeanProperty}

sealed trait AkkaClusterCometBroadcastMessage
case class BroadcastMessage(val name : String, val msg : AnyRef) extends AkkaClusterCometBroadcastMessage

class AkkaClusterBroadcastFilter extends Actor with ClusterBroadcastFilter[AnyRef] {
  @BeanProperty var clusterName = ""
  @BeanProperty var broadcaster : Broadcaster = null

  override def init : Unit = ()

  def destroy : Unit = stop

  def filter(o : AnyRef) : AnyRef = {
    o match {
      case BroadcastMessage(_,m) => m

      case _ => {
        Cluster.relayMessage(classOf[AkkaClusterBroadcastFilter],BroadcastMessage(clusterName,o))
        o
      }
    }
  }

  def receive = { 
    case bm@BroadcastMessage(c,_) if (c == clusterName) && (broadcaster ne null) => broadcaster broadcast bm
    case _ =>
  }

  //Since this class is instantiated by Atmosphere, we need to make sure it's started
  start
}