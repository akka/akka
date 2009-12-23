/**
 * Copyright (C) 2009 Scalable Solutions.
 */
 
package se.scalablesolutions.akka.comet

import se.scalablesolutions.akka.util.{Logging}
import se.scalablesolutions.akka.actor.{Actor}
import se.scalablesolutions.akka.remote.{Cluster}
import org.atmosphere.cpr.{ClusterBroadcastFilter,Broadcaster}
import scala.reflect.{BeanProperty}

sealed trait AkkaClusterBroadcastMessage
case class BroadcastMessage(val name : String, val msg : AnyRef) extends AkkaClusterBroadcastMessage

class AkkaClusterBroadcastFilter extends Actor with ClusterBroadcastFilter[AnyRef] with Logging {
	@BeanProperty var clusterName = ""
	@BeanProperty var broadcaster : Broadcaster = null
	
	override def init = ()
	
	def destroy = ()
	
	def filter(o : AnyRef) : AnyRef = {
		Cluster.relayMessage(classOf[AkkaClusterBroadcastFilter],BroadcastMessage(clusterName,o))
		log.info("filter invoked for message [%s], message was forwarded to cluster",o)
		o
	}
	
	def receive = { 
	     case BroadcastMessage(clusterName,m) if broadcaster ne null => {
	                     log.info("Receiving remote message, broadcasting it to listeners: [%s]",m)
	                     broadcaster broadcast m
	                     }
	     case x => log.info("Not a valid message for cluster[%s] = [%s]",clusterName,x)
	}
}