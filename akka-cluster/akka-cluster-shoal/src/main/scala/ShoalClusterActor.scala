/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.cluster.shoal

import java.util.Properties

import se.scalablesolutions.akka.config.Config.config
import se.scalablesolutions.akka.remote.{ClusterActor, BasicClusterActor, RemoteServer}

import com.sun.enterprise.ee.cms.core._
import com.sun.enterprise.ee.cms.impl.client._

/**
 * Clustering support via Shoal.
 */
class ShoalClusterActor extends BasicClusterActor {

  type ADDR_T = String

  @volatile protected var gms : Option[GroupManagementService] = None
            protected lazy val serverName : String = RemoteServer.HOSTNAME + ":" + RemoteServer.PORT
  @volatile private   var isActive = false

  lazy val topic : String = config.getString("akka.remote.cluster.shoal.topic") getOrElse "akka-messages"

  override def init = {
    super.init
    gms = Some(createGMS)
    isActive = true
  }

  override def shutdown = {
    super.shutdown
    isActive = false
    for(g <- gms) g.shutdown(GMSConstants.shutdownType.INSTANCE_SHUTDOWN)
    gms = None
  }

  /**
   * Constructs a Properties instance with properties designated for the underlying
   * Shoal cluster transport (JXTA,JGroups)
   */
  protected def properties() : Properties = {
    config.getConfigMap("akka.remote.cluster.shoal.properties").map( m => {
      new Properties(){
        for(key <- m.keys) setProperty(key,m(key))
      }
    }).getOrElse(null)
  }

  /**
   * Creates a GroupManagementService, provides it with the proper properties
   * Adds callbacks and boots up the cluster
   */
  protected def createGMS : GroupManagementService = {
    val g = GMSFactory
      .startGMSModule(serverName,name, GroupManagementService.MemberType.CORE, properties())
      .asInstanceOf[GroupManagementService]
    val callback = createCallback
    g.addActionFactory(new JoinNotificationActionFactoryImpl(callback))
    g.addActionFactory(new FailureSuspectedActionFactoryImpl(callback))
    g.addActionFactory(new FailureNotificationActionFactoryImpl(callback))
    g.addActionFactory(new PlannedShutdownActionFactoryImpl(callback))
    g.addActionFactory(new MessageActionFactoryImpl(callback), topic)
    g.join
    g
  }

  /**
   * Creates a CallBack instance that deals with the cluster signalling
   */
  protected def createCallback : CallBack = {
    import scala.collection.JavaConversions._
    import ClusterActor._

    val me = this
    new CallBack {
      def processNotification(signal : Signal) {
        try {
          signal.acquire()
          if(isActive) {
            signal match {
              case  ms : MessageSignal => me send Message[ADDR_T](ms.getMemberToken,ms.getMessage)
              case jns : JoinNotificationSignal => me send View[ADDR_T](Set[ADDR_T]() ++ jns.getCurrentCoreMembers.asScala - serverName)
              case fss : FailureSuspectedSignal => me send Zombie[ADDR_T](fss.getMemberToken)
              case fns : FailureNotificationSignal => me send Zombie[ADDR_T](fns.getMemberToken)
              case _ => log.debug("Unhandled signal: [%s]",signal)
            }
          }
          signal.release()
        } catch {
          case e : SignalAcquireException => log.warning(e,"SignalAcquireException")
          case e : SignalReleaseException => log.warning(e,"SignalReleaseException")
        }
      }
    }
  }

  protected def toOneNode(dest : ADDR_T, msg : Array[Byte]) : Unit =
    for(g <- gms) g.getGroupHandle.sendMessage(dest,topic, msg)

  protected def toAllNodes(msg : Array[Byte]) : Unit =
    for(g <- gms) g.getGroupHandle.sendMessage(topic, msg)
}