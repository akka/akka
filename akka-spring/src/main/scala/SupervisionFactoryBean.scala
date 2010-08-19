/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

import org.springframework.beans.factory.config.AbstractFactoryBean
import se.scalablesolutions.akka.config.TypedActorConfigurator
import se.scalablesolutions.akka.config.JavaConfig._
import se.scalablesolutions.akka.config.ScalaConfig.{Supervise, Server, SupervisorConfig, RemoteAddress => SRemoteAddress}
import se.scalablesolutions.akka.actor.{Supervisor, SupervisorFactory, Actor}
import AkkaSpringConfigurationTags._
import reflect.BeanProperty


/**
 * Factory bean for supervisor configuration.
 * @author michaelkober
 */
class SupervisionFactoryBean extends AbstractFactoryBean[AnyRef] {
  @BeanProperty var restartStrategy: RestartStrategy = _
  @BeanProperty var supervised: List[ActorProperties] = _
  @BeanProperty var typed: String = ""

  /*
   * @see org.springframework.beans.factory.FactoryBean#getObjectType()
   */
  def getObjectType: Class[AnyRef] = classOf[AnyRef]

  /*
   * @see org.springframework.beans.factory.config.AbstractFactoryBean#createInstance()
   */
  def createInstance: AnyRef = typed match {
    case AkkaSpringConfigurationTags.TYPED_ACTOR_TAG => createInstanceForTypedActors
    case AkkaSpringConfigurationTags.UNTYPED_ACTOR_TAG => createInstanceForUntypedActors
  }

  private def createInstanceForTypedActors() : TypedActorConfigurator = {
    val configurator = new TypedActorConfigurator()
    configurator.configure(
      restartStrategy,
      supervised.map(createComponent(_)).toArray
      ).supervise

  }

  private def createInstanceForUntypedActors() : Supervisor = {
    val factory = new SupervisorFactory(
      new SupervisorConfig(
        restartStrategy.transform,
        supervised.map(createSupervise(_))))
    factory.newInstance
  }

  /**
   * Create configuration for TypedActor
   */
  private[akka] def createComponent(props: ActorProperties): Component = {
    import StringReflect._
    val lifeCycle = if (!props.lifecycle.isEmpty && props.lifecycle.equalsIgnoreCase(VAL_LIFECYCYLE_TEMPORARY)) new LifeCycle(new Temporary()) else new LifeCycle(new Permanent())
    val isRemote = (props.host != null) && (!props.host.isEmpty)
    val withInterface = (props.interface != null) && (!props.interface.isEmpty)
    if (isRemote) {
      val remote = new RemoteAddress(props.host, props.port)
      if (withInterface) {
        new Component(props.interface.toClass, props.target.toClass, lifeCycle, props.timeout, props.transactional, remote)
      } else {
        new Component(props.target.toClass, lifeCycle, props.timeout, props.transactional, remote)
      }
    } else {
      if (withInterface) {
        new Component(props.interface.toClass, props.target.toClass, lifeCycle, props.timeout, props.transactional)
      } else {
        new Component(props.target.toClass, lifeCycle, props.timeout, props.transactional)
      }
    }
  }

  /**
   * Create configuration for UntypedActor
   */
  private[akka] def createSupervise(props: ActorProperties): Server = {
    import StringReflect._
    val lifeCycle = if (!props.lifecycle.isEmpty && props.lifecycle.equalsIgnoreCase(VAL_LIFECYCYLE_TEMPORARY)) new LifeCycle(new Temporary()) else new LifeCycle(new Permanent())
    val isRemote = (props.host != null) && (!props.host.isEmpty)
    val actorRef = Actor.actorOf(props.target.toClass)
    if (props.timeout > 0) {
      actorRef.setTimeout(props.timeout)
    }
    if (props.transactional) {
      actorRef.makeTransactionRequired
    }

    val supervise = if (isRemote) {
      val remote = new SRemoteAddress(props.host, props.port)
      Supervise(actorRef, lifeCycle.transform, remote)
    } else {
      Supervise(actorRef, lifeCycle.transform)
    }
    supervise
  }
}
