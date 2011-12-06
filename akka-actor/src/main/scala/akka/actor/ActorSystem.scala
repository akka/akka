/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.config.ConfigurationException
import akka.actor._
import akka.event._
import akka.dispatch._
import akka.util.duration._
import org.jboss.netty.akka.util.HashedWheelTimer
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.io.File
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigResolveOptions
import com.typesafe.config.ConfigException
import java.lang.reflect.InvocationTargetException
import akka.util.{ Helpers, Duration, ReflectiveAccess }
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ CountDownLatch, Executors, ConcurrentHashMap }
import scala.annotation.tailrec
import org.jboss.netty.akka.util.internal.ConcurrentIdentityHashMap
import java.io.Closeable

object ActorSystem {

  val Version = "2.0-SNAPSHOT"

  val EnvHome = System.getenv("AKKA_HOME") match {
    case null | "" | "." ⇒ None
    case value           ⇒ Some(value)
  }

  val SystemHome = System.getProperty("akka.home") match {
    case null | "" ⇒ None
    case value     ⇒ Some(value)
  }

  val GlobalHome = SystemHome orElse EnvHome

  def create(name: String, config: Config): ActorSystem = apply(name, config)
  def apply(name: String, config: Config): ActorSystem = new ActorSystemImpl(name, config).start()

  /**
   * Uses the standard default Config from ConfigFactory.load(), since none is provided.
   */
  def create(name: String): ActorSystem = apply(name)

  /**
   * Uses the standard default Config from ConfigFactory.load(), since none is provided.
   */
  def apply(name: String): ActorSystem = apply(name, ConfigFactory.load())

  def create(): ActorSystem = apply()
  def apply(): ActorSystem = apply("default")

  class Settings(cfg: Config, val name: String) {

    val config: Config = {
      val config = cfg.withFallback(ConfigFactory.defaultReference)
      config.checkValid(ConfigFactory.defaultReference, "akka")
      config
    }

    import scala.collection.JavaConverters._
    import config._

    val ConfigVersion = getString("akka.version")

    val ProviderClass = getString("akka.actor.provider")

    val CreationTimeout = Timeout(Duration(getMilliseconds("akka.actor.creation-timeout"), MILLISECONDS))
    val ActorTimeout = Timeout(Duration(getMilliseconds("akka.actor.timeout"), MILLISECONDS))
    val SerializeAllMessages = getBoolean("akka.actor.serialize-messages")

    val LogLevel = getString("akka.loglevel")
    val StdoutLogLevel = getString("akka.stdout-loglevel")
    val EventHandlers: Seq[String] = getStringList("akka.event-handlers").asScala
    val LogConfigOnStart = config.getBoolean("akka.logConfigOnStart")
    val AddLoggingReceive = getBoolean("akka.actor.debug.receive")
    val DebugAutoReceive = getBoolean("akka.actor.debug.autoreceive")
    val DebugLifecycle = getBoolean("akka.actor.debug.lifecycle")
    val FsmDebugEvent = getBoolean("akka.actor.debug.fsm")
    val DebugEventStream = getBoolean("akka.actor.debug.event-stream")

    val DispatcherThroughput = getInt("akka.actor.default-dispatcher.throughput")
    val DispatcherDefaultShutdown = Duration(getMilliseconds("akka.actor.dispatcher-shutdown-timeout"), MILLISECONDS)
    val MailboxCapacity = getInt("akka.actor.default-dispatcher.mailbox-capacity")
    val MailboxPushTimeout = Duration(getNanoseconds("akka.actor.default-dispatcher.mailbox-push-timeout-time"), NANOSECONDS)
    val DispatcherThroughputDeadlineTime = Duration(getNanoseconds("akka.actor.default-dispatcher.throughput-deadline-time"), NANOSECONDS)

    val Home = config.getString("akka.home") match {
      case "" ⇒ None
      case x  ⇒ Some(x)
    }
    val BootClasses: Seq[String] = getStringList("akka.boot").asScala

    val EnabledModules: Seq[String] = getStringList("akka.enabled-modules").asScala

    val SchedulerTickDuration = Duration(getMilliseconds("akka.scheduler.tickDuration"), MILLISECONDS)
    val SchedulerTicksPerWheel = getInt("akka.scheduler.ticksPerWheel")

    if (ConfigVersion != Version)
      throw new ConfigurationException("Akka JAR version [" + Version +
        "] does not match the provided config version [" + ConfigVersion + "]")

