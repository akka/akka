/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.{ actor ⇒ a }
import java.util.concurrent.{ CompletionStage, ThreadFactory }

import akka.actor.setup.ActorSystemSetup
import com.typesafe.config.{ Config, ConfigFactory }
import scala.concurrent.{ ExecutionContextExecutor, Future, Promise }

import akka.actor.typed.internal.adapter._
import akka.util.Timeout
import akka.annotation.DoNotInherit
import akka.annotation.ApiMayChange
import akka.actor.BootstrapSetup
import akka.actor.typed.receptionist.Receptionist

/**
 * An ActorSystem is home to a hierarchy of Actors. It is created using
 * [[ActorSystem#apply]] from a [[Behavior]] object that describes the root
 * Actor of this hierarchy and which will create all other Actors beneath it.
 * A system also implements the [[ActorRef]] type, and sending a message to
 * the system directs that message to the root Actor.
 *
 * Not for user extension.
 */
@DoNotInherit
@ApiMayChange
abstract class ActorSystem[-T] extends ActorRef[T] with Extensions {
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
   * A [[akka.actor.typed.Logger]] that can be used to emit log messages
   * without specifying a more detailed source. Typically it is desirable to
   * use the dedicated `Logger` available from each Actor’s [[ActorContext]]
   * as that ties the log entries to the actor.
   */
  def log: Logger

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
   * Returns a CompletionStage which will be completed after the ActorSystem has been terminated
   * and termination hooks have been executed.
   */
  def getWhenTerminated: CompletionStage[Terminated]

  /**
   * The deadLetter address is a destination that will accept (and discard)
   * every message sent to it.
   */
  def deadLetters[U]: ActorRef[U]

  /**
   * Create a string representation of the actor hierarchy within this system
   * for debugging purposes.
   *
   * The format of the string is subject to change, i.e. no stable “API”.
   */
  def printTree: String

  /**
   * Create an actor in the "/system" namespace. This actor will be shut down
   * during system.terminate only after all user actors have terminated.
   *
   * The returned Future of [[ActorRef]] may be converted into an [[ActorRef]]
   * to which messages can immediately be sent by using the `ActorRef.apply`
   * method.
   */
  def systemActorOf[U](behavior: Behavior[U], name: String, props: Props = Props.empty)(implicit timeout: Timeout): Future[ActorRef[U]]

  /**
   * Return a reference to this system’s [[akka.actor.typed.receptionist.Receptionist]].
   */
  def receptionist: ActorRef[Receptionist.Command] =
    Receptionist(this).ref
}

object ActorSystem {

  /**
   * Scala API: Create an ActorSystem
   */
  def apply[T](
    guardianBehavior: Behavior[T],
    name:             String
  ): ActorSystem[T] = createInternal(name, guardianBehavior, Props.empty, ActorSystemSetup.create(BootstrapSetup()))

  /**
   * Scala API: Create an ActorSystem
   */
  def apply[T](
    guardianBehavior: Behavior[T],
    name:             String,
    config:           Config
  ): ActorSystem[T] =
    createInternal(name, guardianBehavior, Props.empty, ActorSystemSetup.create(BootstrapSetup(config)))

  /**
   * Scala API: Creates a new actor system with the specified name and settings
   * The core actor system settings are defined in [[BootstrapSetup]]
   */
  def apply[T](guardianBehavior: Behavior[T], name: String, setup: ActorSystemSetup, guardianProps: Props = Props.empty): ActorSystem[T] = {
    createInternal(name, guardianBehavior, guardianProps, setup)
  }

  /**
   * Scala API: Shortcut for creating an actor system with custom bootstrap settings.
   * Same behavior as calling `ActorSystem(name, ActorSystemSetup(bootstrapSetup))`
   */
  def apply[T](guardianBehavior: Behavior[T], name: String, bootstrapSetup: BootstrapSetup): ActorSystem[T] =
    apply(guardianBehavior, name, ActorSystemSetup.create(bootstrapSetup))

