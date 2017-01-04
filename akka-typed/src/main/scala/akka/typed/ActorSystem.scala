/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import scala.concurrent.ExecutionContext
import akka.{ actor ⇒ a, event ⇒ e }
import java.util.concurrent.ThreadFactory

import akka.actor.setup.ActorSystemSetup
import com.typesafe.config.{ Config, ConfigFactory }

import scala.concurrent.{ ExecutionContextExecutor, Future }
import akka.typed.adapter.{ ActorSystemAdapter, PropsAdapter }
import akka.util.Timeout

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
  def settings: Settings

  /**
   * Log the configuration.
   */
  def logConfiguration(): Unit

  /**
   * A reference to this system’s logFilter, which filters usage of the [[#log]]
   * [[akka.event.LoggingAdapter]] such that only permissible messages are sent
   * via the [[#eventStream]]. The default implementation will just test that
   * the message is suitable for the current log level.
   */
  def logFilter: e.LoggingFilter

  /**
   * A [[akka.event.LoggingAdapter]] that can be used to emit log messages
   * without specifying a more detailed source. Typically it is desirable to
   * construct a dedicated LoggingAdapter within each Actor from that Actor’s
   * [[ActorRef]] in order to identify the log messages.
   */
  def log: e.LoggingAdapter

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

  /**
   * Ask the system guardian of this system to create an actor from the given
   * behavior and deployment and with the given name. The name does not need to
   * be unique since the guardian will prefix it with a running number when
   * creating the child actor. The timeout sets the timeout used for the [[AskPattern$]]
   * invocation when asking the guardian.
   *
   * The returned Future of [[ActorRef]] may be converted into an [[ActorRef]]
   * to which messages can immediately be sent by using the [[ActorRef$apply]]
   * method.
   */
  def systemActorOf[U](behavior: Behavior[U], name: String, deployment: DeploymentConfig = EmptyDeploymentConfig)(implicit timeout: Timeout): Future[ActorRef[U]]
}

object ActorSystem {
  import internal._

  /**
   * Create an ActorSystem implementation that is optimized for running
   * Akka Typed [[Behavior]] hierarchies—this system cannot run untyped
   * [[akka.actor.Actor]] instances.
   */
  def apply[T](name: String, guardianBehavior: Behavior[T],
               guardianDeployment: DeploymentConfig         = EmptyDeploymentConfig,
               config:             Option[Config]           = None,
               classLoader:        Option[ClassLoader]      = None,
               executionContext:   Option[ExecutionContext] = None): ActorSystem[T] = {
    Behavior.validateAsInitial(guardianBehavior)
    val cl = classLoader.getOrElse(akka.actor.ActorSystem.findClassLoader())
    val appConfig = config.getOrElse(ConfigFactory.load(cl))
    new ActorSystemImpl(name, appConfig, cl, executionContext, guardianBehavior, guardianDeployment)
  }

  /**
   * Create an ActorSystem based on the untyped [[akka.actor.ActorSystem]]
   * which runs Akka Typed [[Behavior]] on an emulation layer. In this
   * system typed and untyped actors can coexist.
   */
  def adapter[T](name: String, guardianBehavior: Behavior[T],
                 guardianDeployment:  DeploymentConfig         = EmptyDeploymentConfig,
                 config:              Option[Config]           = None,
                 classLoader:         Option[ClassLoader]      = None,
                 executionContext:    Option[ExecutionContext] = None,
                 actorSystemSettings: ActorSystemSetup         = ActorSystemSetup.empty): ActorSystem[T] = {
    Behavior.validateAsInitial(guardianBehavior)
    val cl = classLoader.getOrElse(akka.actor.ActorSystem.findClassLoader())
    val appConfig = config.getOrElse(ConfigFactory.load(cl))
    val untyped = new a.ActorSystemImpl(name, appConfig, cl, executionContext, Some(PropsAdapter(guardianBehavior, guardianDeployment)), actorSystemSettings)
    untyped.start()
    new ActorSystemAdapter(untyped)
  }

  /**
   * Wrap an untyped [[akka.actor.ActorSystem]] such that it can be used from
   * Akka Typed [[Behavior]].
   */
  def wrap(untyped: a.ActorSystem): ActorSystem[Nothing] = new ActorSystemAdapter(untyped.asInstanceOf[a.ActorSystemImpl])
}

/**
 * The configuration settings that were parsed from the config by an [[ActorSystem]].
 * This class is immutable.
 */
final class Settings(val config: Config, val untyped: a.ActorSystem.Settings, val name: String) {
  import collection.JavaConverters._

  def this(_cl: ClassLoader, _config: Config, name: String) = this({
    val config = _config.withFallback(ConfigFactory.defaultReference(_cl))
    config.checkValid(ConfigFactory.defaultReference(_cl), "akka")
    config
  }, new a.ActorSystem.Settings(_cl, _config, name), name)

  def this(untyped: a.ActorSystem.Settings) = this(untyped.config, untyped, untyped.name)

  private var foundSettings = List.empty[String]
  private def found(name: String, value: String): Unit = foundSettings ::= s"$name = $value"
  private def getS(name: String, path: String): String = {
    val value = config.getString(path)
    found(name, value)
    value
  }
  private def getSL(name: String, path: String): List[String] = {
    val value = config.getStringList(path)
    found(name, value.toString)
    value.asScala.toList
  }
  private def getI(name: String, path: String): Int = {
    val value = config.getInt(path)
    found(name, value.toString)
    value
  }

  val Loggers = getSL("Loggers", "akka.typed.loggers")
  val LoggingFilter = getS("LoggingFilter", "akka.typed.logging-filter")
  val DefaultMailboxCapacity = getI("DefaultMailboxCapacity", "akka.typed.mailbox-capacity")

  foundSettings = foundSettings.reverse

  override def toString: String = s"Settings($name,\n  ${foundSettings.mkString("\n  ")})"
}
