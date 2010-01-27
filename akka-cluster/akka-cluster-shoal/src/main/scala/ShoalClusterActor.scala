/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.remote

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

class ShoalClusterActor extends BasicClusterActor {

  type ADDR_T = String

  @volatile protected var gms : Option[GroupManagementService] = None
            protected val serverName : String = RemoteServer.HOSTNAME + ":" + RemoteServer.PORT
  @volatile private   var isActive = false

  protected def topic : String = "akka-messages"

  override def init = {
    super.init
    gms = createGMS
    isActive = true
  }

  override def shutdown = {
    super.shutdown
    isActive = false
    for(g <- gms) g.shutdown(GMSConstants.shutdownType.INSTANCE_SHUTDOWN)
    gms = None
  }

  protected def createGMS : Option[GroupManagementService] = {
    val g = GMSFactory.startGMSModule(serverName,name, GroupManagementService.MemberType.CORE, null).asInstanceOf[GroupManagementService]

    val callback = createCallback
    g.addActionFactory(new JoinNotificationActionFactoryImpl(callback))
    g.addActionFactory(new FailureSuspectedActionFactoryImpl(callback))
    g.addActionFactory(new FailureNotificationActionFactoryImpl(callback))
    g.addActionFactory(new PlannedShutdownActionFactoryImpl(callback))
    g.addActionFactory(new MessageActionFactoryImpl(callback), topic)
    g.join
    Some(g)
  }

  protected def createCallback : CallBack = {
    import org.scala_tools.javautils.Imports._
    val me = this
    new CallBack {
      def processNotification(signal : Signal) {
        try {
          signal.acquire()
          if(isActive) {
            signal match {
              case  ms : MessageSignal => me send Message(ms.getMemberToken,ms.getMessage)
              case  js : JoinNotificationSignal => me send View(Set[ADDR_T]() ++ js.getCurrentCoreMembers.asScala - serverName)
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