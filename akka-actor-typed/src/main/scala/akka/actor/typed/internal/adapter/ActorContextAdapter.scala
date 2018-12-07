/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal
package adapter

import akka.actor.ExtendedActorSystem
import akka.annotation.InternalApi
import akka.util.OptionVal
import akka.{ ConfigurationException, actor ⇒ untyped }

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

/**
 * INTERNAL API. Wrapping an [[akka.actor.ActorContext]] as an [[TypedActorContext]].
 */
@InternalApi private[akka] final class ActorContextAdapter[T](val untypedContext: untyped.ActorContext) extends ActorContextImpl[T] {

  import ActorRefAdapter.toUntyped

  // lazily initialized
  private var actorLogger: OptionVal[Logger] = OptionVal.None

  final override val self = ActorRefAdapter(untypedContext.self)
  final override val system = ActorSystemAdapter(untypedContext.system)
  override def children: Iterable[ActorRef[Nothing]] = untypedContext.children.map(ActorRefAdapter(_))
  override def child(name: String): Option[ActorRef[Nothing]] = untypedContext.child(name).map(ActorRefAdapter(_))
  override def spawnAnonymous[U](behavior: Behavior[U], props: Props = Props.empty): ActorRef[U] =
    ActorContextAdapter.spawnAnonymous(untypedContext, behavior, props)
  override def spawn[U](behavior: Behavior[U], name: String, props: Props = Props.empty): ActorRef[U] =
    ActorContextAdapter.spawn(untypedContext, behavior, name, props)
  override def stop[U](child: ActorRef[U]): Unit =
    if (child.path.parent == self.path) { // only if a direct child
      toUntyped(child) match {
        case f: akka.actor.FunctionRef ⇒
          val cell = untypedContext.asInstanceOf[akka.actor.ActorCell]
          cell.removeFunctionRef(f)
        case c ⇒
          untypedContext.child(child.path.name) match {
            case Some(`c`) ⇒
              untypedContext.stop(c)
            case _ ⇒
            // child that was already stopped
          }
      }
    } else if (self == child) {
      throw new IllegalArgumentException(
        "Only direct children of an actor can be stopped through the actor context, " +
          s"but you tried to stop [$self] by passing its ActorRef to the `stop` method. " +
          "Stopping self has to be expressed as explicitly returning a Stop Behavior " +
          "with `Behaviors.stopped`.")
    } else {
      throw new IllegalArgumentException(
        "Only direct children of an actor can be stopped through the actor context, " +
          s"but [$child] is not a child of [$self]. Stopping other actors has to be expressed as " +
          "an explicit stop message that the actor accepts.")
    }

  override def watch[U](other: ActorRef[U]): Unit = { untypedContext.watch(toUntyped(other)) }
  override def watchWith[U](other: ActorRef[U], msg: T): Unit = { untypedContext.watchWith(toUntyped(other), msg) }
  override def unwatch[U](other: ActorRef[U]): Unit = { untypedContext.unwatch(toUntyped(other)) }
  var receiveTimeoutMsg: T = null.asInstanceOf[T]
  override def setReceiveTimeout(d: FiniteDuration, msg: T): Unit = {
    receiveTimeoutMsg = msg
    untypedContext.setReceiveTimeout(d)
  }
  override def cancelReceiveTimeout(): Unit = {
    receiveTimeoutMsg = null.asInstanceOf[T]
    untypedContext.setReceiveTimeout(Duration.Undefined)
  }
  override def executionContext: ExecutionContextExecutor = untypedContext.dispatcher
  override def scheduleOnce[U](delay: FiniteDuration, target: ActorRef[U], msg: U): untyped.Cancellable = {
    import untypedContext.dispatcher
    untypedContext.system.scheduler.scheduleOnce(delay, toUntyped(target), msg)
  }
  override private[akka] def internalSpawnMessageAdapter[U](f: U ⇒ T, _name: String): ActorRef[U] = {
    val cell = untypedContext.asInstanceOf[akka.actor.ActorCell]
    // apply the function inside the actor by wrapping the msg and f, handled by ActorAdapter
    val ref = cell.addFunctionRef((_, msg) ⇒ untypedContext.self ! AdaptMessage[U, T](msg.asInstanceOf[U], f), _name)
    ActorRefAdapter[U](ref)
  }

  override def log: Logger = {
    actorLogger match {
      case OptionVal.Some(logger) ⇒ logger
      case OptionVal.None ⇒
        val logSource = self.path.toString
        val logClass = classOf[Behavior[_]] // FIXME figure out untyped better class somehow
        val system = untypedContext.system.asInstanceOf[ExtendedActorSystem]
        val logger = new LoggerAdapterImpl(system.eventStream, logClass, logSource, system.logFilter)
        actorLogger = OptionVal.Some(logger)
        logger
    }
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[typed] object ActorContextAdapter {

  private def toUntypedImp[U](context: TypedActorContext[_]): untyped.ActorContext =
    context match {
      case adapter: ActorContextAdapter[_] ⇒ adapter.untypedContext
      case _ ⇒
        throw new UnsupportedOperationException("only adapted untyped ActorContext permissible " +
          s"($context of class ${context.getClass.getName})")
    }

  def toUntyped2[U](context: TypedActorContext[_]): untyped.ActorContext = toUntypedImp(context)

  def toUntyped[U](context: scaladsl.ActorContext[_]): untyped.ActorContext =
    context match {
      case c: TypedActorContext[_] ⇒ toUntypedImp(c)
      case _ ⇒
        throw new UnsupportedOperationException("unknown ActorContext type " +
          s"($context of class ${context.getClass.getName})")
    }

  def toUntyped[U](context: javadsl.ActorContext[_]): untyped.ActorContext =
    context match {
      case c: TypedActorContext[_] ⇒ toUntypedImp(c)
      case _ ⇒
        throw new UnsupportedOperationException("unknown ActorContext type " +
          s"($context of class ${context.getClass.getName})")
    }

  def spawnAnonymous[T](context: akka.actor.ActorContext, behavior: Behavior[T], props: Props): ActorRef[T] = {
    try {
      Behavior.validateAsInitial(behavior)
      ActorRefAdapter(context.actorOf(PropsAdapter(() ⇒ behavior, props)))
    } catch {
      case ex: ConfigurationException if ex.getMessage.startsWith("configuration requested remote deployment") ⇒
        throw new ConfigurationException("Remote deployment not allowed for typed actors", ex)
    }
  }

  def spawn[T](context: akka.actor.ActorContext, behavior: Behavior[T], name: String, props: Props): ActorRef[T] = {
    try {
      Behavior.validateAsInitial(behavior)
      ActorRefAdapter(context.actorOf(PropsAdapter(() ⇒ behavior, props), name))
    } catch {
      case ex: ConfigurationException if ex.getMessage.startsWith("configuration requested remote deployment") ⇒
        throw new ConfigurationException("Remote deployment not allowed for typed actors", ex)
    }
  }

}
