/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import akka.event.{ LoggingFilter, LoggingAdapter, EventStream }
import scala.concurrent.ExecutionContext
import akka.{ actor ⇒ a }
import java.util.concurrent.ThreadFactory
import com.typesafe.config.{ Config, ConfigFactory }
import scala.concurrent.{ ExecutionContextExecutor, Future }
import akka.typed.adapter.{ PropsAdapter, ActorSystemAdapter }

/**
 * An ActorSystem is home to a hierarchy of Actors. It is created using
 * [[ActorSystem$]] `apply` from a [[Props]] object that describes the root
 * Actor of this hierarchy and which will create all other Actors beneath it.
 * A system also implements the [[ActorRef]] type, and sending a message to
 * the system directs that message to the root Actor.
 */
trait ActorSystem[-T] extends ActorRef[T] { this: internal.ActorRefImpl[T] ⇒

  /**
   * The name of this actor system, used to distinguish multiple ones within
   * the same JVM & class loader.
   */
  def name: String

  /**
   * The core settings extracted from the supplied configuration.
   */
  def settings: akka.actor.ActorSystem.Settings

  /**
   * Log the configuration.
   */
  def logConfiguration(): Unit

  def logFilter: LoggingFilter
  def log: LoggingAdapter

  /**
   * Start-up time in milliseconds since the epoch.
   */
  def startTime: Long

  /**
   * Up-time of this actor system in seconds.
   */
  def uptime: Long

  /**
   * A ThreadFactory that can be used if the transport needs to create any Threads
   */
  def threadFactory: ThreadFactory

  /**
   * ClassLoader wrapper which is used for reflective accesses internally. This is set
   * to use the context class loader, if one is set, or the class loader which
   * loaded the ActorSystem implementation. The context class loader is also
   * set on all threads created by the ActorSystem, if one was set during
   * creation.
   */
  def dynamicAccess: a.DynamicAccess

  /**
   * A generic scheduler that can initiate the execution of tasks after some delay.
   * It is recommended to use the ActorContext’s scheduling capabilities for sending
   * messages to actors instead of registering a Runnable for execution using this facility.
   */
  def scheduler: a.Scheduler

  /**
   * Main event bus of this actor system, used for example for logging.
   */
  def eventStream: EventStream

  /**
   * Facilities for lookup up thread-pools from configuration.
   */
  def dispatchers: Dispatchers

  /**
   * The default thread pool of this ActorSystem, configured with settings in `akka.actor.default-dispatcher`.
   */
  implicit def executionContext: ExecutionContextExecutor

  /**
   * Terminates this actor system. This will stop the guardian actor, which in turn
   * will recursively stop all its child actors, then the system guardian
   * (below which the logging actors reside).
   */
  def terminate(): Future[Terminated]

  /**
   * Returns a Future which will be completed after the ActorSystem has been terminated
   * and termination hooks have been executed.
   */
  def whenTerminated: Future[Terminated]

  /**
   * The deadLetter address is a destination that will accept (and discard)
   * every message sent to it.
   */
  def deadLetters[U]: ActorRef[U]

  /**
   * Create a string representation of the actor hierarchy within this system.
   *
   * The format of the string is subject to change, i.e. no stable “API”.
   */
  def printTree: String
}

object ActorSystem {
  import internal._

  /**
   * Create an ActorSystem implementation that is optimized for running
   * Akka Typed [[Behavior]] hierarchies—this system cannot run untyped
   * [[akka.actor.Actor]] instances.
   */
  def apply[T](name: String, guardianProps: Props[T],
               config:           Option[Config]           = None,
               classLoader:      Option[ClassLoader]      = None,
               executionContext: Option[ExecutionContext] = None): ActorSystem[T] = {
    val cl = classLoader.getOrElse(akka.actor.ActorSystem.findClassLoader())
    val appConfig = config.getOrElse(ConfigFactory.load(cl))
    new ActorSystemImpl(name, appConfig, cl, executionContext, guardianProps)
  }

  /**
   * Create an ActorSystem based on the untyped [[akka.actor.ActorSystem]]
   * which runs Akka Typed [[Behavior]] on an emulation layer. In this
   * system typed and untyped actors can coexist.
   */
  def adapter[T](name: String, guardianProps: Props[T],
                 config:           Option[Config]           = None,
                 classLoader:      Option[ClassLoader]      = None,
                 executionContext: Option[ExecutionContext] = None): ActorSystem[T] = {
    val cl = classLoader.getOrElse(akka.actor.ActorSystem.findClassLoader())
    val appConfig = config.getOrElse(ConfigFactory.load(cl))
    val untyped = new a.ActorSystemImpl(name, appConfig, cl, executionContext, Some(PropsAdapter(guardianProps)))
    untyped.start()
    new ActorSystemAdapter(untyped)
  }

  /**
   * Wrap an untyped [[akka.actor.ActorSystem]] such that it can be used from
   * Akka Typed [[Behavior]].
   */
  def wrap(untyped: a.ActorSystem): ActorSystem[Nothing] = new ActorSystemAdapter(untyped.asInstanceOf[a.ActorSystemImpl])
}
