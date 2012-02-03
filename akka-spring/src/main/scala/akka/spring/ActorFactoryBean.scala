/**
 *  Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.spring

import org.springframework.beans.{ BeanUtils, BeansException, BeanWrapper, BeanWrapperImpl }
import org.springframework.beans.factory.config.AbstractFactoryBean
import org.springframework.context.{ ApplicationContext, ApplicationContextAware }
import org.springframework.util.StringUtils

import akka.actor.{ ActorRef, ActorRegistry, AspectInitRegistry, TypedActorConfiguration, TypedActor, Actor }
import akka.event.EventHandler
import akka.dispatch.MessageDispatcher
import scala.util.Duration

import scala.reflect.BeanProperty

import java.net.InetSocketAddress

/**
 * Exception to use when something goes wrong during bean creation.
 *
 * @author <a href="johan.rask@jayway.com">Johan Rask</a>
 */
class AkkaBeansException(message: String, cause: Throwable) extends BeansException(message, cause) {
  def this(message: String) = this(message, null)
}

/**
 * Factory bean for typed and untyped actors.
 *
 * @author michaelkober
 * @author <a href="johan.rask@jayway.com">Johan Rask</a>
 * @author Martin Krasser
 */
class ActorFactoryBean extends AbstractFactoryBean[AnyRef] with ApplicationContextAware {
  import AkkaSpringConfigurationTags._
  @BeanProperty
  var id: String = ""
  @BeanProperty
  var typed: String = ""
  @BeanProperty
  var interface: String = ""
  @BeanProperty
  var implementation: String = ""
  @BeanProperty
  var beanRef: String = null
  @BeanProperty
  var timeoutStr: String = ""
  @BeanProperty
  var host: String = ""
  @BeanProperty
  var port: String = ""
  @BeanProperty
  var serverManaged: Boolean = false
  @BeanProperty
  var autostart: Boolean = false
  @BeanProperty
  var serviceName: String = ""
  @BeanProperty
  var lifecycle: String = ""
  @BeanProperty
  var dispatcher: DispatcherProperties = _
  @BeanProperty
  var scope: String = VAL_SCOPE_SINGLETON
  @BeanProperty
  var property: PropertyEntries = _
  @BeanProperty
  var applicationContext: ApplicationContext = _

