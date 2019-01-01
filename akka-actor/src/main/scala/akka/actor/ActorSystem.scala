/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import java.io.Closeable
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicReference

import com.typesafe.config.{ Config, ConfigFactory }
import akka.event._
import akka.dispatch._
import akka.japi.Util.immutableSeq
import akka.actor.dungeon.ChildrenContainer
import akka.util._
import akka.util.Helpers.toRootLowerCase

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, ExecutionContextExecutor, Future, Promise }
import scala.util.{ Failure, Success, Try }
import scala.util.control.{ ControlThrowable, NonFatal }
import java.util.Optional

import akka.actor.setup.{ ActorSystemSetup, Setup }
import akka.annotation.InternalApi

import scala.compat.java8.FutureConverters
import scala.compat.java8.OptionConverters._

object BootstrapSetup {

  /**
   * Scala API: Construct a bootstrap settings with default values. Note that passing that to the actor system is the
   * same as not passing any [[BootstrapSetup]] at all. You can use the returned instance to derive
   * one that has other values than defaults using the various `with`-methods.
   */
  def apply(): BootstrapSetup = {
    new BootstrapSetup()
  }

  /**
   * Scala API: Create bootstrap settings needed for starting the actor system
   *
   * @see [[BootstrapSetup]] for description of the properties
   */
  def apply(classLoader: Option[ClassLoader], config: Option[Config], defaultExecutionContext: Option[ExecutionContext]): BootstrapSetup =
    new BootstrapSetup(classLoader, config, defaultExecutionContext)

  /**
   * Scala API: Short for using custom config but keeping default classloader and default execution context
   */
  def apply(config: Config): BootstrapSetup = apply(None, Some(config), None)

  /**
   * Java API: Create bootstrap settings needed for starting the actor system
   *
   * @see [[BootstrapSetup]] for description of the properties
   */
  def create(classLoader: Optional[ClassLoader], config: Optional[Config], defaultExecutionContext: Optional[ExecutionContext]): BootstrapSetup =
    apply(classLoader.asScala, config.asScala, defaultExecutionContext.asScala)

  /**
   * Java  API: Short for using custom config but keeping default classloader and default execution context
   */
  def create(config: Config): BootstrapSetup = apply(config)

  /**
   * Java API: Construct a bootstrap settings with default values. Note that passing that to the actor system is the
   * same as not passing any [[BootstrapSetup]] at all. You can use the returned instance to derive
   * one that has other values than defaults using the various `with`-methods.
   */
  def create(): BootstrapSetup = {
    new BootstrapSetup()
  }

}

abstract class ProviderSelection private (private[akka] val identifier: String)
object ProviderSelection {
  case object Local extends ProviderSelection("local")
  case object Remote extends ProviderSelection("remote")
  case object Cluster extends ProviderSelection("cluster")

  /**
   * JAVA API
   */
  def local(): ProviderSelection = Local

  /**
   * JAVA API
   */
  def remote(): ProviderSelection = Remote

  /**
   * JAVA API
   */
  def cluster(): ProviderSelection = Cluster

}

/**
 * Core bootstrap settings of the actor system, create using one of the factories in [[BootstrapSetup]],
 * constructor is *Internal API*.
 *
 * @param classLoader If no ClassLoader is given, it obtains the current ClassLoader by first inspecting the current
 *                    threads' getContextClassLoader, then tries to walk the stack to find the callers class loader, then
 *                    falls back to the ClassLoader associated with the ActorSystem class.
 * @param config Configuration to use for the actor system. If no Config is given, the default reference config will be obtained from the ClassLoader.
 * @param defaultExecutionContext If defined the ExecutionContext will be used as the default executor inside this ActorSystem.
 *                                If no ExecutionContext is given, the system will fallback to the executor configured under
 *                                "akka.actor.default-dispatcher.default-executor.fallback".
 * @param actorRefProvider Overrides the `akka.actor.provider` setting in config, can be `local` (default), `remote` or
 *                         `cluster`. It can also be a fully qualified class name of a provider.
 */
final class BootstrapSetup private (
  val classLoader:             Option[ClassLoader]       = None,
  val config:                  Option[Config]            = None,
  val defaultExecutionContext: Option[ExecutionContext]  = None,
  val actorRefProvider:        Option[ProviderSelection] = None) extends Setup {

  def withClassloader(classLoader: ClassLoader): BootstrapSetup =
    new BootstrapSetup(Some(classLoader), config, defaultExecutionContext, actorRefProvider)

  def withConfig(config: Config): BootstrapSetup =
    new BootstrapSetup(classLoader, Some(config), defaultExecutionContext, actorRefProvider)

  def withDefaultExecutionContext(executionContext: ExecutionContext): BootstrapSetup =
    new BootstrapSetup(classLoader, config, Some(executionContext), actorRefProvider)

  def withActorRefProvider(name: ProviderSelection): BootstrapSetup =
    new BootstrapSetup(classLoader, config, defaultExecutionContext, Some(name))

}

object ActorSystem {

  val Version: String = akka.Version.current // generated file

  val EnvHome: Option[String] = System.getenv("AKKA_HOME") match {
    case null | "" | "." ⇒ None
    case value           ⇒ Some(value)
  }

  val SystemHome: Option[String] = System.getProperty("akka.home") match {
    case null | "" ⇒ None
    case value     ⇒ Some(value)
  }

  val GlobalHome: Option[String] = SystemHome orElse EnvHome

