/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal
package adapter

import java.util.concurrent.CompletionStage

import akka.actor
import akka.actor.ExtendedActorSystem
import akka.actor.InvalidMessageException
import akka.{ actor ⇒ a }
import scala.concurrent.ExecutionContextExecutor

import akka.util.Timeout
import scala.concurrent.Future

import akka.annotation.InternalApi
import scala.compat.java8.FutureConverters

import akka.actor.ActorRefProvider

/**
 * INTERNAL API. Lightweight wrapper for presenting an untyped ActorSystem to a Behavior (via the context).
 * Therefore it does not have a lot of vals, only the whenTerminated Future is cached after
 * its transformation because redoing that every time will add extra objects that persist for
 * a longer time; in all other cases the wrapper will just be spawned for a single call in
 * most circumstances.
 */
@InternalApi private[akka] class ActorSystemAdapter[-T](val untyped: a.ActorSystemImpl)
  extends ActorSystem[T] with ActorRef[T] with internal.ActorRefImpl[T] with internal.InternalRecipientRef[T] with ExtensionsImpl {

  untyped.assertInitialized()

  import ActorRefAdapter.sendSystemMessage

  // Members declared in akka.actor.typed.ActorRef
  override def tell(msg: T): Unit = {
    if (msg == null) throw InvalidMessageException("[null] is not an allowed message")
    untyped.guardian ! msg
  }

  // impl ActorRefImpl
  override def isLocal: Boolean = true
  // impl ActorRefImpl
  override def sendSystem(signal: internal.SystemMessage): Unit = sendSystemMessage(untyped.guardian, signal)

  // impl InternalRecipientRef
  override def provider: ActorRefProvider = untyped.provider
  // impl InternalRecipientRef
  def isTerminated: Boolean = whenTerminated.isCompleted

  final override val path: a.ActorPath = a.RootActorPath(a.Address("akka", untyped.name)) / "user"

  override def toString: String = untyped.toString

  // Members declared in akka.actor.typed.ActorSystem
  override def deadLetters[U]: ActorRef[U] = ActorRefAdapter(untyped.deadLetters)
  override def dispatchers: Dispatchers = new Dispatchers {
    override def lookup(selector: DispatcherSelector): ExecutionContextExecutor =
      selector match {
        case DispatcherDefault(_)         ⇒ untyped.dispatcher
        case DispatcherFromConfig(str, _) ⇒ untyped.dispatchers.lookup(str)
      }
    override def shutdown(): Unit = () // there was no shutdown in untyped Akka
  }
  override def dynamicAccess: a.DynamicAccess = untyped.dynamicAccess
  implicit override def executionContext: scala.concurrent.ExecutionContextExecutor = untyped.dispatcher
  override val log: Logger = new LoggerAdapterImpl(untyped.eventStream, getClass, name, untyped.logFilter)
  override def logConfiguration(): Unit = untyped.logConfiguration()
  override def name: String = untyped.name
  override def scheduler: akka.actor.Scheduler = untyped.scheduler
  override def settings: Settings = new Settings(untyped.settings)
  override def startTime: Long = untyped.startTime
  override def threadFactory: java.util.concurrent.ThreadFactory = untyped.threadFactory
  override def uptime: Long = untyped.uptime
  override def printTree: String = untyped.printTree

  import akka.dispatch.ExecutionContexts.sameThreadExecutionContext

  override def terminate(): scala.concurrent.Future[akka.actor.typed.Terminated] =
    untyped.terminate().map(t ⇒ Terminated(ActorRefAdapter(t.actor)))(sameThreadExecutionContext)
  override lazy val whenTerminated: scala.concurrent.Future[akka.actor.typed.Terminated] =
    untyped.whenTerminated.map(t ⇒ Terminated(ActorRefAdapter(t.actor)))(sameThreadExecutionContext)
  override lazy val getWhenTerminated: CompletionStage[akka.actor.typed.Terminated] =
    FutureConverters.toJava(whenTerminated)

  def systemActorOf[U](behavior: Behavior[U], name: String, props: Props)(implicit timeout: Timeout): Future[ActorRef[U]] = {
    val ref = untyped.systemActorOf(PropsAdapter(() ⇒ behavior, props), name)
    Future.successful(ActorRefAdapter(ref))
  }

}

private[akka] object ActorSystemAdapter {
  def apply(untyped: a.ActorSystem): ActorSystem[Nothing] = AdapterExtension(untyped).adapter

  // to make sure we do never create more than one adapter for the same actor system
  class AdapterExtension(system: a.ExtendedActorSystem) extends a.Extension {
    val adapter = new ActorSystemAdapter(system.asInstanceOf[a.ActorSystemImpl])
  }

  object AdapterExtension extends a.ExtensionId[AdapterExtension] with a.ExtensionIdProvider {
    override def get(system: a.ActorSystem): AdapterExtension = super.get(system)
    override def lookup() = AdapterExtension
    override def createExtension(system: a.ExtendedActorSystem): AdapterExtension =
      new AdapterExtension(system)
  }

  /**
   * An untyped extension to load configured typed extensions. It is loaded via
   * akka.library-extensions. `loadExtensions` cannot be called from the AdapterExtension
   * directly because the adapter is created too early during typed actor system creation.
   *
   * When on the classpath typed extensions will be loaded for untyped ActorSystems as well.
   */
  class LoadTypedExtensions(system: a.ExtendedActorSystem) extends a.Extension {
    ActorSystemAdapter.AdapterExtension(system).adapter.loadExtensions()
  }

  object LoadTypedExtensions extends a.ExtensionId[LoadTypedExtensions] with a.ExtensionIdProvider {
    override def lookup(): actor.ExtensionId[_ <: actor.Extension] = this
    override def createExtension(system: ExtendedActorSystem): LoadTypedExtensions =
      new LoadTypedExtensions(system)
  }

  def toUntyped[U](sys: ActorSystem[_]): a.ActorSystem =
    sys match {
      case adapter: ActorSystemAdapter[_] ⇒ adapter.untyped
      case _ ⇒ throw new UnsupportedOperationException("only adapted untyped ActorSystem permissible " +
        s"($sys of class ${sys.getClass.getName})")
    }
}