  lazy val timeout = try {
    if (!timeoutStr.isEmpty) timeoutStr.toLong else -1L
  } catch {
    case nfe: NumberFormatException ⇒
      EventHandler notifyListeners EventHandler.Error(nfe, this, "could not parse timeout %s" format timeoutStr)
      throw nfe
  }

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
    case e: IllegalArgumentException ⇒ null
  }

  /*
   * @see org.springframework.beans.factory.config.AbstractFactoryBean#createInstance()
   */
  def createInstance: AnyRef = {
    val ref = typed match {
      case TYPED_ACTOR_TAG ⇒
        val typedActor = createTypedInstance()
        setProperties(AspectInitRegistry.initFor(typedActor).targetInstance)
        typedActor
      case UNTYPED_ACTOR_TAG ⇒
        val untypedActor = createUntypedInstance()
        setProperties(untypedActor.actor)
        //Will always auto start
        //if (autostart)
        //  untypedActor
        untypedActor
      case _ ⇒ throw new IllegalArgumentException("Unknown actor type")
    }
    ref
  }

  private[akka] def createTypedInstance(): AnyRef = {
    if (!StringUtils.hasText(interface)) throw new AkkaBeansException(
      "The 'interface' part of the 'akka:actor' element in the Spring config file can't be null or empty string")
    if ((!StringUtils.hasText(implementation)) && (beanRef eq null)) throw new AkkaBeansException(
      "Either 'implementation' or 'ref' must be specified as attribute of the 'akka:typed-actor' element in the Spring config file ")

    val typedActor: AnyRef = if (beanRef eq null)
      TypedActor.newInstance(interface.toClass, implementation.toClass, createConfig)
    else
      TypedActor.newInstance(interface.toClass, getBeanFactory().getBean(beanRef), createConfig)

    if (isRemote && serverManaged) {
      if (serviceName.isEmpty) {
        Actor.remote.registerTypedActor(interface, typedActor)
      } else {
        Actor.remote.registerTypedActor(serviceName, typedActor)
      }
    }
    typedActor
  }

  /**
   * Create an UntypedActor.
   */
  private[akka] def createUntypedInstance(): ActorRef = {
    if ((!StringUtils.hasText(implementation)) && (beanRef eq null)) throw new AkkaBeansException(
      "Either 'implementation' or 'ref' must be specified as attribute of the 'akka:untyped-actor' element in the Spring config file ")

    val actorRef = if (isRemote && !serverManaged) { //If clientManaged
      if (beanRef ne null)
        Actor.remote.actorOf(getBeanFactory().getBean(beanRef).asInstanceOf[Actor], host, port.toInt)
      else
        Actor.remote.actorOf(implementation.toClass, host, port.toInt)
    } else {
      if (beanRef ne null)
        Actor.actorOf(getBeanFactory().getBean(beanRef).asInstanceOf[Actor])
      else
        Actor.actorOf(implementation.toClass)
    }

    if (timeout > 0)
      actorRef.setTimeout(timeout)

    if (StringUtils.hasText(id))
      actorRef.id = id

    if (hasDispatcher)
      actorRef.setDispatcher(dispatcherInstance(if (dispatcher.dispatcherType == THREAD_BASED) Some(actorRef) else None))

    if (isRemote && serverManaged) {
      if (serviceName.isEmpty)
        Actor.remote.register(actorRef)
      else
        Actor.remote.register(serviceName, actorRef)
    }

    actorRef
  }

  /**
   * Stop the typed actor if it is a singleton.
   */
  override def destroyInstance(instance: AnyRef) {
    typed match {
      case TYPED_ACTOR_TAG   ⇒ TypedActor.stop(instance)
      case UNTYPED_ACTOR_TAG ⇒ instance.asInstanceOf[ActorRef].stop
    }
  }

  private def setProperties(ref: AnyRef): AnyRef = {
    if (hasSetDependecies) return ref
    val beanWrapper = new BeanWrapperImpl(ref)
    if (ref.isInstanceOf[ApplicationContextAware]) {
      beanWrapper.setPropertyValue("applicationContext", applicationContext)
    }
    for (entry ← property.entryList) {
      val propertyDescriptor = BeanUtils.getPropertyDescriptor(ref.getClass, entry.name)
      val method = propertyDescriptor.getWriteMethod
      if (StringUtils.hasText(entry.ref)) {
        method.invoke(ref, getBeanFactory().getBean(entry.ref))
      } else if (StringUtils.hasText(entry.value)) {
        beanWrapper.setPropertyValue(entry.name, entry.value)
      } else throw new AkkaBeansException("Either property@ref or property@value must be set on property element")
    }
    ref
  }

  private[akka] def createConfig: TypedActorConfiguration = {
    val config = new TypedActorConfiguration().timeout(Duration(timeout, "millis"))
    if (isRemote && !serverManaged) config.makeRemote(host, port.toInt)
    if (StringUtils.hasText(id)) config.id(id)
    if (hasDispatcher) {
      if (dispatcher.dispatcherType == THREAD_BASED) {
        config.threadBasedDispatcher()
      } else {
        config.dispatcher(dispatcherInstance())
      }
    }
    config
  }

  private[akka] def isRemote = StringUtils.hasText(host)

  private[akka] def hasDispatcher = (dispatcher ne null) && (StringUtils.hasText(dispatcher.dispatcherType))

  /**
   * Create dispatcher instance with dispatcher properties.
   * @param actorRef actorRef for thread based dispatcher
   * @return new dispatcher instance
   */
  private[akka] def dispatcherInstance(actorRef: Option[ActorRef] = None): MessageDispatcher = {
    import DispatcherFactoryBean._
    if (dispatcher.dispatcherType == THREAD_BASED) {
      createNewInstance(dispatcher, actorRef)
    } else {
      createNewInstance(dispatcher)
    }
  }
}

/**
 * Factory bean for remote client actor-for.
 *
 * @author michaelkober
 */
class ActorForFactoryBean extends AbstractFactoryBean[AnyRef] with ApplicationContextAware {
  import AkkaSpringConfigurationTags._

  @BeanProperty
  var interface: String = ""
  @BeanProperty
  var host: String = ""
  @BeanProperty
  var port: String = ""
  @BeanProperty
  var serviceName: String = ""
  @BeanProperty
  var applicationContext: ApplicationContext = _

  override def isSingleton = false

  /*
   * @see org.springframework.beans.factory.FactoryBean#getObjectType()
   */
  def getObjectType: Class[AnyRef] = classOf[AnyRef]

  /*
   * @see org.springframework.beans.factory.config.AbstractFactoryBean#createInstance()
   */
  def createInstance: AnyRef = interface match {
    case null | "" ⇒ Actor.remote.actorFor(serviceName, host, port.toInt)
    case iface     ⇒ Actor.remote.typedActorFor(iface.toClass, serviceName, host, port.toInt)
  }
}

