/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.spring

import org.springframework.beans.factory.config.AbstractFactoryBean
import akka.actor.{ Supervisor, Actor, ActorRegistry }
import AkkaSpringConfigurationTags._
import reflect.BeanProperty
import akka.config.{ TypedActorConfigurator, RemoteAddress }

/**
 * Factory bean for supervisor configuration.
 * @author michaelkober
 */
class SupervisionFactoryBean extends AbstractFactoryBean[AnyRef] {
  @BeanProperty
  var restartStrategy: FaultHandlingStrategy = _
  @BeanProperty
  var supervised: List[ActorProperties] = _
  @BeanProperty
  var typed: String = ""

  /*
   * @see org.springframework.beans.factory.FactoryBean#getObjectType()
   */
  def getObjectType: Class[AnyRef] = classOf[AnyRef]

  /*
   * @see org.springframework.beans.factory.config.AbstractFactoryBean#createInstance()
   */
  def createInstance: AnyRef = typed match {
    case AkkaSpringConfigurationTags.TYPED_ACTOR_TAG   ⇒ createInstanceForTypedActors
    case AkkaSpringConfigurationTags.UNTYPED_ACTOR_TAG ⇒ createInstanceForUntypedActors
  }

  private def createInstanceForTypedActors(): TypedActorConfigurator = {
    val configurator = new TypedActorConfigurator()
    configurator.configure(
      restartStrategy,
      supervised.map(createComponent(_)).toArray).supervise

  }

  private def createInstanceForUntypedActors(): Supervisor = {
    val factory = new SupervisorFactory(
      new SupervisorConfig(
        restartStrategy,
        supervised.map(createSupervise(_))))
    factory.newInstance
  }

  /**
   * Create configuration for TypedActor
   */
  private[akka] def createComponent(props: ActorProperties): SuperviseTypedActor = {
    val lifeCycle = if (!props.lifecycle.isEmpty && props.lifecycle.equalsIgnoreCase(VAL_LIFECYCYLE_TEMPORARY)) Temporary else Permanent
    val isRemote = (props.host ne null) && (!props.host.isEmpty)
    val withInterface = (props.interface ne null) && (!props.interface.isEmpty)
    if (isRemote) {
      //val remote = new RemoteAddress(props.host, props.port)
      val remote = new RemoteAddress(props.host, props.port.toInt)
      if (withInterface) {
        new SuperviseTypedActor(props.interface.toClass, props.target.toClass, lifeCycle, props.timeout, remote)
      } else {
        new SuperviseTypedActor(props.target.toClass, lifeCycle, props.timeout, remote)
      }
    } else {
      if (withInterface) {
        new SuperviseTypedActor(props.interface.toClass, props.target.toClass, lifeCycle, props.timeout)
      } else {
        new SuperviseTypedActor(props.target.toClass, lifeCycle, props.timeout)
      }
    }
  }

  /**
   * Create configuration for UntypedActor
   */
  private[akka] def createSupervise(props: ActorProperties): Server = {
    val lifeCycle = if (!props.lifecycle.isEmpty && props.lifecycle.equalsIgnoreCase(VAL_LIFECYCYLE_TEMPORARY)) Temporary else Permanent
    val isRemote = (props.host ne null) && (!props.host.isEmpty)
    val actorRef = Actor.actorOf(props.target.toClass)

    if (props.timeout > 0)
      actorRef.setTimeout(props.timeout)

    Supervise(actorRef, lifeCycle, isRemote)
  }
}
