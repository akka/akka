/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed.internal

import java.util.concurrent.{ CompletionStage, ThreadFactory }

import akka.actor.typed.internal.ActorRefImpl
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, DispatcherSelector, Dispatchers, Extension, ExtensionId, Logger, Props, Settings, Terminated }
import akka.annotation.InternalApi
import akka.util.Timeout
import akka.{ actor ⇒ a }
import com.typesafe.config.ConfigFactory

import scala.compat.java8.FutureConverters
import scala.concurrent._
import scala.collection.mutable

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class ActorSystemStub(val name: String)
  extends ActorSystem[Nothing] with ActorRef[Nothing] with ActorRefImpl[Nothing] {

  // synchronized access
  private val extensions: mutable.Map[ExtensionId[_], Extension] = mutable.Map.empty

  override val path: a.ActorPath = a.RootActorPath(a.Address("akka", name)) / "user"

  override val settings: Settings = new Settings(getClass.getClassLoader, ConfigFactory.empty, name)

  override def tell(msg: Nothing): Unit = throw new RuntimeException("must not send message to ActorSystemStub")

  override def isLocal: Boolean = true
  override def sendSystem(signal: akka.actor.typed.internal.SystemMessage): Unit =
    throw new RuntimeException("must not send SYSTEM message to ActorSystemStub")

  val deadLettersInbox = new DebugRef[Any](path.parent / "deadLetters", true)
  override def deadLetters[U]: akka.actor.typed.ActorRef[U] = deadLettersInbox

  val controlledExecutor = new ControlledExecutor
  implicit override def executionContext: scala.concurrent.ExecutionContextExecutor = controlledExecutor
  override def dispatchers: akka.actor.typed.Dispatchers = new Dispatchers {
    def lookup(selector: DispatcherSelector): ExecutionContextExecutor = controlledExecutor
    def shutdown(): Unit = ()
  }

  override def dynamicAccess: a.DynamicAccess = new a.ReflectiveDynamicAccess(getClass.getClassLoader)

  override def logConfiguration(): Unit = log.info(settings.toString)

  override def scheduler: a.Scheduler = throw new UnsupportedOperationException("no scheduler")

  private val terminationPromise = Promise[Terminated]
  override def terminate(): Future[akka.actor.typed.Terminated] = {
    terminationPromise.trySuccess(Terminated(this)(null))
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

  override def systemActorOf[U](behavior: Behavior[U], name: String, props: Props)(implicit timeout: Timeout): Future[ActorRef[U]] = {
    Future.failed(new UnsupportedOperationException("ActorSystemStub cannot create system actors"))
  }

  def registerExtension[T <: Extension](ext: ExtensionId[T], extImpl: T): Unit = extensions.synchronized {
    extensions += ext -> extImpl
  }

  override def registerExtension[T <: Extension](ext: ExtensionId[T]): T = extensions.synchronized {
    extensions.get(ext) match {
      case None ⇒
        val instance = ext.createExtension(this)
        extensions += ext -> instance
        instance
      case Some(existing) ⇒ existing.asInstanceOf[T]
    }
  }

  override def extension[T <: Extension](ext: ExtensionId[T]): T = extensions.synchronized {
    extensions.get(ext) match {
      case None           ⇒ throw new IllegalArgumentException(s"Trying to get non-registered extension [$ext]")
      case Some(existing) ⇒ existing.asInstanceOf[T]
    }
  }

  override def hasExtension(ext: ExtensionId[_ <: Extension]): Boolean = extensions.synchronized {
    extensions.contains(ext)
  }

  override def log: Logger = new StubbedLogger

}