    override def toString: String = config.root.render
  }

  // TODO move to migration kit
  object OldConfigurationLoader {

    val defaultConfig: Config = {
      val cfg = fromProperties orElse fromClasspath orElse fromHome getOrElse emptyConfig
      cfg.withFallback(ConfigFactory.defaultReference).resolve(ConfigResolveOptions.defaults)
    }

    // file extensions (.conf, .json, .properties), are handled by parseFileAnySyntax
    val defaultLocation: String = (systemMode orElse envMode).map("akka." + _).getOrElse("akka")

    private def envMode = System.getenv("AKKA_MODE") match {
      case null | "" ⇒ None
      case value     ⇒ Some(value)
    }

    private def systemMode = System.getProperty("akka.mode") match {
      case null | "" ⇒ None
      case value     ⇒ Some(value)
    }

    private def configParseOptions = ConfigParseOptions.defaults.setAllowMissing(false)

    private def fromProperties = try {
      val property = Option(System.getProperty("akka.config"))
      property.map(p ⇒
        ConfigFactory.systemProperties.withFallback(
          ConfigFactory.parseFileAnySyntax(new File(p), configParseOptions)))
    } catch { case _ ⇒ None }

    private def fromClasspath = try {
      Option(ConfigFactory.systemProperties.withFallback(
        ConfigFactory.parseResourcesAnySyntax(ActorSystem.getClass, "/" + defaultLocation, configParseOptions)))
    } catch { case _ ⇒ None }

    private def fromHome = try {
      Option(ConfigFactory.systemProperties.withFallback(
        ConfigFactory.parseFileAnySyntax(new File(GlobalHome.get + "/config/" + defaultLocation), configParseOptions)))
    } catch { case _ ⇒ None }

    private def emptyConfig = ConfigFactory.systemProperties
  }
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
 * system.actorOf[MyActor]("name")
 * system.actorOf[MyActor]
 * system.actorOf(new MyActor(...))
 * 
 * // Java
 * system.actorOf(classOf[MyActor]);
 * system.actorOf(new Creator<MyActor>() {
 *   public MyActor create() { ... }
 * });
 * system.actorOf(new Creator<MyActor>() {
 *   public MyActor create() { ... }
 * }, "name");
 * }}}
 * 
 * Where no name is given explicitly, one will be automatically generated.
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
   * The logical node name where this actor system resides.
   */
  def nodename: String

  /**
   * The logical name of the cluster this actor system belongs to.
   */
  def clustername: String

  /**
   * Construct a path below the application guardian to be used with [[ActorSystem.actorFor]].
   */
  def /(name: String): ActorPath

  /**
   * Construct a path below the application guardian to be used with [[ActorSystem.actorFor]].
   */
  def /(name: Iterable[String]): ActorPath

  /**
   * Start-up time in milliseconds since the epoch.
   */
  val startTime = System.currentTimeMillis

  /**
   * Up-time of this actor system in seconds.
   */
  def uptime = (System.currentTimeMillis - startTime) / 1000

  /**
   * Main event bus of this actor system, used for example for logging.
   */
  def eventStream: EventStream

  /**
   * Convenient logging adapter for logging to the [[ActorSystem.eventStream]].
   */
  def log: LoggingAdapter

  /**
   * Actor reference where messages are re-routed to which were addressed to
   * stopped or non-existing actors. Delivery to this actor is done on a best
   * effort basis and hence not strictly guaranteed.
   */
  def deadLetters: ActorRef

  /**
   * Light-weight scheduler for running asynchronous tasks after some deadline
   * in the future. Not terribly precise but cheap.
   */
  def scheduler: Scheduler

  /**
   * Helper object for creating new dispatchers and passing in all required
   * information.
   */
  def dispatcherFactory: Dispatchers

  /**
   * Default dispatcher as configured. This dispatcher is used for all actors
   * in the actor system which do not have a different dispatcher configured
   * explicitly.
   */
  def dispatcher: MessageDispatcher

  /**
   * Register a block of code to run after all actors in this actor system have
   * been stopped.
   */
  def registerOnTermination[T](code: ⇒ T)

  /**
   * Register a block of code to run after all actors in this actor system have
   * been stopped (Java API).
   */
  def registerOnTermination(code: Runnable)

  /**
   * Stop this actor system. This will stop the guardian actor, which in turn
   * will recursively stop all its child actors, then the system guardian
   * (below which the logging actors reside) and the execute all registered
   * termination handlers (see [[ActorSystem.registerOnTermination]]).
   */
  def stop()

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