  /**
   * Java API: Create an ActorSystem
   */
  def create[T](guardianBehavior: Behavior[T], name: String): ActorSystem[T] =
    apply(guardianBehavior, name)

  /**
   * Java API: Create an ActorSystem
   */
  def create[T](guardianBehavior: Behavior[T], name: String, config: Config): ActorSystem[T] =
    apply(guardianBehavior, name, config)

  /**
   * Java API: Creates a new actor system with the specified name and settings
   * The core actor system settings are defined in [[BootstrapSetup]]
   */
  def create[T](guardianBehavior: Behavior[T], name: String, setups: ActorSystemSetup): ActorSystem[T] =
    apply(guardianBehavior, name, setups)

  /**
   * Java API: Shortcut for creating an actor system with custom bootstrap settings.
   * Same behavior as calling `ActorSystem.create(name, ActorSystemSetup.create(bootstrapSettings))`
   */
  def create[T](guardianBehavior: Behavior[T], name: String, bootstrapSetup: BootstrapSetup): ActorSystem[T] =
    create(guardianBehavior, name, ActorSystemSetup.create(bootstrapSetup))

  /**
   * Create an ActorSystem based on the untyped [[akka.actor.ActorSystem]]
   * which runs Akka Typed [[Behavior]] on an emulation layer. In this
   * system typed and untyped actors can coexist.
   */
  private def createInternal[T](name: String, guardianBehavior: Behavior[T],
                                guardianProps: Props,
                                setup:         ActorSystemSetup): ActorSystem[T] = {

    Behavior.validateAsInitial(guardianBehavior)
    require(Behavior.isAlive(guardianBehavior))

    val bootstrapSettings = setup.get[BootstrapSetup]
    val cl = bootstrapSettings.flatMap(_.classLoader).getOrElse(akka.actor.ActorSystem.findClassLoader())
    val appConfig = bootstrapSettings.flatMap(_.config).getOrElse(ConfigFactory.load(cl))
    val executionContext = bootstrapSettings.flatMap(_.defaultExecutionContext)

    val promise = Promise[Terminated]()
    val wrappedGuardian = WhenTerminated(guardianBehavior, promise)
    val whenTerminatedSetup = WhenTerminatedSetup(promise.future)

    val untyped = new a.ActorSystemImpl(name, appConfig, cl, executionContext,
      Some(PropsAdapter(() ⇒ wrappedGuardian, guardianProps, isGuardian = true)), setup and whenTerminatedSetup)
    untyped.start()

    untyped.guardian ! GuardianActorAdapter.Start
    ActorSystemAdapter.AdapterExtension(untyped).adapter
  }

  /**
   * Wrap an untyped [[akka.actor.ActorSystem]] such that it can be used from
   * Akka Typed [[Behavior]].
   */
  def wrap(untyped: a.ActorSystem): ActorSystem[Nothing] = ActorSystemAdapter.AdapterExtension(untyped.asInstanceOf[a.ActorSystemImpl]).adapter
}

/**
 * The configuration settings that were parsed from the config by an [[ActorSystem]].
 * This class is immutable.
 */
final class Settings(val config: Config, val untyped: a.ActorSystem.Settings, val name: String) {
  def this(_cl: ClassLoader, _config: Config, name: String) = this({
    val config = _config.withFallback(ConfigFactory.defaultReference(_cl))
    config.checkValid(ConfigFactory.defaultReference(_cl), "akka")
    config
  }, new a.ActorSystem.Settings(_cl, _config, name), name)

  def this(untyped: a.ActorSystem.Settings) = this(untyped.config, untyped, untyped.name)

  private var foundSettings = List.empty[String]

  foundSettings = foundSettings.reverse

  def setup: ActorSystemSetup = untyped.setup

  override def toString: String = s"Settings($name,\n  ${foundSettings.mkString("\n  ")})"
}