  /**
   * Creates a new ActorSystem with the name "default",
   * obtains the current ClassLoader by first inspecting the current threads' getContextClassLoader,
   * then tries to walk the stack to find the callers class loader, then falls back to the ClassLoader
   * associated with the ActorSystem class.
   * Then it loads the default reference configuration using the ClassLoader.
   */
  def create(): ActorSystem = apply()

  /**
   * Creates a new ActorSystem with the specified name,
   * obtains the current ClassLoader by first inspecting the current threads' getContextClassLoader,
   * then tries to walk the stack to find the callers class loader, then falls back to the ClassLoader
   * associated with the ActorSystem class.
   * Then it loads the default reference configuration using the ClassLoader.
   */
  def create(name: String): ActorSystem = apply(name)

  /**
   * Java API: Creates a new actor system with the specified name and settings
   * The core actor system settings are defined in [[BootstrapSetup]]
   */
  def create(name: String, setups: ActorSystemSetup): ActorSystem = apply(name, setups)

  /**
   * Java API: Shortcut for creating an actor system with custom bootstrap settings.
   * Same behavior as calling `ActorSystem.create(name, ActorSystemSetup.create(bootstrapSettings))`
   */
  def create(name: String, bootstrapSetup: BootstrapSetup): ActorSystem =
    create(name, ActorSystemSetup.create(bootstrapSetup))

  /**
   * Creates a new ActorSystem with the specified name, and the specified Config, then
   * obtains the current ClassLoader by first inspecting the current threads' getContextClassLoader,
   * then tries to walk the stack to find the callers class loader, then falls back to the ClassLoader
   * associated with the ActorSystem class.
   *
   * @see <a href="https://lightbend.github.io/config/v1.3.1/" target="_blank">The Typesafe Config Library API Documentation</a>
   */
  def create(name: String, config: Config): ActorSystem = apply(name, config)

  /**
   * Creates a new ActorSystem with the specified name, the specified Config, and specified ClassLoader
   *
   * @see <a href="https://lightbend.github.io/config/v1.3.1/" target="_blank">The Typesafe Config Library API Documentation</a>
   */
  def create(name: String, config: Config, classLoader: ClassLoader): ActorSystem = apply(name, config, classLoader)

  /**
   * Creates a new ActorSystem with the specified name, the specified Config, the specified ClassLoader,
   * and the specified ExecutionContext. The ExecutionContext will be used as the default executor inside this ActorSystem.
   * If `null` is passed in for the Config, ClassLoader and/or ExecutionContext parameters, the respective default value
   * will be used. If no Config is given, the default reference config will be obtained from the ClassLoader.
   * If no ClassLoader is given, it obtains the current ClassLoader by first inspecting the current
   * threads' getContextClassLoader, then tries to walk the stack to find the callers class loader, then
   * falls back to the ClassLoader associated with the ActorSystem class. If no ExecutionContext is given, the
   * system will fallback to the executor configured under "akka.actor.default-dispatcher.default-executor.fallback".
   * Note that the given ExecutionContext will be used by all dispatchers that have been configured with
   * executor = "default-executor", including those that have not defined the executor setting and thereby fallback
   * to the default of "default-dispatcher.executor".
   *
   * @see <a href="https://lightbend.github.io/config/v1.3.1/" target="_blank">The Typesafe Config Library API Documentation</a>
   */
  def create(name: String, config: Config, classLoader: ClassLoader, defaultExecutionContext: ExecutionContext): ActorSystem = apply(name, Option(config), Option(classLoader), Option(defaultExecutionContext))

  /**
   * Creates a new ActorSystem with the name "default",
   * obtains the current ClassLoader by first inspecting the current threads' getContextClassLoader,
   * then tries to walk the stack to find the callers class loader, then falls back to the ClassLoader
   * associated with the ActorSystem class.
   * Then it loads the default reference configuration using the ClassLoader.
   */
  def apply(): ActorSystem = apply("default")

  /**
   * Creates a new ActorSystem with the specified name,
   * obtains the current ClassLoader by first inspecting the current threads' getContextClassLoader,
   * then tries to walk the stack to find the callers class loader, then falls back to the ClassLoader
   * associated with the ActorSystem class.
   * Then it loads the default reference configuration using the ClassLoader.
   */
  def apply(name: String): ActorSystem = apply(name, None, None, None)

  /**
   * Scala API: Creates a new actor system with the specified name and settings
   * The core actor system settings are defined in [[BootstrapSetup]]
   */
  def apply(name: String, setup: ActorSystemSetup): ActorSystem = {
    val bootstrapSettings = setup.get[BootstrapSetup]
    val cl = bootstrapSettings.flatMap(_.classLoader).getOrElse(findClassLoader())
    val appConfig = bootstrapSettings.flatMap(_.config).getOrElse(ConfigFactory.load(cl))
    val defaultEC = bootstrapSettings.flatMap(_.defaultExecutionContext)

    new ActorSystemImpl(name, appConfig, cl, defaultEC, None, setup).start()
  }

  /**
   * Scala API: Shortcut for creating an actor system with custom bootstrap settings.
   * Same behavior as calling `ActorSystem(name, ActorSystemSetup(bootstrapSetup))`
   */
  def apply(name: String, bootstrapSetup: BootstrapSetup): ActorSystem =
    create(name, ActorSystemSetup.create(bootstrapSetup))

  /**
   * Creates a new ActorSystem with the specified name, and the specified Config, then
   * obtains the current ClassLoader by first inspecting the current threads' getContextClassLoader,
   * then tries to walk the stack to find the callers class loader, then falls back to the ClassLoader
   * associated with the ActorSystem class.
   *
   * @see <a href="https://lightbend.github.io/config/v1.3.1/" target="_blank">The Typesafe Config Library API Documentation</a>
   */
  def apply(name: String, config: Config): ActorSystem = apply(name, Option(config), None, None)

