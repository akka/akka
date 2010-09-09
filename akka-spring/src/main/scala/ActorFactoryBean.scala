/**
 *  Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.spring

import java.beans.PropertyDescriptor
import java.lang.reflect.Method
import javax.annotation.PreDestroy
import javax.annotation.PostConstruct

import org.springframework.beans.{BeanUtils,BeansException,BeanWrapper,BeanWrapperImpl}
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.config.AbstractFactoryBean
import org.springframework.context.{ApplicationContext,ApplicationContextAware}
import org.springframework.util.ReflectionUtils
import org.springframework.util.StringUtils

import se.scalablesolutions.akka.actor.{ActorRef, AspectInitRegistry, TypedActorConfiguration, TypedActor,Actor}
import se.scalablesolutions.akka.dispatch.MessageDispatcher
import se.scalablesolutions.akka.util.{Logging, Duration}
import scala.reflect.BeanProperty

/**
 * Exception to use when something goes wrong during bean creation.
 *
 * @author <a href="johan.rask@jayway.com">Johan Rask</a>
 */
class AkkaBeansException(message: String, cause:Throwable) extends BeansException(message, cause) {
  def this(message: String) = this(message, null)
}

/**
 * Factory bean for typed and untyped actors.
 *
 * @author michaelkober
 * @author <a href="johan.rask@jayway.com">Johan Rask</a>
 * @author Martin Krasser
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActorFactoryBean extends AbstractFactoryBean[AnyRef] with Logging with ApplicationContextAware {
  import StringReflect._
  import AkkaSpringConfigurationTags._

  @BeanProperty var typed: String = ""
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

  // Holds info about if deps have been set or not. Depends on
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
    val ref = typed match {
      case TYPED_ACTOR_TAG => val typedActor = createTypedInstance()
        setProperties(AspectInitRegistry.initFor(typedActor).targetInstance)
        typedActor
      case UNTYPED_ACTOR_TAG => val untypedActor = createUntypedInstance()
        setProperties(untypedActor.actor)
        untypedActor
      case _ => throw new IllegalArgumentException("Unknown actor type")
    }
    ref
  }

  private[akka] def createTypedInstance() : AnyRef = {
    if (interface == null || interface == "") throw new AkkaBeansException(
        "The 'interface' part of the 'akka:actor' element in the Spring config file can't be null or empty string")
    if (implementation == null || implementation == "") throw new AkkaBeansException(
        "The 'implementation' part of the 'akka:typed-actor' element in the Spring config file can't be null or empty string")

    TypedActor.newInstance(interface.toClass, implementation.toClass, createConfig)
  }

  /**
   * Create an UntypedActor.
   */
  private[akka] def createUntypedInstance() : ActorRef = {
    if (implementation == null || implementation == "") throw new AkkaBeansException(
        "The 'implementation' part of the 'akka:untyped-actor' element in the Spring config file can't be null or empty string")
    val actorRef = Actor.actorOf(implementation.toClass)
    if (timeout > 0) {
      actorRef.setTimeout(timeout)
    }
    if (transactional) {
      actorRef.makeTransactionRequired
    }
    if (isRemote) {
      actorRef.makeRemote(host, port)
    }
    if (hasDispatcher) {
      if (dispatcher.dispatcherType != THREAD_BASED){
        actorRef.setDispatcher(dispatcherInstance())
      } else {
        actorRef.setDispatcher(dispatcherInstance(Some(actorRef)))
      }
    }
    actorRef
  }

 /**
  * Stop the typed actor if it is a singleton.
  */
 override def destroyInstance(instance: AnyRef) {
   typed match {
      case TYPED_ACTOR_TAG => TypedActor.stop(instance)
      case UNTYPED_ACTOR_TAG => instance.asInstanceOf[ActorRef].stop
    }
 }

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


  private[akka] def createConfig: TypedActorConfiguration = {
    val config = new TypedActorConfiguration().timeout(Duration(timeout, "millis"))
    if (transactional) config.makeTransactionRequired
    if (isRemote) config.makeRemote(host, port)
    if (hasDispatcher) {
      if (dispatcher.dispatcherType != THREAD_BASED) {
        config.dispatcher(dispatcherInstance())
      } else {
        config.threadBasedDispatcher()
      }
    }
    config
  }

  private[akka] def isRemote = (host != null) && (!host.isEmpty)

  private[akka] def hasDispatcher =
    (dispatcher != null) &&
    (dispatcher.dispatcherType != null) &&
    (!dispatcher.dispatcherType.isEmpty)

  /**
   * Create dispatcher instance with dispatcher properties.
   * @param actorRef actorRef for thread based dispatcher
   * @return new dispatcher instance
   */
  private[akka] def dispatcherInstance(actorRef: Option[ActorRef] = None) : MessageDispatcher = {
    import DispatcherFactoryBean._
    if (dispatcher.dispatcherType != THREAD_BASED) {
      createNewInstance(dispatcher)
    } else {
      createNewInstance(dispatcher, actorRef)
    }
  }
}
