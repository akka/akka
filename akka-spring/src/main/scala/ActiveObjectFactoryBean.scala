/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.spring

import org.springframework.beans.factory.config.AbstractFactoryBean
import se.scalablesolutions.akka.actor.ActiveObject
import reflect.BeanProperty
import se.scalablesolutions.akka.config.ScalaConfig.RestartCallbacks




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


  /*
   * @see org.springframework.beans.factory.FactoryBean#getObjectType()
   */
  def getObjectType: Class[AnyRef] = target.toClass


  /*
   * @see org.springframework.beans.factory.config.AbstractFactoryBean#createInstance()
   */
  def createInstance: AnyRef = {
    ActiveObject.newInstance(target.toClass, timeout, transactional, restartCallbacks)
    if (isRemote) {
      newRemoteInstance(target, timeout, interface, transactional, restartCallbacks, host, port)
    } else {
      newInstance(target, timeout, interface, transactional, restartCallbacks);
    }
  }

  private[akka] def isRemote = (host != null) && (!host.isEmpty)

  /**
   * create Option[RestartCallback]
   */
  private def restartCallbacks: Option[RestartCallbacks] = {
    if (((pre == null) || pre.isEmpty) && ((post == null) || post.isEmpty)) {
      None
    } else {
      val callbacks = new RestartCallbacks(pre, post)
      Some(callbacks)
    }
  }

  private def newInstance(target: String, timeout: Long, interface: String, transactional: Boolean, callbacks: Option[RestartCallbacks]): AnyRef = {
    if ((interface == null) || interface.isEmpty) {
      ActiveObject.newInstance(target.toClass, timeout, transactional, callbacks)
    } else {
      ActiveObject.newInstance(interface.toClass, target.toClass, timeout, transactional, callbacks)
    }
  }

  private def newRemoteInstance(target: String, timeout: Long, interface: String, transactional: Boolean, callbacks: Option[RestartCallbacks], host: String, port: Int): AnyRef = {
    if ((interface == null) || interface.isEmpty) {
      ActiveObject.newRemoteInstance(target.toClass, timeout, transactional, host, port, callbacks)
    } else {
      ActiveObject.newRemoteInstance(interface.toClass, target.toClass, timeout, transactional, host, port, callbacks)
    }
  }

}