class ActorSystemImpl(val name: String, applicationConfig: Config) extends ActorSystem {

  if (!name.matches("""^\w+$"""))
    throw new IllegalArgumentException("invalid ActorSystem name '" + name + "', must contain only word characters (i.e. [a-zA-Z_0-9])")

  import ActorSystem._

  val settings = new Settings(applicationConfig, name)

  def logConfiguration(): Unit = log.info(settings.toString)

  protected def systemImpl = this

  private[akka] def systemActorOf(props: Props, name: String): ActorRef = {
    implicit val timeout = settings.CreationTimeout
    (systemGuardian ? CreateChild(props, name)).get match {
      case ref: ActorRef ⇒ ref
      case ex: Exception ⇒ throw ex
    }
  }

  def actorOf(props: Props, name: String): ActorRef = {
    implicit val timeout = settings.CreationTimeout
    (guardian ? CreateChild(props, name)).get match {
      case ref: ActorRef ⇒ ref
      case ex: Exception ⇒ throw ex
    }
  }

  def actorOf(props: Props): ActorRef = {
    implicit val timeout = settings.CreationTimeout
    (guardian ? CreateRandomNameChild(props)).get match {
      case ref: ActorRef ⇒ ref
      case ex: Exception ⇒ throw ex
    }
  }

  import settings._

  // this provides basic logging (to stdout) until .start() is called below
  val eventStream = new EventStream(DebugEventStream)
  eventStream.startStdoutLogger(settings)
  val log = new BusLogging(eventStream, "ActorSystem") // “this” used only for .getClass in tagging messages

  val scheduler = createScheduler()

  val deadLetters = new DeadLetterActorRef(eventStream)
  val deadLetterMailbox = new Mailbox(null) {
    becomeClosed()
    override def enqueue(receiver: ActorRef, envelope: Envelope) { deadLetters ! DeadLetter(envelope.message, envelope.sender, receiver) }
    override def dequeue() = null
    override def systemEnqueue(receiver: ActorRef, handle: SystemMessage) { deadLetters ! DeadLetter(handle, receiver, receiver) }
    override def systemDrain(): SystemMessage = null
    override def hasMessages = false
    override def hasSystemMessages = false
    override def numberOfMessages = 0
  }

  val provider: ActorRefProvider = {
    val providerClass = ReflectiveAccess.getClassFor(ProviderClass) match {
      case Left(e)  ⇒ throw e
      case Right(b) ⇒ b
    }
    val arguments = Seq(
      classOf[String] -> name,
      classOf[Settings] -> settings,
      classOf[EventStream] -> eventStream,
      classOf[Scheduler] -> scheduler,
      classOf[InternalActorRef] -> deadLetters)
    val types: Array[Class[_]] = arguments map (_._1) toArray
    val values: Array[AnyRef] = arguments map (_._2) toArray

    ReflectiveAccess.createInstance[ActorRefProvider](providerClass, types, values) match {
      case Left(e: InvocationTargetException) ⇒ throw e.getTargetException
      case Left(e)                            ⇒ throw e
      case Right(p)                           ⇒ p
    }
  }

  val dispatcherFactory = new Dispatchers(settings, DefaultDispatcherPrerequisites(eventStream, deadLetterMailbox, scheduler))
  // TODO why implicit val dispatcher?
  implicit val dispatcher = dispatcherFactory.defaultGlobalDispatcher

  def terminationFuture: Future[Unit] = provider.terminationFuture
  def lookupRoot: InternalActorRef = provider.rootGuardian
  def guardian: InternalActorRef = provider.guardian
  def systemGuardian: InternalActorRef = provider.systemGuardian
  def deathWatch: DeathWatch = provider.deathWatch
  def nodename: String = provider.nodename
  def clustername: String = provider.clustername

  def /(actorName: String): ActorPath = guardian.path / actorName
  def /(path: Iterable[String]): ActorPath = guardian.path / path

  private lazy val _start: this.type = {
    provider.init(this)
    deadLetters.init(dispatcher, provider.rootPath)
    // this starts the reaper actor and the user-configured logging subscribers, which are also actors
    eventStream.startDefaultLoggers(this)
    registerOnTermination(stopScheduler())
    loadExtensions()
    if (LogConfigOnStart) logConfiguration()
    this
  }

  def start() = _start

