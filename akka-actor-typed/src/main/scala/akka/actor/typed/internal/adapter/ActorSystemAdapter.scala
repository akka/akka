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
import akka.{ actor => classic }

/**
 * INTERNAL API. Lightweight wrapper for presenting a classic ActorSystem to a Behavior (via the context).
 * Therefore it does not have a lot of vals, only the whenTerminated Future is cached after
 * its transformation because redoing that every time will add extra objects that persist for
 * a longer time; in all other cases the wrapper will just be spawned for a single call in
 * most circumstances.
 */
@InternalApi private[akka] class ActorSystemAdapter[-T](val system: classic.ActorSystemImpl)
    extends ActorSystem[T]
    with ActorRef[T]
    with ActorRefImpl[T]
    with InternalRecipientRef[T]
    with ExtensionsImpl {

  // note that the (classic) system may not be initialized yet here, and that is fine because
  // it is unlikely that anything gets a hold of the extension until the system is started

  import ActorRefAdapter.sendSystemMessage

  override private[akka] def classicSystem: classic.ActorSystem = system

  // Members declared in akka.actor.typed.ActorRef
  override def tell(msg: T): Unit = {
    if (msg == null) throw InvalidMessageException("[null] is not an allowed message")
    system.guardian ! msg
  }

  // impl ActorRefImpl
  override def isLocal: Boolean = true
  // impl ActorRefImpl
  override def sendSystem(signal: SystemMessage): Unit = sendSystemMessage(system.guardian, signal)

  // impl InternalRecipientRef
  override def provider: ActorRefProvider = system.provider
  // impl InternalRecipientRef
  def isTerminated: Boolean = whenTerminated.isCompleted

  final override val path: classic.ActorPath =
    classic.RootActorPath(classic.Address("akka", system.name)) / "user"

  override def toString: String = system.toString

  // Members declared in akka.actor.typed.ActorSystem
  override def deadLetters[U]: ActorRef[U] = ActorRefAdapter(system.deadLetters)
  override def dispatchers: Dispatchers = new Dispatchers {
    override def lookup(selector: DispatcherSelector): ExecutionContextExecutor =
      selector match {
        case DispatcherDefault(_)         => system.dispatcher
        case DispatcherFromConfig(str, _) => system.dispatchers.lookup(str)
        case DispatcherSameAsParent(_)    => system.dispatcher
      }
    override def shutdown(): Unit = () // there was no shutdown in classic Akka
  }
  override def dynamicAccess: classic.DynamicAccess = system.dynamicAccess
  implicit override def executionContext: scala.concurrent.ExecutionContextExecutor = system.dispatcher
  override val log: Logger = new LoggerAdapterImpl(
    system.eventStream,
    classOf[ActorSystem[_]],
    name,
    LoggingFilterWithMarker.wrap(system.logFilter))
  override def logConfiguration(): Unit = system.logConfiguration()
  override def name: String = system.name
  override val scheduler: Scheduler = new SchedulerAdapter(system.scheduler)
  override def settings: Settings = new Settings(system.settings)
  override def startTime: Long = system.startTime
  override def threadFactory: java.util.concurrent.ThreadFactory = system.threadFactory
  override def uptime: Long = system.uptime
  override def printTree: String = system.printTree

  import akka.dispatch.ExecutionContexts.sameThreadExecutionContext

  override def terminate(): Unit = system.terminate()
  override lazy val whenTerminated: scala.concurrent.Future[akka.Done] =
    system.whenTerminated.map(_ => Done)(sameThreadExecutionContext)
  override lazy val getWhenTerminated: CompletionStage[akka.Done] =
    FutureConverters.toJava(whenTerminated)

  override def systemActorOf[U](behavior: Behavior[U], name: String, props: Props): ActorRef[U] = {
    val ref = system.systemActorOf(PropsAdapter(() => behavior, props), name)
    ActorRefAdapter(ref)
  }

}

private[akka] object ActorSystemAdapter {
  def apply(system: classic.ActorSystem): ActorSystem[Nothing] = AdapterExtension(system).adapter

  // to make sure we do never create more than one adapter for the same actor system
  class AdapterExtension(system: classic.ExtendedActorSystem) extends classic.Extension {
    val adapter = new ActorSystemAdapter(system.asInstanceOf[classic.ActorSystemImpl])
  }

  object AdapterExtension extends classic.ExtensionId[AdapterExtension] with classic.ExtensionIdProvider {
    override def get(system: classic.ActorSystem): AdapterExtension = super.get(system)
    override def lookup() = AdapterExtension
    override def createExtension(system: classic.ExtendedActorSystem): AdapterExtension =
      new AdapterExtension(system)
  }

  /**
   * A classic extension to load configured typed extensions. It is loaded via
   * akka.library-extensions. `loadExtensions` cannot be called from the AdapterExtension
   * directly because the adapter is created too early during typed actor system creation.
   *
   * When on the classpath typed extensions will be loaded for classic ActorSystems as well.
   */
  class LoadTypedExtensions(system: classic.ExtendedActorSystem) extends classic.Extension {
    ActorSystemAdapter.AdapterExtension(system).adapter.loadExtensions()
  }

  object LoadTypedExtensions extends classic.ExtensionId[LoadTypedExtensions] with classic.ExtensionIdProvider {
    override def lookup(): actor.ExtensionId[_ <: actor.Extension] = this
    override def createExtension(system: ExtendedActorSystem): LoadTypedExtensions =
      new LoadTypedExtensions(system)
  }

  def toClassic[U](sys: ActorSystem[_]): classic.ActorSystem =
    sys match {
      case adapter: ActorSystemAdapter[_] => adapter.classicSystem
      case _ =>
        throw new UnsupportedOperationException(
          "Only adapted classic ActorSystem permissible " +
          s"($sys of class ${sys.getClass.getName})")
    }
}
