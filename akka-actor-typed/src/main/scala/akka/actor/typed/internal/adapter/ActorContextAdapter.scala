/*
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal
package adapter

import akka.actor.ExtendedActorSystem
import akka.annotation.InternalApi
import akka.util.OptionVal
import akka.{ ConfigurationException, actor ⇒ a }

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

/**
 * INTERNAL API. Wrapping an [[akka.actor.ActorContext]] as an [[TypedActorContext]].
 */
@InternalApi private[akka] final class ActorContextAdapter[T](val untyped: a.ActorContext) extends ActorContextImpl[T] {

  import ActorRefAdapter.toUntyped

  // lazily initialized
  private var actorLogger: OptionVal[Logger] = OptionVal.None

  final override val self = ActorRefAdapter(untyped.self)
  final override val system = ActorSystemAdapter(untyped.system)
  override def children = untyped.children.map(ActorRefAdapter(_))
  override def child(name: String) = untyped.child(name).map(ActorRefAdapter(_))
  override def spawnAnonymous[U](behavior: Behavior[U], props: Props = Props.empty) =
    ActorContextAdapter.spawnAnonymous(untyped, behavior, props)
  override def spawn[U](behavior: Behavior[U], name: String, props: Props = Props.empty) =
    ActorContextAdapter.spawn(untyped, behavior, name, props)
  override def stop[U](child: ActorRef[U]): Unit =
    if (child.path.parent == self.path) { // only if a direct child
      toUntyped(child) match {
        case f: akka.actor.FunctionRef ⇒
          val cell = untyped.asInstanceOf[akka.actor.ActorCell]
          cell.removeFunctionRef(f)
        case c ⇒
          untyped.child(child.path.name) match {
            case Some(`c`) ⇒
              untyped.stop(c)
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

  override def watch[U](other: ActorRef[U]) = { untyped.watch(toUntyped(other)) }
  override def watchWith[U](other: ActorRef[U], msg: T) = { untyped.watchWith(toUntyped(other), msg) }
  override def unwatch[U](other: ActorRef[U]) = { untyped.unwatch(toUntyped(other)) }
  var receiveTimeoutMsg: T = null.asInstanceOf[T]
  override def setReceiveTimeout(d: FiniteDuration, msg: T) = {
    receiveTimeoutMsg = msg
    untyped.setReceiveTimeout(d)
  }
  override def cancelReceiveTimeout(): Unit = {
    receiveTimeoutMsg = null.asInstanceOf[T]
    untyped.setReceiveTimeout(Duration.Undefined)
  }
  override def executionContext: ExecutionContextExecutor = untyped.dispatcher
  override def scheduleOnce[U](delay: FiniteDuration, target: ActorRef[U], msg: U): a.Cancellable = {
    import untyped.dispatcher
    untyped.system.scheduler.scheduleOnce(delay, toUntyped(target), msg)
  }
  override private[akka] def internalSpawnMessageAdapter[U](f: U ⇒ T, _name: String): ActorRef[U] = {
    val cell = untyped.asInstanceOf[akka.actor.ActorCell]
    // apply the function inside the actor by wrapping the msg and f, handled by ActorAdapter
    val ref = cell.addFunctionRef((_, msg) ⇒ untyped.self ! AdaptMessage[U, T](msg.asInstanceOf[U], f), _name)
    ActorRefAdapter[U](ref)
  }

  override def log: Logger = {
    actorLogger match {
      case OptionVal.Some(logger) ⇒ logger
      case OptionVal.None ⇒
        val logSource = self.path.toString
        val logClass = classOf[Behavior[_]] // FIXME figure out a better class somehow
        val system = untyped.system.asInstanceOf[ExtendedActorSystem]
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

  private def toUntypedImp[U](ctx: TypedActorContext[_]): a.ActorContext =
    ctx match {
      case adapter: ActorContextAdapter[_] ⇒ adapter.untyped
      case _ ⇒
        throw new UnsupportedOperationException("only adapted untyped ActorContext permissible " +
          s"($ctx of class ${ctx.getClass.getName})")
    }

  def toUntyped2[U](ctx: TypedActorContext[_]): a.ActorContext = toUntypedImp(ctx)

  def toUntyped[U](ctx: scaladsl.ActorContext[_]): a.ActorContext =
    ctx match {
      case c: TypedActorContext[_] ⇒ toUntypedImp(c)
      case _ ⇒
        throw new UnsupportedOperationException("unknown ActorContext type " +
          s"($ctx of class ${ctx.getClass.getName})")
    }

  def toUntyped[U](ctx: javadsl.ActorContext[_]): a.ActorContext =
    ctx match {
      case c: TypedActorContext[_] ⇒ toUntypedImp(c)
      case _ ⇒
        throw new UnsupportedOperationException("unknown ActorContext type " +
          s"($ctx of class ${ctx.getClass.getName})")
    }

  def spawnAnonymous[T](ctx: akka.actor.ActorContext, behavior: Behavior[T], props: Props): ActorRef[T] = {
    try {
      Behavior.validateAsInitial(behavior)
      ActorRefAdapter(ctx.actorOf(PropsAdapter(() ⇒ behavior, props)))
    } catch {
      case ex: ConfigurationException if ex.getMessage.startsWith("configuration requested remote deployment") ⇒
        throw new ConfigurationException("Remote deployment not allowed for typed actors", ex)
    }
  }

  def spawn[T](ctx: akka.actor.ActorContext, behavior: Behavior[T], name: String, props: Props): ActorRef[T] = {
    try {
      Behavior.validateAsInitial(behavior)
      ActorRefAdapter(ctx.actorOf(PropsAdapter(() ⇒ behavior, props), name))
    } catch {
      case ex: ConfigurationException if ex.getMessage.startsWith("configuration requested remote deployment") ⇒
        throw new ConfigurationException("Remote deployment not allowed for typed actors", ex)
    }
  }

}
