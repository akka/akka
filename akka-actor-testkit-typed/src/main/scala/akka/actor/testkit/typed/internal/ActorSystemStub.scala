/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.internal

import java.util.concurrent.{ CompletionStage, ThreadFactory }

import akka.actor.typed.internal.ActorRefImpl
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, DispatcherSelector, Dispatchers, Extension, ExtensionId, Logger, Props, Settings, Terminated }
import akka.annotation.InternalApi
import akka.util.Timeout
import akka.{ actor ⇒ untyped }
import com.typesafe.config.ConfigFactory
import scala.compat.java8.FutureConverters
import scala.concurrent._

import akka.actor.ActorRefProvider
import akka.actor.typed.internal.InternalRecipientRef

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class ActorSystemStub(val name: String)
  extends ActorSystem[Nothing] with ActorRef[Nothing] with ActorRefImpl[Nothing] with InternalRecipientRef[Nothing] {

  override val path: untyped.ActorPath = untyped.RootActorPath(untyped.Address("akka", name)) / "user"

  override val settings: Settings = new Settings(getClass.getClassLoader, ConfigFactory.empty, name)

  override def tell(message: Nothing): Unit = throw new UnsupportedOperationException("must not send message to ActorSystemStub")

  // impl ActorRefImpl
  override def isLocal: Boolean = true
  // impl ActorRefImpl
  override def sendSystem(signal: akka.actor.typed.internal.SystemMessage): Unit =
    throw new UnsupportedOperationException("must not send SYSTEM message to ActorSystemStub")

  // impl InternalRecipientRef, ask not supported
  override def provider: ActorRefProvider = throw new UnsupportedOperationException("no provider")
  // impl InternalRecipientRef
  def isTerminated: Boolean = whenTerminated.isCompleted

  val deadLettersInbox = new DebugRef[Any](path.parent / "deadLetters", true)
  override def deadLetters[U]: akka.actor.typed.ActorRef[U] = deadLettersInbox

  val controlledExecutor = new ControlledExecutor
  implicit override def executionContext: scala.concurrent.ExecutionContextExecutor = controlledExecutor
  override def dispatchers: akka.actor.typed.Dispatchers = new Dispatchers {
    def lookup(selector: DispatcherSelector): ExecutionContextExecutor = controlledExecutor
    def shutdown(): Unit = ()
  }

  override def dynamicAccess: untyped.DynamicAccess = new untyped.ReflectiveDynamicAccess(getClass.getClassLoader)

  override def logConfiguration(): Unit = log.info(settings.toString)

  override def scheduler: untyped.Scheduler = throw new UnsupportedOperationException("no scheduler")

  private val terminationPromise = Promise[Terminated]
  override def terminate(): Future[akka.actor.typed.Terminated] = {
    terminationPromise.trySuccess(Terminated(this))
    terminationPromise.future
  }
  override def whenTerminated: Future[akka.actor.typed.Terminated] = terminationPromise.future
  override def getWhenTerminated: CompletionStage[Terminated] = FutureConverters.toJava(whenTerminated)
  override val startTime: Long = System.currentTimeMillis()
  override def uptime: Long = System.currentTimeMillis() - startTime
  override def threadFactory: java.util.concurrent.ThreadFactory = new ThreadFactory {
    override def newThread(r: Runnable): Thread = new Thread(r)
  }

  override def printTree: String = "no tree for ActorSystemStub"

  def systemActorOf[U](behavior: Behavior[U], name: String, props: Props)(implicit timeout: Timeout): Future[ActorRef[U]] = {
    Future.failed(new UnsupportedOperationException("ActorSystemStub cannot create system actors"))
  }

  def registerExtension[T <: Extension](ext: ExtensionId[T]): T =
    throw new UnsupportedOperationException("ActorSystemStub cannot register extensions")

  def extension[T <: Extension](ext: ExtensionId[T]): T =
    throw new UnsupportedOperationException("ActorSystemStub cannot register extensions")

  def hasExtension(ext: ExtensionId[_ <: Extension]): Boolean =
    throw new UnsupportedOperationException("ActorSystemStub cannot register extensions")

  def log: Logger = new StubbedLogger
}