  /**
   * Creates a new ActorSystem with the specified name, the specified Config, and specified ClassLoader
   *
   * @see <a href="https://lightbend.github.io/config/v1.3.1/" target="_blank">The Typesafe Config Library API Documentation</a>
   */
  def apply(name: String, config: Config, classLoader: ClassLoader): ActorSystem = apply(name, Option(config), Option(classLoader), None)

  /**
   * Creates a new ActorSystem with the specified name,
   * the specified ClassLoader if given, otherwise obtains the current ClassLoader by first inspecting the current
   * threads' getContextClassLoader, then tries to walk the stack to find the callers class loader, then
   * falls back to the ClassLoader associated with the ActorSystem class.
   * If an ExecutionContext is given, it will be used as the default executor inside this ActorSystem.
   * If no ExecutionContext is given, the system will fallback to the executor configured under "akka.actor.default-dispatcher.default-executor.fallback".
   * The system will use the passed in config, or falls back to the default reference configuration using the ClassLoader.
   *
   * @see <a href="https://lightbend.github.io/config/v1.3.1/" target="_blank">The Typesafe Config Library API Documentation</a>
   */
  def apply(
    name:                    String,
    config:                  Option[Config]           = None,
    classLoader:             Option[ClassLoader]      = None,
    defaultExecutionContext: Option[ExecutionContext] = None): ActorSystem =
    apply(name, ActorSystemSetup(BootstrapSetup(classLoader, config, defaultExecutionContext)))

  /**
   * Settings are the overall ActorSystem Settings which also provides a convenient access to the Config object.
   *
   * For more detailed information about the different possible configuration options, look in the Akka Documentation under "Configuration"
   *
   * @see <a href="https://lightbend.github.io/config/v1.3.1/" target="_blank">The Typesafe Config Library API Documentation</a>
   */
  class Settings(classLoader: ClassLoader, cfg: Config, final val name: String, val setup: ActorSystemSetup) {

    def this(classLoader: ClassLoader, cfg: Config, name: String) = this(classLoader, cfg, name, ActorSystemSetup())

    /**
     * The backing Config of this ActorSystem's Settings
     *
     * @see <a href="https://lightbend.github.io/config/v1.3.1/" target="_blank">The Typesafe Config Library API Documentation</a>
     */
    final val config: Config = {
      val config = cfg.withFallback(ConfigFactory.defaultReference(classLoader))
      config.checkValid(ConfigFactory.defaultReference(classLoader), "akka")
      config
    }

    import akka.util.Helpers.ConfigOps
    import config._

    final val ConfigVersion: String = getString("akka.version")
    final val ProviderClass: String =
      setup.get[BootstrapSetup]
        .flatMap(_.actorRefProvider).map(_.identifier)
        .getOrElse(getString("akka.actor.provider")) match {
          case "local"   ⇒ classOf[LocalActorRefProvider].getName
          // these two cannot be referenced by class as they may not be on the classpath
          case "remote"  ⇒ "akka.remote.RemoteActorRefProvider"
          case "cluster" ⇒ "akka.cluster.ClusterActorRefProvider"
          case fqcn      ⇒ fqcn
        }

    final val SupervisorStrategyClass: String = getString("akka.actor.guardian-supervisor-strategy")
    final val CreationTimeout: Timeout = Timeout(config.getMillisDuration("akka.actor.creation-timeout"))
    final val UnstartedPushTimeout: Timeout = Timeout(config.getMillisDuration("akka.actor.unstarted-push-timeout"))

    final val AllowJavaSerialization: Boolean = getBoolean("akka.actor.allow-java-serialization")
    final val EnableAdditionalSerializationBindings: Boolean =
      !AllowJavaSerialization || getBoolean("akka.actor.enable-additional-serialization-bindings")
    final val SerializeAllMessages: Boolean = getBoolean("akka.actor.serialize-messages")
    final val SerializeAllCreators: Boolean = getBoolean("akka.actor.serialize-creators")

    final val LogLevel: String = getString("akka.loglevel")
    final val StdoutLogLevel: String = getString("akka.stdout-loglevel")
    final val Loggers: immutable.Seq[String] = immutableSeq(getStringList("akka.loggers"))
    final val LoggersDispatcher: String = getString("akka.loggers-dispatcher")
    final val LoggingFilter: String = getString("akka.logging-filter")
    final val LoggerStartTimeout: Timeout = Timeout(config.getMillisDuration("akka.logger-startup-timeout"))
    final val LogConfigOnStart: Boolean = config.getBoolean("akka.log-config-on-start")
    final val LogDeadLetters: Int = toRootLowerCase(config.getString("akka.log-dead-letters")) match {
      case "off" | "false" ⇒ 0
      case "on" | "true"   ⇒ Int.MaxValue
      case _               ⇒ config.getInt("akka.log-dead-letters")
    }
    final val LogDeadLettersDuringShutdown: Boolean = config.getBoolean("akka.log-dead-letters-during-shutdown")

    final val AddLoggingReceive: Boolean = getBoolean("akka.actor.debug.receive")
    final val DebugAutoReceive: Boolean = getBoolean("akka.actor.debug.autoreceive")
    final val DebugLifecycle: Boolean = getBoolean("akka.actor.debug.lifecycle")
    final val FsmDebugEvent: Boolean = getBoolean("akka.actor.debug.fsm")
    final val DebugEventStream: Boolean = getBoolean("akka.actor.debug.event-stream")
    final val DebugUnhandledMessage: Boolean = getBoolean("akka.actor.debug.unhandled")
    final val DebugRouterMisconfiguration: Boolean = getBoolean("akka.actor.debug.router-misconfiguration")

