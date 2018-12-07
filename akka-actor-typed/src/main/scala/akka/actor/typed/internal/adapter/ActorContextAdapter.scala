/*
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
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
 * INTERNAL API. Wrapping an [[akka.actor.ActorContext]] as an [[ActorContext]].
 */
@InternalApi private[akka] final class ActorContextAdapter[T](val context: untyped.ActorContext) extends ActorContextImpl[T] {

  import ActorRefAdapter.toUntyped

  // lazily initialized
  private var actorLogger: OptionVal[Logger] = OptionVal.None

  override def self = ActorRefAdapter(context.self)
  override val system = ActorSystemAdapter(context.system)
  override def children: Iterable[ActorRef[Nothing]] = context.children.map(ActorRefAdapter(_))
  override def child(name: String): Option[ActorRef[Nothing]] = context.child(name).map(ActorRefAdapter(_))
  override def spawnAnonymous[U](behavior: Behavior[U], props: Props = Props.empty): ActorRef[U] =
    ActorContextAdapter.spawnAnonymous(context, behavior, props)
  override def spawn[U](behavior: Behavior[U], name: String, props: Props = Props.empty): ActorRef[U] =
    ActorContextAdapter.spawn(context, behavior, name, props)
  override def stop[U](child: ActorRef[U]): Unit =
    if (child.path.parent == self.path) { // only if untyped direct child
      toUntyped(child) match {
        case f: akka.actor.FunctionRef ⇒
          val cell = context.asInstanceOf[akka.actor.ActorCell]
          cell.removeFunctionRef(f)
        case c ⇒
          context.child(child.path.name) match {
            case Some(`c`) ⇒
              context.stop(c)
            case _ ⇒
            // child that was already stopped
          }
      }
    } else {
      throw new IllegalArgumentException(
        "Only direct children of an actor can be stopped through the actor context, " +
          s"but [$child] is not untyped child of [$self]. Stopping other actors has to be expressed as " +
          "an explicit stop message that the actor accepts.")
    }

  override def watch[U](other: ActorRef[U]): Unit = { context.watch(toUntyped(other)) }
  override def watchWith[U](other: ActorRef[U], msg: T): Unit = { context.watchWith(toUntyped(other), msg) }
  override def unwatch[U](other: ActorRef[U]): Unit = { context.unwatch(toUntyped(other)) }
  var receiveTimeoutMsg: T = null.asInstanceOf[T]
  override def setReceiveTimeout(d: FiniteDuration, msg: T): Unit = {
    receiveTimeoutMsg = msg
    context.setReceiveTimeout(d)
  }
  override def cancelReceiveTimeout(): Unit = {
    receiveTimeoutMsg = null.asInstanceOf[T]
    context.setReceiveTimeout(Duration.Undefined)
  }
  override def executionContext: ExecutionContextExecutor = context.dispatcher
  override def scheduleOnce[U](delay: FiniteDuration, target: ActorRef[U], msg: U): untyped.Cancellable = {
    import context.dispatcher
    context.system.scheduler.scheduleOnce(delay, toUntyped(target), msg)
  }
  override private[akka] def internalSpawnMessageAdapter[U](f: U ⇒ T, _name: String): ActorRef[U] = {
    val cell = context.asInstanceOf[akka.actor.ActorCell]
    // apply the function inside the actor by wrapping the msg and f, handled by ActorAdapter
    val ref = cell.addFunctionRef((_, msg) ⇒ context.self ! AdaptMessage[U, T](msg.asInstanceOf[U], f), _name)
    ActorRefAdapter[U](ref)
  }

  override def log: Logger = {
    actorLogger match {
      case OptionVal.Some(logger) ⇒ logger
      case OptionVal.None ⇒
        val logSource = self.path.toString
        val logClass = classOf[Behavior[_]] // FIXME figure out untyped better class somehow
        val system = context.system.asInstanceOf[ExtendedActorSystem]
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

  private def toUntypedImp[U](context: ActorContext[_]): untyped.ActorContext =
    context match {
      case adapter: ActorContextAdapter[_] ⇒ adapter.context
      case _ ⇒
        throw new UnsupportedOperationException("only adapted untyped ActorContext permissible " +
          s"($context of class ${context.getClass.getName})")
    }

  def toUntyped2[U](context: ActorContext[_]): untyped.ActorContext = toUntypedImp(context)

  def toUntyped[U](context: scaladsl.ActorContext[_]): untyped.ActorContext =
    context match {
      case c: ActorContext[_] ⇒ toUntypedImp(c)
      case _ ⇒
        throw new UnsupportedOperationException("unknown ActorContext type " +
          s"($context of class ${context.getClass.getName})")
    }

  def toUntyped[U](context: javadsl.ActorContext[_]): untyped.ActorContext = context match {
    case c: ActorContext[_] ⇒ toUntypedImp(c)
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
