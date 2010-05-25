/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.spring

import org.springframework.beans.factory.config.AbstractFactoryBean
import se.scalablesolutions.akka.actor.ActiveObject
import reflect.BeanProperty
import se.scalablesolutions.akka.config.ScalaConfig.RestartCallbacks
import se.scalablesolutions.akka.dispatch.MessageDispatcher


/**
 * Factory bean for active objects.
 * @author michaelkober
 */
class ActiveObjectFactoryBean extends AbstractFactoryBean[AnyRef] {
  import StringReflect._

  @BeanProperty var target: String = ""
  @BeanProperty var timeout: Long = _
  @BeanProperty var interface: String = ""
  @BeanProperty var transactional: Boolean = false
  @BeanProperty var pre: String = ""
  @BeanProperty var post: String = ""
  @BeanProperty var host: String = ""
  @BeanProperty var port: Int = _
  @BeanProperty var lifecycle: String = ""
  @BeanProperty var dispatcher: DispatcherProperties = _

  /*
   * @see org.springframework.beans.factory.FactoryBean#getObjectType()
   */
  def getObjectType: Class[AnyRef] = target.toClass


  /*
   * @see org.springframework.beans.factory.config.AbstractFactoryBean#createInstance()
   */
  def createInstance: AnyRef = {
    var argumentList = ""
    if (isRemote) argumentList += "r"
    if (hasInterface) argumentList += "i"
    if (hasDispatcher) argumentList += "d"
    create(argumentList)
  }

// TODO: check if this works in 2.8 (type inferred to Nothing instead of AnyRef here)
//
//  private[akka] def create(argList : String) : AnyRef = argList match {
//    case "r" => ActiveObject.newRemoteInstance(target.toClass, timeout, transactional, host, port, callbacks)
//    case "ri" => ActiveObject.newRemoteInstance(interface.toClass, target.toClass, timeout, transactional, host, port, callbacks)
//    case "rd" => ActiveObject.newRemoteInstance(target.toClass, timeout, transactional, dispatcherInstance, host, port, callbacks)
//    case "rid" => ActiveObject.newRemoteInstance(interface.toClass, target.toClass, timeout, transactional, dispatcherInstance, host, port, callbacks)
//    case "i" => ActiveObject.newInstance(interface.toClass, target.toClass, timeout, transactional, callbacks)
//    case "id" => ActiveObject.newInstance(interface.toClass, target.toClass, timeout, transactional, dispatcherInstance, callbacks)
//    case "d" => ActiveObject.newInstance(target.toClass, timeout, transactional, dispatcherInstance, callbacks)
//    case _ => ActiveObject.newInstance(target.toClass, timeout, transactional, callbacks)
//  }

  private[akka] def create(argList : String) : AnyRef = {
    if (argList == "r") {
      ActiveObject.newRemoteInstance(target.toClass, timeout, transactional, host, port, callbacks)
    } else if (argList == "ri" ) {
      ActiveObject.newRemoteInstance(interface.toClass, target.toClass, timeout, transactional, host, port, callbacks)
    } else if (argList == "rd") {
      ActiveObject.newRemoteInstance(target.toClass, timeout, transactional, dispatcherInstance, host, port, callbacks)
    } else if (argList == "rid") {
      ActiveObject.newRemoteInstance(interface.toClass, target.toClass, timeout, transactional, dispatcherInstance, host, port, callbacks)
    } else if (argList == "i") {
      ActiveObject.newInstance(interface.toClass, target.toClass, timeout, transactional, callbacks)
    } else if (argList == "id") {
      ActiveObject.newInstance(interface.toClass, target.toClass, timeout, transactional, dispatcherInstance, callbacks)
    } else if (argList == "d") {
      ActiveObject.newInstance(target.toClass, timeout, transactional, dispatcherInstance, callbacks)
    } else {
      ActiveObject.newInstance(target.toClass, timeout, transactional, callbacks)
    }
  }

  /**
   * create Option[RestartCallback]
   */
  private def callbacks: Option[RestartCallbacks] = {
    if (hasCallbacks) {
      val callbacks = new RestartCallbacks(pre, post)
      Some(callbacks)
    } else {
      None
    }
  }

  private[akka] def isRemote = (host != null) && (!host.isEmpty)

  private[akka] def hasInterface = (interface != null) && (!interface.isEmpty)

  private[akka] def hasCallbacks = ((pre != null) && !pre.isEmpty) || ((post != null) && !post.isEmpty)

  private[akka] def hasDispatcher = (dispatcher != null) && (dispatcher.dispatcherType != null) && (!dispatcher.dispatcherType.isEmpty)

  private[akka] def dispatcherInstance : MessageDispatcher = {
    import DispatcherFactoryBean._
    createNewInstance(dispatcher)
  }
}