    final val Home: Option[String] = config.getString("akka.home") match {
      case "" ⇒ None
      case x  ⇒ Some(x)
    }

    final val SchedulerClass: String = getString("akka.scheduler.implementation")
    final val Daemonicity: Boolean = getBoolean("akka.daemonic")
    final val JvmExitOnFatalError: Boolean = getBoolean("akka.jvm-exit-on-fatal-error")
    final val JvmShutdownHooks: Boolean = getBoolean("akka.jvm-shutdown-hooks")

    final val DefaultVirtualNodesFactor: Int = getInt("akka.actor.deployment.default.virtual-nodes-factor")

    if (ConfigVersion != Version)
      throw new akka.ConfigurationException("Akka JAR version [" + Version + "] does not match the provided config version [" + ConfigVersion + "]")

    /**
     * Returns the String representation of the Config that this Settings is backed by
     */
    override def toString: String = config.root.render

  }

  private[akka] def findClassLoader(): ClassLoader = Reflect.findClassLoader()
}

/**
 * An actor system is a hierarchical group of actors which share common
 * configuration, e.g. dispatchers, deployments, remote capabilities and
 * addresses. It is also the entry point for creating or looking up actors.
 *
 * There are several possibilities for creating actors (see [[akka.actor.Props]]
 * for details on `props`):
 *
 * {{{
 * // Java or Scala
 * system.actorOf(props, "name")
 * system.actorOf(props)
 *
 * // Scala
 * system.actorOf(Props[MyActor], "name")
 * system.actorOf(Props(classOf[MyActor], arg1, arg2), "name")
 *
 * // Java
 * system.actorOf(Props.create(MyActor.class), "name");
 * system.actorOf(Props.create(MyActor.class, arg1, arg2), "name");
 * }}}
 *
 * Where no name is given explicitly, one will be automatically generated.
 *
 * <b><i>Important Notice:</i></b>
 *
 * This class is not meant to be extended by user code. If you want to
 * actually roll your own Akka, it will probably be better to look into
 * extending [[akka.actor.ExtendedActorSystem]] instead, but beware that you
 * are completely on your own in that case!
 */
