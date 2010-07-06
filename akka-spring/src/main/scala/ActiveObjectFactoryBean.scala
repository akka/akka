/**
 *  Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.spring

import java.beans.PropertyDescriptor
import java.lang.reflect.Method

import reflect.BeanProperty

import org.springframework.beans.BeanWrapperImpl
import org.springframework.beans.BeanWrapper
import org.springframework.beans.BeanUtils
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.config.AbstractFactoryBean
import org.springframework.util.ReflectionUtils
import org.springframework.util.StringUtils

import se.scalablesolutions.akka.actor.{ActiveObjectConfiguration, ActiveObject}
import se.scalablesolutions.akka.config.ScalaConfig.{ShutdownCallback, RestartCallbacks}
import se.scalablesolutions.akka.dispatch.MessageDispatcher
import se.scalablesolutions.akka.util.Logging

/**
 * Factory bean for active objects.
 *
 * @author michaelkober
 * @author <a href="johan.rask@jayway.com">Johan Rask</a>
 * @author Martin Krasser
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
  @BeanProperty var shutdown: String = ""
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

    setProperties(create(argumentList))
  }

  /**
   * Stop the active object if it is a singleton.
   */
  override def destroy = {
    if(scope.equals(VAL_SCOPE_SINGLETON)) {
      ActiveObject.stop(getObject)
    }
    super.destroy
  }

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

  private[akka] def create(argList : String) : AnyRef = {
    if (argList == "r") {
      ActiveObject.newInstance(target.toClass, createConfig.makeRemote(host, port))
    } else if (argList == "ri" ) {
      ActiveObject.newInstance(interface.toClass, aNewInstance(target.toClass), createConfig.makeRemote(host, port))
    } else if (argList == "rd") {
      ActiveObject.newInstance(target.toClass, createConfig.makeRemote(host, port).dispatcher(dispatcherInstance))
    } else if (argList == "rid") {
      ActiveObject.newInstance(interface.toClass, aNewInstance(target.toClass), createConfig.makeRemote(host, port).dispatcher(dispatcherInstance))
    } else if (argList == "i") {
      ActiveObject.newInstance(interface.toClass, aNewInstance(target.toClass), createConfig)
    } else if (argList == "id") {
      ActiveObject.newInstance(interface.toClass, aNewInstance(target.toClass), createConfig.dispatcher(dispatcherInstance))
    } else if (argList == "d") {
      ActiveObject.newInstance(target.toClass, createConfig.dispatcher(dispatcherInstance))
    } else {
      ActiveObject.newInstance(target.toClass, createConfig)
    }
  }

  private[akka] def createConfig: ActiveObjectConfiguration = {
    val config = new ActiveObjectConfiguration().timeout(timeout)
    if (hasRestartCallbacks) config.restartCallbacks(pre, post)
    if (hasShutdownCallback) config.shutdownCallback(shutdown)
    if (transactional) config.makeTransactionRequired
    config
  }

  private[akka] def aNewInstance[T <: AnyRef](clazz: Class[T]) : T = {
      clazz.newInstance().asInstanceOf[T]
  }

  private[akka] def isRemote = (host != null) && (!host.isEmpty)

  private[akka] def hasInterface = (interface != null) && (!interface.isEmpty)

  private[akka] def hasRestartCallbacks = ((pre != null) && !pre.isEmpty) || ((post != null) && !post.isEmpty)

  private[akka] def hasShutdownCallback = ((shutdown != null) && !shutdown.isEmpty)

  private[akka] def hasDispatcher = (dispatcher != null) && (dispatcher.dispatcherType != null) && (!dispatcher.dispatcherType.isEmpty)

  private[akka] def dispatcherInstance : MessageDispatcher = {
    import DispatcherFactoryBean._
    createNewInstance(dispatcher)
  }
}
