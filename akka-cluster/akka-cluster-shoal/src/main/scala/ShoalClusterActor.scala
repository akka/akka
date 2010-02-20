/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.remote

import se.scalablesolutions.akka.Config.config
import java.util.Properties

import com.sun.enterprise.ee.cms.core.{CallBack,
                                       GMSConstants,
                                       GMSFactory,
                                       GroupManagementService,
                                       MessageSignal,
                                       Signal,
                                       GMSException,
                                       SignalAcquireException,
                                       SignalReleaseException,
                                       JoinNotificationSignal,
                                       FailureSuspectedSignal,
                                       FailureNotificationSignal }
import com.sun.enterprise.ee.cms.impl.client.{FailureNotificationActionFactoryImpl,
                                       FailureSuspectedActionFactoryImpl,
                                       JoinNotificationActionFactoryImpl,
                                       MessageActionFactoryImpl,
                                       PlannedShutdownActionFactoryImpl
}
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

    val g = GMSFactory.startGMSModule(serverName,name, GroupManagementService.MemberType.CORE, properties()).asInstanceOf[GroupManagementService]

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
    val me = this
    new CallBack {
      def processNotification(signal : Signal) {
        try {
          signal.acquire()
          if(isActive) {
            signal match {
              case  ms : MessageSignal => me send Message(ms.getMemberToken,ms.getMessage)
              case jns : JoinNotificationSignal => me send View(Set[ADDR_T]() ++ jns.getCurrentCoreMembers - serverName)
              case fss : FailureSuspectedSignal => me send Zombie(fss.getMemberToken)
              case fns : FailureNotificationSignal => me send Zombie(fns.getMemberToken)
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