abstract class ActorSystem extends ActorRefFactory {
  import ActorSystem._

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
   * Construct a path below the application guardian to be used with [[ActorSystem#actorSelection]].
   */
  def /(name: String): ActorPath

  /**
   * Java API: Create a new child actor path.
   */
  def child(child: String): ActorPath = /(child)

  /**
   * Construct a path below the application guardian to be used with [[ActorSystem#actorSelection]].
   */
  def /(name: Iterable[String]): ActorPath

  /**
   * Java API: Recursively create a descendant’s path by appending all child names.
   */
  def descendant(names: java.lang.Iterable[String]): ActorPath = /(immutableSeq(names))

  /**
   * Start-up time in milliseconds since the epoch.
   */
  val startTime: Long = System.currentTimeMillis

  /**
   * Up-time of this actor system in seconds.
   */
  def uptime: Long = (System.currentTimeMillis - startTime) / 1000

  /**
   * Main event bus of this actor system, used for example for logging.
   */
  def eventStream: EventStream

  /**
   * Java API: Main event bus of this actor system, used for example for logging.
   */
  def getEventStream: EventStream = eventStream

  /**
   * Convenient logging adapter for logging to the [[ActorSystem#eventStream]].
   */
  def log: LoggingAdapter

  /**
   * Actor reference where messages are re-routed to which were addressed to
   * stopped or non-existing actors. Delivery to this actor is done on a best
   * effort basis and hence not strictly guaranteed.
   */
  def deadLetters: ActorRef
  //#scheduler
  /**
   * Light-weight scheduler for running asynchronous tasks after some deadline
   * in the future. Not terribly precise but cheap.
   */
  def scheduler: Scheduler
  //#scheduler

  /**
   * Java API: Light-weight scheduler for running asynchronous tasks after some deadline
   * in the future. Not terribly precise but cheap.
   */
  def getScheduler: Scheduler = scheduler

  /**
   * Helper object for looking up configured dispatchers.
   */
  def dispatchers: Dispatchers

  /**
   * Default dispatcher as configured. This dispatcher is used for all actors
   * in the actor system which do not have a different dispatcher configured
   * explicitly.
   * Importing this member will place the default MessageDispatcher in scope.
   */
  implicit def dispatcher: ExecutionContextExecutor

  /**
   * Java API: Default dispatcher as configured. This dispatcher is used for all actors
   * in the actor system which do not have a different dispatcher configured
   * explicitly.
   * Importing this member will place the default MessageDispatcher in scope.
   */
  def getDispatcher: ExecutionContextExecutor = dispatcher

  /**
   * Helper object for looking up configured mailbox types.
   */
  def mailboxes: Mailboxes

  /**
   * Register a block of code (callback) to run after [[ActorSystem.terminate()]] has been issued and
   * all actors in this actor system have been stopped.
   * Multiple code blocks may be registered by calling this method multiple times.
   * The callbacks will be run sequentially in reverse order of registration, i.e.
   * last registration is run first.
   * Note that ActorSystem will not terminate until all the registered callbacks are finished.
   *
   * Throws a RejectedExecutionException if the System has already been terminated or if termination has been initiated.
   *
   * Scala API
   */
  def registerOnTermination[T](code: ⇒ T): Unit

  /**
   * Java API: Register a block of code (callback) to run after [[ActorSystem.terminate()]] has been issued and
   * all actors in this actor system have been stopped.
   * Multiple code blocks may be registered by calling this method multiple times.
   * The callbacks will be run sequentially in reverse order of registration, i.e.
   * last registration is run first.
   * Note that ActorSystem will not terminate until all the registered callbacks are finished.
   *
   * Throws a RejectedExecutionException if the System has already been terminated or if termination has been initiated.
   */
  def registerOnTermination(code: Runnable): Unit

  /**
   * Terminates this actor system. This will stop the guardian actor, which in turn
   * will recursively stop all its child actors, the system guardian
   * (below which the logging actors reside) and then execute all registered
   * termination handlers (see [[ActorSystem#registerOnTermination]]).
   * Be careful to not schedule any operations on completion of the returned future
   * using the `dispatcher` of this actor system as it will have been shut down before the
   * future completes.
   */
  def terminate(): Future[Terminated]

  /**
   * Returns a Future which will be completed after the ActorSystem has been terminated
   * and termination hooks have been executed. If you registered any callback with
   * [[ActorSystem#registerOnTermination]], the returned Future from this method will not complete
   * until all the registered callbacks are finished. Be careful to not schedule any operations
   * on the `dispatcher` of this actor system as it will have been shut down before this
   * future completes.
   */
  def whenTerminated: Future[Terminated]

  /**
   * Returns a CompletionStage which will be completed after the ActorSystem has been terminated
   * and termination hooks have been executed. If you registered any callback with
   * [[ActorSystem#registerOnTermination]], the returned CompletionStage from this method will not complete
   * until all the registered callbacks are finished. Be careful to not schedule any operations
   * on the `dispatcher` of this actor system as it will have been shut down before this
   * future completes.
   */
  def getWhenTerminated: CompletionStage[Terminated]

  /**
   * Registers the provided extension and creates its payload, if this extension isn't already registered
   * This method has putIfAbsent-semantics, this method can potentially block, waiting for the initialization
   * of the payload, if is in the process of registration from another Thread of execution
   */
  def registerExtension[T <: Extension](ext: ExtensionId[T]): T

  /**
   * Returns the payload that is associated with the provided extension
   * throws an IllegalStateException if it is not registered.
   * This method can potentially block, waiting for the initialization
   * of the payload, if is in the process of registration from another Thread of execution
   */
  def extension[T <: Extension](ext: ExtensionId[T]): T

  /**
   * Returns whether the specified extension is already registered, this method can potentially block, waiting for the initialization
   * of the payload, if is in the process of registration from another Thread of execution
   */
  def hasExtension(ext: ExtensionId[_ <: Extension]): Boolean
}

/**
 * More powerful interface to the actor system’s implementation which is presented to extensions (see [[akka.actor.Extension]]).
 *
 * <b><i>Important Notice:</i></b>
 *
 * This class is not meant to be extended by user code. If you want to
 * actually roll your own Akka, beware that you are completely on your own in
 * that case!
 */
abstract class ExtendedActorSystem extends ActorSystem {

  /**
   * The ActorRefProvider is the only entity which creates all actor references within this actor system.
   */
  def provider: ActorRefProvider

  /**
   * The top-level supervisor of all actors created using system.actorOf(...).
   */
  def guardian: InternalActorRef

  /**
   * The top-level supervisor of all system-internal services like logging.
   */
  def systemGuardian: InternalActorRef

  /**
   * Create an actor in the "/system" namespace. This actor will be shut down
   * during system.terminate only after all user actors have terminated.
   */
  def systemActorOf(props: Props, name: String): ActorRef

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
  def dynamicAccess: DynamicAccess

  /**
   * Filter of log events that is used by the LoggingAdapter before
   * publishing log events to the eventStream
   */
  def logFilter: LoggingFilter

  /**
   * For debugging: traverse actor hierarchy and make string representation.
   * Careful, this may OOM on large actor systems, and it is only meant for
   * helping debugging in case something already went terminally wrong.
   */
  private[akka] def printTree: String

}

/**
 * Internal API
 */
@InternalApi
private[akka] class ActorSystemImpl(
  val name:                String,
  applicationConfig:       Config,
  classLoader:             ClassLoader,
  defaultExecutionContext: Option[ExecutionContext],
  val guardianProps:       Option[Props],
  setup:                   ActorSystemSetup) extends ExtendedActorSystem {

  if (!name.matches("""^[a-zA-Z0-9][a-zA-Z0-9-_]*$"""))
    throw new IllegalArgumentException(
      "invalid ActorSystem name [" + name +
        "], must contain only word characters (i.e. [a-zA-Z0-9] plus non-leading '-' or '_')")

  import ActorSystem._

  @volatile private var logDeadLetterListener: Option[ActorRef] = None
  final val settings: Settings = new Settings(classLoader, applicationConfig, name, setup)

  protected def uncaughtExceptionHandler: Thread.UncaughtExceptionHandler =
    new Thread.UncaughtExceptionHandler() {
      def uncaughtException(thread: Thread, cause: Throwable): Unit = {
        cause match {
          case NonFatal(_) | _: InterruptedException | _: NotImplementedError | _: ControlThrowable ⇒ log.error(cause, "Uncaught error from thread [{}]", thread.getName)
          case _ ⇒
            if (cause.isInstanceOf[IncompatibleClassChangeError] && cause.getMessage.startsWith("akka"))
              System.err.println(
                s"""Detected ${cause.getClass.getName} error, which MAY be caused by incompatible Akka versions on the classpath.
                  | Please note that a given Akka version MUST be the same across all modules of Akka that you are using,
                  | e.g. if you use akka-actor [${akka.Version.current} (resolved from current classpath)] all other core
                  | Akka modules MUST be of the same version. External projects like Alpakka, Persistence plugins or Akka
                  | HTTP etc. have their own version numbers - please make sure you're using a compatible set of libraries.
                 """.stripMargin.replaceAll("[\r\n]", ""))

            if (settings.JvmExitOnFatalError)
              try logFatalError("shutting down JVM since 'akka.jvm-exit-on-fatal-error' is enabled for", cause, thread)
              finally System.exit(-1)
            else
              try logFatalError("shutting down", cause, thread)
              finally terminate()
        }
      }

      @inline
      private def logFatalError(message: String, cause: Throwable, thread: Thread): Unit = {
        // First log to stderr as this has the best chance to get through in an 'emergency panic' situation:
        import System.err
        err.print("Uncaught error from thread [")
        err.print(thread.getName)
        err.print("]: ")
        err.print(cause.getMessage)
        err.print(", ")
        err.print(message)
        err.print(" ActorSystem[")
        err.print(name)
        err.println("]")
        System.err.flush()
        cause.printStackTrace(System.err)
        System.err.flush()

        // Also log using the normal infrastructure - hope for the best:
        markerLogging.error(LogMarker.Security, cause, "Uncaught error from thread [{}]: " + cause.getMessage + ", " + message + " ActorSystem[{}]", thread.getName, name)
      }
    }

  final val threadFactory: MonitorableThreadFactory =
    MonitorableThreadFactory(name, settings.Daemonicity, Option(classLoader), uncaughtExceptionHandler)

  /**
   * This is an extension point: by overriding this method, subclasses can
   * control all reflection activities of an actor system.
   */
  protected def createDynamicAccess(): DynamicAccess = new ReflectiveDynamicAccess(classLoader)

  private val _pm: DynamicAccess = createDynamicAccess()
  def dynamicAccess: DynamicAccess = _pm

  def logConfiguration(): Unit = log.info(settings.toString)

  protected def systemImpl: ActorSystemImpl = this

  def systemActorOf(props: Props, name: String): ActorRef = systemGuardian.underlying.attachChild(props, name, systemService = true)

  def actorOf(props: Props, name: String): ActorRef =
    if (guardianProps.isEmpty) guardian.underlying.attachChild(props, name, systemService = false)
    else throw new UnsupportedOperationException(
      s"cannot create top-level actor [$name] from the outside on ActorSystem with custom user guardian")

  def actorOf(props: Props): ActorRef =
    if (guardianProps.isEmpty) guardian.underlying.attachChild(props, systemService = false)
    else throw new UnsupportedOperationException("cannot create top-level actor from the outside on ActorSystem with custom user guardian")

  def stop(actor: ActorRef): Unit = {
    val path = actor.path
    val guard = guardian.path
    val sys = systemGuardian.path
    path.parent match {
      case `guard` ⇒ guardian ! StopChild(actor)
      case `sys`   ⇒ systemGuardian ! StopChild(actor)
      case _       ⇒ actor.asInstanceOf[InternalActorRef].stop()
    }
  }

  import settings._

  // this provides basic logging (to stdout) until .start() is called below
  val eventStream = new EventStream(this, DebugEventStream)
  eventStream.startStdoutLogger(settings)

  val logFilter: LoggingFilter = {
    val arguments = Vector(classOf[Settings] → settings, classOf[EventStream] → eventStream)
    dynamicAccess.createInstanceFor[LoggingFilter](LoggingFilter, arguments).get
  }

  private[this] val markerLogging = new MarkerLoggingAdapter(eventStream, getClass.getName + "(" + name + ")", this.getClass, logFilter)
  val log: LoggingAdapter = markerLogging

  val scheduler: Scheduler = createScheduler()

  val provider: ActorRefProvider = try {
    val arguments = Vector(
      classOf[String] → name,
      classOf[Settings] → settings,
      classOf[EventStream] → eventStream,
      classOf[DynamicAccess] → dynamicAccess)

    dynamicAccess.createInstanceFor[ActorRefProvider](ProviderClass, arguments).get
  } catch {
    case NonFatal(e) ⇒
      Try(stopScheduler())
      throw e
  }

  def deadLetters: ActorRef = provider.deadLetters

  val mailboxes: Mailboxes = new Mailboxes(settings, eventStream, dynamicAccess, deadLetters)

  val dispatchers: Dispatchers = new Dispatchers(settings, DefaultDispatcherPrerequisites(
    threadFactory, eventStream, scheduler, dynamicAccess, settings, mailboxes, defaultExecutionContext))

  val dispatcher: ExecutionContextExecutor = dispatchers.defaultGlobalDispatcher

  val internalCallingThreadExecutionContext: ExecutionContext =
    dynamicAccess.getObjectFor[ExecutionContext]("scala.concurrent.Future$InternalCallbackExecutor$").getOrElse(
      new ExecutionContext with BatchingExecutor {
        override protected def unbatchedExecute(r: Runnable): Unit = r.run()
        override protected def resubmitOnBlock: Boolean = false // Since we execute inline, no gain in resubmitting
        override def reportFailure(t: Throwable): Unit = dispatcher reportFailure t
      })

  private[this] final val terminationCallbacks = new TerminationCallbacks(provider.terminationFuture)(dispatcher)

  override def whenTerminated: Future[Terminated] = terminationCallbacks.terminationFuture
  override def getWhenTerminated: CompletionStage[Terminated] = FutureConverters.toJava(whenTerminated)
  def lookupRoot: InternalActorRef = provider.rootGuardian
  def guardian: LocalActorRef = provider.guardian
  def systemGuardian: LocalActorRef = provider.systemGuardian

  def /(actorName: String): ActorPath = guardian.path / actorName
  def /(path: Iterable[String]): ActorPath = guardian.path / path

  // Used for ManifestInfo.checkSameVersion
  private def allModules: List[String] = List(
    "akka-actor",
    "akka-actor-testkit-typed",
    "akka-actor-typed",
    "akka-agent",
    "akka-camel",
    "akka-cluster",
    "akka-cluster-metrics",
    "akka-cluster-sharding",
    "akka-cluster-sharding-typed",
    "akka-cluster-tools",
    "akka-cluster-typed",
    "akka-discovery",
    "akka-distributed-data",
    "akka-multi-node-testkit",
    "akka-osgi",
    "akka-persistence",
    "akka-persistence-query",
    "akka-persistence-shared",
    "akka-persistence-typed",
    "akka-protobuf",
    "akka-remote",
    "akka-slf4j",
    "akka-stream",
    "akka-stream-testkit",
    "akka-stream-typed")

  @volatile private var _initialized = false
  /**
   *  Asserts that the ActorSystem has been fully initialized. Can be used to guard code blocks that might accidentally
   *  be run during initialization but require a fully initialized ActorSystem before proceeding.
   */
  def assertInitialized(): Unit =
    if (!_initialized)
      throw new IllegalStateException(
        "The calling code expected that the ActorSystem was initialized but it wasn't yet. " +
          "This is probably a bug in the ActorSystem initialization sequence often related to initialization of extensions. " +
          "Please report at https://github.com/akka/akka/issues."
      )
  private lazy val _start: this.type = try {

    registerOnTermination(stopScheduler())
    // the provider is expected to start default loggers, LocalActorRefProvider does this
    provider.init(this)
    // at this point it should be initialized "enough" for most extensions that we might want to guard against otherwise
    _initialized = true

    if (settings.LogDeadLetters > 0)
      logDeadLetterListener = Some(systemActorOf(Props[DeadLetterListener], "deadLetterListener"))
    eventStream.startUnsubscriber()
    ManifestInfo(this).checkSameVersion("Akka", allModules, logWarning = true)
    loadExtensions()
    if (LogConfigOnStart) logConfiguration()
    this
  } catch {
    case NonFatal(e) ⇒
      try terminate() catch { case NonFatal(_) ⇒ Try(stopScheduler()) }
      throw e
  }

  def start(): this.type = _start
  def registerOnTermination[T](code: ⇒ T): Unit = { registerOnTermination(new Runnable { def run = code }) }
  def registerOnTermination(code: Runnable): Unit = { terminationCallbacks.add(code) }

  override def terminate(): Future[Terminated] = {
    if (!settings.LogDeadLettersDuringShutdown) logDeadLetterListener foreach stop
    guardian.stop()
    whenTerminated
  }

  @volatile var aborting = false

  /**
   * This kind of shutdown attempts to bring the system down and release its
   * resources more forcefully than plain shutdown. For example it will not
   * wait for remote-deployed child actors to terminate before terminating their
   * parents.
   */
  def abort(): Unit = {
    aborting = true
    terminate()
  }

  //#create-scheduler
  /**
   * Create the scheduler service. This one needs one special behavior: if
   * Closeable, it MUST execute all outstanding tasks upon .close() in order
   * to properly shutdown all dispatchers.
   *
   * Furthermore, this timer service MUST throw IllegalStateException if it
   * cannot schedule a task. Once scheduled, the task MUST be executed. If
   * executed upon close(), the task may execute before its timeout.
   */
  protected def createScheduler(): Scheduler =
    dynamicAccess.createInstanceFor[Scheduler](settings.SchedulerClass, immutable.Seq(
      classOf[Config] → settings.config,
      classOf[LoggingAdapter] → log,
      classOf[ThreadFactory] → threadFactory.withName(threadFactory.name + "-scheduler"))).get
  //#create-scheduler

  /*
   * This is called after the last actor has signaled its termination, i.e.
   * after the last dispatcher has had its chance to schedule its shutdown
   * action.
   */
  protected def stopScheduler(): Unit = scheduler match {
    case x: Closeable ⇒ x.close()
    case _            ⇒
  }

  private val extensions = new ConcurrentHashMap[ExtensionId[_], AnyRef]

  /**
   * Returns any extension registered to the specified Extension or returns null if not registered
   */
  @tailrec
  private def findExtension[T <: Extension](ext: ExtensionId[T]): T = extensions.get(ext) match {
    case c: CountDownLatch ⇒
      c.await(); findExtension(ext) //Registration in process, await completion and retry
    case t: Throwable ⇒ throw t //Initialization failed, throw same again
    case other ⇒
      other.asInstanceOf[T] //could be a T or null, in which case we return the null as T
  }

  @tailrec
  final def registerExtension[T <: Extension](ext: ExtensionId[T]): T = {
    findExtension(ext) match {
      case null ⇒ //Doesn't already exist, commence registration
        val inProcessOfRegistration = new CountDownLatch(1)
        extensions.putIfAbsent(ext, inProcessOfRegistration) match { // Signal that registration is in process
          case null ⇒ try { // Signal was successfully sent
            ext.createExtension(this) match { // Create and initialize the extension
              case null ⇒ throw new IllegalStateException(s"Extension instance created as 'null' for extension [$ext]")
              case instance ⇒
                extensions.replace(ext, inProcessOfRegistration, instance) //Replace our in process signal with the initialized extension
                instance //Profit!
            }
          } catch {
            case t: Throwable ⇒
              extensions.replace(ext, inProcessOfRegistration, t) //In case shit hits the fan, remove the inProcess signal
              throw t //Escalate to caller
          } finally {
            inProcessOfRegistration.countDown //Always notify listeners of the inProcess signal
          }
          case _ ⇒ registerExtension(ext) //Someone else is in process of registering an extension for this Extension, retry
        }
      case existing ⇒ existing.asInstanceOf[T]
    }
  }

  def extension[T <: Extension](ext: ExtensionId[T]): T = findExtension(ext) match {
    case null ⇒ throw new IllegalArgumentException(s"Trying to get non-registered extension [$ext]")
    case some ⇒ some.asInstanceOf[T]
  }

  def hasExtension(ext: ExtensionId[_ <: Extension]): Boolean = findExtension(ext) != null

  private def loadExtensions(): Unit = {
    /**
     * @param throwOnLoadFail Throw exception when an extension fails to load (needed for backwards compatibility)
     */
    def loadExtensions(key: String, throwOnLoadFail: Boolean): Unit = {
      immutableSeq(settings.config.getStringList(key)) foreach { fqcn ⇒
        dynamicAccess.getObjectFor[AnyRef](fqcn) recoverWith { case _ ⇒ dynamicAccess.createInstanceFor[AnyRef](fqcn, Nil) } match {
          case Success(p: ExtensionIdProvider) ⇒ registerExtension(p.lookup())
          case Success(p: ExtensionId[_])      ⇒ registerExtension(p)
          case Success(_) ⇒
            if (!throwOnLoadFail) log.error("[{}] is not an 'ExtensionIdProvider' or 'ExtensionId', skipping...", fqcn)
            else throw new RuntimeException(s"[$fqcn] is not an 'ExtensionIdProvider' or 'ExtensionId'")
          case Failure(problem) ⇒
            if (!throwOnLoadFail) log.error(problem, "While trying to load extension [{}], skipping...", fqcn)
            else throw new RuntimeException(s"While trying to load extension [$fqcn]", problem)
        }
      }
    }

    loadExtensions("akka.library-extensions", throwOnLoadFail = true)
    loadExtensions("akka.extensions", throwOnLoadFail = false)
  }

  override def toString: String = lookupRoot.path.root.address.toString

  override def printTree: String = {
    def printNode(node: ActorRef, indent: String): String = {
      node match {
        case wc: ActorRefWithCell ⇒
          val cell = wc.underlying
          (if (indent.isEmpty) "-> " else indent.dropRight(1) + "⌊-> ") +
            node.path.name + " " + Logging.simpleName(node) + " " +
            (cell match {
              case real: ActorCell ⇒ if (real.actor ne null) real.actor.getClass else "null"
              case _               ⇒ Logging.simpleName(cell)
            }) +
            (cell match {
              case real: ActorCell ⇒ " status=" + real.mailbox.currentStatus
              case _               ⇒ ""
            }) +
            " " + (cell.childrenRefs match {
              case ChildrenContainer.TerminatingChildrenContainer(_, toDie, reason) ⇒
                "Terminating(" + reason + ")" +
                  (toDie.toSeq.sorted mkString ("\n" + indent + "   |    toDie: ", "\n" + indent + "   |           ", ""))
              case x @ (ChildrenContainer.TerminatedChildrenContainer | ChildrenContainer.EmptyChildrenContainer) ⇒ x.toString
              case n: ChildrenContainer.NormalChildrenContainer ⇒ n.c.size + " children"
              case x ⇒ Logging.simpleName(x)
            }) +
            (if (cell.childrenRefs.children.isEmpty) "" else "\n") +
            ({
              val children = cell.childrenRefs.children.toSeq.sorted
              val bulk = children.dropRight(1) map (printNode(_, indent + "   |"))
              bulk ++ (children.lastOption map (printNode(_, indent + "    ")))
            } mkString ("\n"))
        case _ ⇒
          indent + node.path.name + " " + Logging.simpleName(node)
      }
    }
    printNode(lookupRoot, "")
  }

  final class TerminationCallbacks[T](upStreamTerminated: Future[T])(implicit ec: ExecutionContext) {
    private[this] final val done = Promise[T]()
    private[this] final val ref = new AtomicReference(done)

    // onComplete never fires twice so safe to avoid null check
    upStreamTerminated onComplete {
      t ⇒ ref.getAndSet(null).complete(t)
    }

    /**
     * Adds a Runnable that will be executed on ActorSystem termination.
     * Note that callbacks are executed in reverse order of insertion.
     *
     * @param r The callback to be executed on ActorSystem termination
     * Throws RejectedExecutionException if called after ActorSystem has been terminated.
     */
    final def add(r: Runnable): Unit = {
      @tailrec def addRec(r: Runnable, p: Promise[T]): Unit = ref.get match {
        case null                               ⇒ throw new RejectedExecutionException("ActorSystem already terminated.")
        case some if ref.compareAndSet(some, p) ⇒ some.completeWith(p.future.andThen { case _ ⇒ r.run() })
        case _                                  ⇒ addRec(r, p)
      }
      addRec(r, Promise[T]())
    }

    /**
     * Returns a Future which will be completed once all registered callbacks have been executed.
     */
    def terminationFuture: Future[T] = done.future
  }
}
