/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.adapter
import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi
import akka.{ConfigurationException, actor => untyped}

/**
 * INTERNAL API
 */
@InternalApi private[typed] object ActorRefFactoryAdapter {

  private def toUntypedImp[U](context: TypedActorContext[_]): untyped.ActorContext =
    context match {
      case adapter: ActorContextAdapter[_] => adapter.untypedContext
      case _ =>
        throw new UnsupportedOperationException(
          "only adapted untyped ActorContext permissible " +
          s"($context of class ${context.getClass.getName})")
    }

  // TODO remove?
  def toUntyped2[U](context: TypedActorContext[_]): untyped.ActorContext = toUntypedImp(context)

  def toUntyped[U](context: scaladsl.ActorContext[_]): untyped.ActorContext =
    context match {
      case c: TypedActorContext[_] => toUntypedImp(c)
      case _ =>
        throw new UnsupportedOperationException(
          "unknown ActorContext type " +
          s"($context of class ${context.getClass.getName})")
    }

  def toUntyped[U](context: javadsl.ActorContext[_]): untyped.ActorContext =
    context match {
      case c: TypedActorContext[_] => toUntypedImp(c)
      case _ =>
        throw new UnsupportedOperationException(
          "unknown ActorContext type " +
          s"($context of class ${context.getClass.getName})")
    }

  def spawnAnonymous[T](context: akka.actor.ActorRefFactory, behavior: Behavior[T], props: Props): ActorRef[T] = {
    try {
      val withDefaultSupervision = Behaviors.supervise(behavior).onFailure(SupervisorStrategy.stop)
      ActorRefAdapter(context.actorOf(internal.adapter.PropsAdapter(() => withDefaultSupervision, props, rethrowTypedFailure = false)))
    } catch {
      case ex: ConfigurationException if ex.getMessage.startsWith("configuration requested remote deployment") =>
        throw new ConfigurationException("Remote deployment not allowed for typed actors", ex)
    }
  }

  def spawn[T](actorRefFactory: akka.actor.ActorRefFactory, behavior: Behavior[T], name: String, props: Props): ActorRef[T] = {
    try {
      val withDefaultSupervision = Behaviors.supervise(behavior).onFailure(SupervisorStrategy.stop)
      ActorRefAdapter(actorRefFactory.actorOf(internal.adapter.PropsAdapter(() => Behavior.validateAsInitial(withDefaultSupervision), props, rethrowTypedFailure = false), name))
    } catch {
      case ex: ConfigurationException if ex.getMessage.startsWith("configuration requested remote deployment") =>
        throw new ConfigurationException("Remote deployment not allowed for typed actors", ex)
    }
  }

}
