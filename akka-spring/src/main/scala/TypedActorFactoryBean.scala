/**
 *  Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.spring

import java.beans.PropertyDescriptor
import java.lang.reflect.Method
import javax.annotation.PreDestroy
import javax.annotation.PostConstruct
import reflect.BeanProperty

import org.springframework.beans.BeanWrapperImpl
import org.springframework.beans.BeanWrapper
import org.springframework.beans.BeanUtils
import org.springframework.beans.BeansException
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.config.AbstractFactoryBean
import org.springframework.context.{ApplicationContext,ApplicationContextAware}
import org.springframework.util.ReflectionUtils
import org.springframework.util.StringUtils

import se.scalablesolutions.akka.actor.{AspectInitRegistry, TypedActorConfiguration, TypedActor}
import se.scalablesolutions.akka.dispatch.MessageDispatcher
import se.scalablesolutions.akka.util.{Logging, Duration}

/**
 * Exception to use when something goes wrong during bean creation.
 *
 * @author <a href="johan.rask@jayway.com">Johan Rask</a>
 */
class AkkaBeansException(message: String, cause:Throwable) extends BeansException(message, cause) {
  def this(message: String) = this(message, null)
}

/**
 * Factory bean for typed actors.
 *
 * @author michaelkober
 * @author <a href="johan.rask@jayway.com">Johan Rask</a>
 * @author Martin Krasser
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TypedActorFactoryBean extends AbstractFactoryBean[AnyRef] with Logging with ApplicationContextAware {
  import StringReflect._
  import AkkaSpringConfigurationTags._

  @BeanProperty var interface: String = ""
  @BeanProperty var implementation: String = ""
  @BeanProperty var timeout: Long = _
  @BeanProperty var transactional: Boolean = false
  @BeanProperty var host: String = ""
  @BeanProperty var port: Int = _
  @BeanProperty var lifecycle: String = ""
  @BeanProperty var dispatcher: DispatcherProperties = _
  @BeanProperty var scope: String = VAL_SCOPE_SINGLETON
  @BeanProperty var property: PropertyEntries = _
  @BeanProperty var applicationContext: ApplicationContext = _

  // Holds info about if deps has been set or not. Depends on
  // if interface is specified or not. We must set deps on
  // target instance if interface is specified
  var hasSetDependecies = false

  override def isSingleton = scope.equals(VAL_SCOPE_SINGLETON)

  /*
   * @see org.springframework.beans.factory.FactoryBean#getObjectType()
   */
  def getObjectType: Class[AnyRef] = try {
    implementation.toClass
  } catch {
    // required by contract to return null
    case e: IllegalArgumentException => null
  }

  /*
   * @see org.springframework.beans.factory.config.AbstractFactoryBean#createInstance()
   */
  def createInstance: AnyRef = {
    var argumentList = ""
    if (isRemote) argumentList += "r"
    if (hasInterface) argumentList += "i"
    if (hasDispatcher) argumentList += "d"
    val ref = create(argumentList)
    setProperties(AspectInitRegistry.initFor(ref).targetInstance)
    ref
  }

 /**
  * Stop the typed actor if it is a singleton.
  */
 override def destroyInstance(instance: AnyRef) = TypedActor.stop(instance)

  private def setProperties(ref: AnyRef): AnyRef = {
    if (hasSetDependecies) return ref
    log.debug("Processing properties and dependencies for implementation class\n\t[%s]", implementation)
    val beanWrapper = new BeanWrapperImpl(ref);
    if (ref.isInstanceOf[ApplicationContextAware]) {
      log.debug("Setting application context")
      beanWrapper.setPropertyValue("applicationContext", applicationContext)
    }
    for (entry <- property.entryList) {
      val propertyDescriptor = BeanUtils.getPropertyDescriptor(ref.getClass, entry.name)
      val method = propertyDescriptor.getWriteMethod
      if (StringUtils.hasText(entry.ref)) {
        log.debug("Setting property %s with bean ref %s using method %s", entry.name, entry.ref, method.getName)
        method.invoke(ref,getBeanFactory().getBean(entry.ref))
      } else if(StringUtils.hasText(entry.value)) {
        log.debug("Setting property %s with value %s using method %s", entry.name, entry.value, method.getName)
        beanWrapper.setPropertyValue(entry.name,entry.value)
      } else throw new AkkaBeansException("Either property@ref or property@value must be set on property element")
    }
    ref
  }

  private[akka] def create(argList: String): AnyRef = {
    if (interface == null || interface == "") throw new AkkaBeansException(
        "The 'interface' part of the 'akka:actor' element in the Spring config file can't be null or empty string")
    if (implementation == null || implementation == "") throw new AkkaBeansException(
        "The 'implementation' part of the 'akka:typed-actor' element in the Spring config file can't be null or empty string")
    argList match {
      case "ri"  => TypedActor.newInstance(interface.toClass, implementation.toClass, createConfig.makeRemote(host, port))
      case "i"   => TypedActor.newInstance(interface.toClass, implementation.toClass, createConfig)
      case "id"  => TypedActor.newInstance(interface.toClass, implementation.toClass, createConfig.dispatcher(dispatcherInstance))
      case "rid" => TypedActor.newInstance(interface.toClass, implementation.toClass, createConfig.makeRemote(host, port).dispatcher(dispatcherInstance))
      case _     => TypedActor.newInstance(interface.toClass, implementation.toClass, createConfig)
      //    case "rd"  => TypedActor.newInstance(implementation.toClass, createConfig.makeRemote(host, port).dispatcher(dispatcherInstance))
      //    case "r"   => TypedActor.newInstance(implementation.toClass, createConfig.makeRemote(host, port))
      //    case "d"   => TypedActor.newInstance(implementation.toClass, createConfig.dispatcher(dispatcherInstance))
    }
  }

  private[akka] def createConfig: TypedActorConfiguration = {
    val config = new TypedActorConfiguration().timeout(Duration(timeout, "millis"))
    if (transactional) config.makeTransactionRequired
    config
  }

  private[akka] def isRemote = (host != null) && (!host.isEmpty)

  private[akka] def hasInterface = (interface != null) && (!interface.isEmpty)

  private[akka] def hasDispatcher =
    (dispatcher != null) &&
    (dispatcher.dispatcherType != null) &&
    (!dispatcher.dispatcherType.isEmpty)

  private[akka] def dispatcherInstance: MessageDispatcher = {
    import DispatcherFactoryBean._
    createNewInstance(dispatcher)
  }
}