  def registerOnTermination[T](code: ⇒ T) { terminationFuture onComplete (_ ⇒ code) }
  def registerOnTermination(code: Runnable) { terminationFuture onComplete (_ ⇒ code.run) }

  def stop() {
    guardian.stop()
  }

  /**
   * Create the scheduler service. This one needs one special behavior: if
   * Closeable, it MUST execute all outstanding tasks upon .close() in order
   * to properly shutdown all dispatchers.
   *
   * Furthermore, this timer service MUST throw IllegalStateException if it
   * cannot schedule a task. Once scheduled, the task MUST be executed. If
   * executed upon close(), the task may execute before its timeout.
   */
  protected def createScheduler(): Scheduler = {
    val threadFactory = new MonitorableThreadFactory("DefaultScheduler")
    val hwt = new HashedWheelTimer(log, threadFactory, settings.SchedulerTickDuration, settings.SchedulerTicksPerWheel)
    // note that dispatcher is by-name parameter in DefaultScheduler constructor,
    // because dispatcher is not initialized when the scheduler is created
    def safeDispatcher = {
      if (dispatcher eq null) {
        val exc = new IllegalStateException("Scheduler is using dispatcher before it has been initialized")
        log.error(exc, exc.getMessage)
        throw exc
      } else {
        dispatcher
      }
    }
    new DefaultScheduler(hwt, log, safeDispatcher)
  }

  /*
   * This is called after the last actor has signaled its termination, i.e. 
   * after the last dispatcher has had its chance to schedule its shutdown 
   * action.
   */
  protected def stopScheduler(): Unit = scheduler match {
    case x: Closeable ⇒ x.close()
    case _            ⇒
  }

  private val extensions = new ConcurrentIdentityHashMap[ExtensionId[_], AnyRef]

  /**
   * Returns any extension registered to the specified Extension or returns null if not registered
   */
  @tailrec
  private def findExtension[T <: Extension](ext: ExtensionId[T]): T = extensions.get(ext) match {
    case c: CountDownLatch ⇒ c.await(); findExtension(ext) //Registration in process, await completion and retry
    case other             ⇒ other.asInstanceOf[T] //could be a T or null, in which case we return the null as T
  }

  @tailrec
  final def registerExtension[T <: Extension](ext: ExtensionId[T]): T = {
    findExtension(ext) match {
      case null ⇒ //Doesn't already exist, commence registration
        val inProcessOfRegistration = new CountDownLatch(1)
        extensions.putIfAbsent(ext, inProcessOfRegistration) match { // Signal that registration is in process
          case null ⇒ try { // Signal was successfully sent
            ext.createExtension(this) match { // Create and initialize the extension
              case null ⇒ throw new IllegalStateException("Extension instance created as null for Extension: " + ext)
              case instance ⇒
                extensions.replace(ext, inProcessOfRegistration, instance) //Replace our in process signal with the initialized extension
                instance //Profit!
            }
          } catch {
            case t ⇒
              extensions.remove(ext, inProcessOfRegistration) //In case shit hits the fan, remove the inProcess signal
              throw t //Escalate to caller
          } finally {
            inProcessOfRegistration.countDown //Always notify listeners of the inProcess signal
          }
          case other ⇒ registerExtension(ext) //Someone else is in process of registering an extension for this Extension, retry
        }
      case existing ⇒ existing.asInstanceOf[T]
    }
  }

  def extension[T <: Extension](ext: ExtensionId[T]): T = findExtension(ext) match {
    case null ⇒ throw new IllegalArgumentException("Trying to get non-registered extension " + ext)
    case some ⇒ some.asInstanceOf[T]
  }

  def hasExtension(ext: ExtensionId[_ <: Extension]): Boolean = findExtension(ext) != null

  private def loadExtensions() {
    import scala.collection.JavaConversions._
    settings.config.getStringList("akka.extensions") foreach { fqcn ⇒
      import ReflectiveAccess._
      getObjectFor[AnyRef](fqcn).fold(_ ⇒ createInstance[AnyRef](fqcn, noParams, noArgs), Right(_)) match {
        case Right(p: ExtensionIdProvider) ⇒ registerExtension(p.lookup());
        case Right(p: ExtensionId[_])      ⇒ registerExtension(p);
        case Right(other)                  ⇒ log.error("'{}' is not an ExtensionIdProvider or ExtensionId, skipping...", fqcn)
        case Left(problem)                 ⇒ log.error(problem, "While trying to load extension '{}', skipping...", fqcn)
      }

    }
  }
}
