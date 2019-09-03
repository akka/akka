/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.adapter

import java.util.concurrent.CompletionStage

import scala.compat.java8.FutureConverters
import scala.concurrent.ExecutionContextExecutor

import akka.Done
import akka.actor
import akka.actor.ActorRefProvider
import akka.actor.ExtendedActorSystem
import akka.actor.InvalidMessageException
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.DispatcherSelector
import akka.actor.typed.Dispatchers
import akka.actor.typed.Logger
import akka.actor.typed.Props
import akka.actor.typed.Scheduler
import akka.actor.typed.Settings
import akka.actor.typed.internal.ActorRefImpl
import akka.actor.typed.internal.ExtensionsImpl
import akka.actor.typed.internal.InternalRecipientRef
import akka.actor.typed.internal.PropsImpl.DispatcherDefault
import akka.actor.typed.internal.PropsImpl.DispatcherFromConfig
import akka.actor.typed.internal.PropsImpl.DispatcherSameAsParent
import akka.actor.typed.internal.SystemMessage
import akka.annotation.InternalApi
import akka.event.LoggingFilterWithMarker
import akka.{ actor => untyped }

/**
 * INTERNAL API. Lightweight wrapper for presenting an untyped ActorSystem to a Behavior (via the context).
 * Therefore it does not have a lot of vals, only the whenTerminated Future is cached after
 * its transformation because redoing that every time will add extra objects that persist for
 * a longer time; in all other cases the wrapper will just be spawned for a single call in
 * most circumstances.
 */
@InternalApi private[akka] class ActorSystemAdapter[-T](val untypedSystem: untyped.ActorSystemImpl)
    extends ActorSystem[T]
    with ActorRef[T]
    with ActorRefImpl[T]
    with InternalRecipientRef[T]
    with ExtensionsImpl {

  // note that the untypedSystem may not be initialized yet here, and that is fine because
  // it is unlikely that anything gets a hold of the extension until the system is started

  import ActorRefAdapter.sendSystemMessage

  override private[akka] def classicSystem: untyped.ActorSystem = untypedSystem

  // Members declared in akka.actor.typed.ActorRef
  override def tell(msg: T): Unit = {
    if (msg == null) throw InvalidMessageException("[null] is not an allowed message")
    untypedSystem.guardian ! msg
  }

  // impl ActorRefImpl
  override def isLocal: Boolean = true
  // impl ActorRefImpl
  override def sendSystem(signal: SystemMessage): Unit = sendSystemMessage(untypedSystem.guardian, signal)

  // impl InternalRecipientRef
  override def provider: ActorRefProvider = untypedSystem.provider
  // impl InternalRecipientRef
  def isTerminated: Boolean = whenTerminated.isCompleted

  final override val path: untyped.ActorPath =
    untyped.RootActorPath(untyped.Address("akka", untypedSystem.name)) / "user"

  override def toString: String = untypedSystem.toString

  // Members declared in akka.actor.typed.ActorSystem
  override def deadLetters[U]: ActorRef[U] = ActorRefAdapter(untypedSystem.deadLetters)
  override def dispatchers: Dispatchers = new Dispatchers {
    override def lookup(selector: DispatcherSelector): ExecutionContextExecutor =
      selector match {
        case DispatcherDefault(_)         => untypedSystem.dispatcher
        case DispatcherFromConfig(str, _) => untypedSystem.dispatchers.lookup(str)
        case DispatcherSameAsParent(_)    => untypedSystem.dispatcher
      }
    override def shutdown(): Unit = () // there was no shutdown in untyped Akka
  }
  override def dynamicAccess: untyped.DynamicAccess = untypedSystem.dynamicAccess
  implicit override def executionContext: scala.concurrent.ExecutionContextExecutor = untypedSystem.dispatcher
  override val log: Logger = new LoggerAdapterImpl(
    untypedSystem.eventStream,
    classOf[ActorSystem[_]],
    name,
    LoggingFilterWithMarker.wrap(untypedSystem.logFilter))
  override def logConfiguration(): Unit = untypedSystem.logConfiguration()
  override def name: String = untypedSystem.name
  override val scheduler: Scheduler = new SchedulerAdapter(untypedSystem.scheduler)
  override def settings: Settings = new Settings(untypedSystem.settings)
  override def startTime: Long = untypedSystem.startTime
  override def threadFactory: java.util.concurrent.ThreadFactory = untypedSystem.threadFactory
  override def uptime: Long = untypedSystem.uptime
  override def printTree: String = untypedSystem.printTree

  import akka.dispatch.ExecutionContexts.sameThreadExecutionContext

  override def terminate(): Unit = untypedSystem.terminate()
  override lazy val whenTerminated: scala.concurrent.Future[akka.Done] =
    untypedSystem.whenTerminated.map(_ => Done)(sameThreadExecutionContext)
  override lazy val getWhenTerminated: CompletionStage[akka.Done] =
    FutureConverters.toJava(whenTerminated)

  override def systemActorOf[U](behavior: Behavior[U], name: String, props: Props): ActorRef[U] = {
    val ref = untypedSystem.systemActorOf(PropsAdapter(() => behavior, props), name)
    ActorRefAdapter(ref)
  }

}

private[akka] object ActorSystemAdapter {
  def apply(system: untyped.ActorSystem): ActorSystem[Nothing] = AdapterExtension(system).adapter

  // to make sure we do never create more than one adapter for the same actor system
  class AdapterExtension(system: untyped.ExtendedActorSystem) extends untyped.Extension {
    val adapter = new ActorSystemAdapter(system.asInstanceOf[untyped.ActorSystemImpl])
  }

  object AdapterExtension extends untyped.ExtensionId[AdapterExtension] with untyped.ExtensionIdProvider {
    override def get(system: untyped.ActorSystem): AdapterExtension = super.get(system)
    override def lookup() = AdapterExtension
    override def createExtension(system: untyped.ExtendedActorSystem): AdapterExtension =
      new AdapterExtension(system)
  }

  /**
   * A classic extension to load configured typed extensions. It is loaded via
   * akka.library-extensions. `loadExtensions` cannot be called from the AdapterExtension
   * directly because the adapter is created too early during typed actor system creation.
   *
   * When on the classpath typed extensions will be loaded for classic ActorSystems as well.
   */
  class LoadTypedExtensions(system: untyped.ExtendedActorSystem) extends untyped.Extension {
    ActorSystemAdapter.AdapterExtension(system).adapter.loadExtensions()
  }

  object LoadTypedExtensions extends untyped.ExtensionId[LoadTypedExtensions] with untyped.ExtensionIdProvider {
    override def lookup(): actor.ExtensionId[_ <: actor.Extension] = this
    override def createExtension(system: ExtendedActorSystem): LoadTypedExtensions =
      new LoadTypedExtensions(system)
  }

  def toUntyped[U](sys: ActorSystem[_]): untyped.ActorSystem =
    sys match {
      case adapter: ActorSystemAdapter[_] => adapter.untypedSystem
      case _ =>
        throw new UnsupportedOperationException(
          "Only adapted classic ActorSystem permissible " +
          s"($sys of class ${sys.getClass.getName})")
    }
}
