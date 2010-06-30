/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.spring

import java.beans.PropertyDescriptor

import java.lang.reflect.Method
import org.springframework.beans.BeanWrapperImpl
import org.springframework.beans.BeanWrapper
import org.springframework.beans.BeanUtils
import org.springframework.util.ReflectionUtils
import org.springframework.util.StringUtils
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.config.AbstractFactoryBean
import se.scalablesolutions.akka.actor.ActiveObject
import reflect.BeanProperty
import se.scalablesolutions.akka.config.ScalaConfig.RestartCallbacks
import se.scalablesolutions.akka.dispatch.MessageDispatcher
import se.scalablesolutions.akka.util.Logging

/**
 * Factory bean for active objects.
 *
 * @author michaelkober
 * @author <a href="johan.rask@jayway.com">Johan Rask</a>
 */
class ActiveObjectFactoryBean extends AbstractFactoryBean[AnyRef] with Logging {
  import StringReflect._
  import AkkaSpringConfigurationTags._

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
  @BeanProperty var scope:String = VAL_SCOPE_SINGLETON
  @BeanProperty var property:PropertyEntries = _

  /*
   * @see org.springframework.beans.factory.FactoryBean#getObjectType()
   */
  def getObjectType: Class[AnyRef] = try {
    target.toClass
  } catch {
    // required by contract to return null
    case e: ClassNotFoundException => null
  }

  /*
   * @see org.springframework.beans.factory.config.AbstractFactoryBean#createInstance()
   */
  def createInstance: AnyRef = {
        if(scope.equals(VAL_SCOPE_SINGLETON)) {
                setSingleton(true)
        } else {
                setSingleton(false)
        }
    var argumentList = ""
    if (isRemote) argumentList += "r"
    if (hasInterface) argumentList += "i"
    if (hasDispatcher) argumentList += "d"

    setProperties(
                create(argumentList))
}

 /**
   * This method manages <property/> element by injecting either
   * values (<property value="value"/>) and bean references (<property ref="beanId"/>)
   */
   private def setProperties(ref:AnyRef) : AnyRef = {
        log.debug("Processing properties and dependencies for target class %s",target)
        val beanWrapper = new BeanWrapperImpl(ref);
        for(entry <- property.entryList) {
                val propertyDescriptor = BeanUtils.getPropertyDescriptor(ref.getClass,entry.name)
                val method = propertyDescriptor.getWriteMethod();

                if(StringUtils.hasText(entry.ref)) {
                        log.debug("Setting property %s with bean ref %s using method %s",
                                entry.name,entry.ref,method.getName)
                        method.invoke(ref,getBeanFactory().getBean(entry.ref))
                } else if(StringUtils.hasText(entry.value)) {
                        log.debug("Setting property %s with value %s using method %s",
                                entry.name,entry.value,method.getName)
                        beanWrapper.setPropertyValue(entry.name,entry.value)
                } else {
                        throw new AkkaBeansException("Either property@ref or property@value must be set on property element")
                }
        }
        ref
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
      ActiveObject.newRemoteInstance(interface.toClass, aNewInstance(target.toClass), timeout, transactional, host, port, callbacks)
    } else if (argList == "rd") {
      ActiveObject.newRemoteInstance(target.toClass, timeout, transactional, dispatcherInstance, host, port, callbacks)
    } else if (argList == "rid") {
      ActiveObject.newRemoteInstance(interface.toClass, aNewInstance(target.toClass), timeout, transactional, dispatcherInstance, host, port, callbacks)
    } else if (argList == "i") {
      ActiveObject.newInstance(interface.toClass, aNewInstance(target.toClass), timeout, transactional, callbacks)
    } else if (argList == "id") {
      ActiveObject.newInstance(interface.toClass, aNewInstance(target.toClass), timeout, transactional, dispatcherInstance, callbacks)
    } else if (argList == "d") {
      ActiveObject.newInstance(target.toClass, timeout, transactional, dispatcherInstance, callbacks)
    } else {
      ActiveObject.newInstance(target.toClass, timeout, transactional, callbacks)
    }
  }

  def aNewInstance[T <: AnyRef](clazz: Class[T]) : T = {
      clazz.newInstance().asInstanceOf[T]
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
