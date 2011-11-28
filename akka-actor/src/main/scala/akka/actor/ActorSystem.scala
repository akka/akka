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
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigRoot
import com.typesafe.config.ConfigFactory
import java.lang.reflect.InvocationTargetException
import akka.util.{ Helpers, Duration, ReflectiveAccess }
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import scala.annotation.tailrec
import org.jboss.netty.akka.util.internal.ConcurrentIdentityHashMap

object ActorSystem {

  val Version = "2.0-SNAPSHOT"

  val envHome = System.getenv("AKKA_HOME") match {
    case null | "" | "." ⇒ None
    case value           ⇒ Some(value)
  }

  val systemHome = System.getProperty("akka.home") match {
    case null | "" ⇒ None
    case value     ⇒ Some(value)
  }

  val GlobalHome = systemHome orElse envHome

  def create(name: String, config: Config): ActorSystem = apply(name, config)
  def apply(name: String, config: Config): ActorSystem = new ActorSystemImpl(name, config).start()

  def create(name: String): ActorSystem = apply(name)
  def apply(name: String): ActorSystem = apply(name, DefaultConfigurationLoader.defaultConfig)

  def create(): ActorSystem = apply()
  def apply(): ActorSystem = apply("default")

  class Settings(cfg: Config) {
    private def referenceConfig: Config =
      ConfigFactory.parseResource(classOf[ActorSystem], "/akka-actor-reference.conf",
        ConfigParseOptions.defaults.setAllowMissing(false))
    val config: ConfigRoot = ConfigFactory.emptyRoot("akka-actor").withFallback(cfg).withFallback(referenceConfig).resolve()

    import scala.collection.JavaConverters._
    import config._
    val ConfigVersion = getString("akka.version")

    val ProviderClass = getString("akka.actor.provider")

    val ActorTimeout = Timeout(Duration(getMilliseconds("akka.actor.timeout"), MILLISECONDS))
    // TODO This isn't used anywhere. Remove?
    val SerializeAllMessages = getBoolean("akka.actor.serialize-messages")

    val LogLevel = getString("akka.loglevel")
    val StdoutLogLevel = getString("akka.stdout-loglevel")
    val EventHandlers: Seq[String] = getStringList("akka.event-handlers").asScala
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

  }

  object DefaultConfigurationLoader {

    val defaultConfig: Config = fromProperties orElse fromClasspath orElse fromHome getOrElse emptyConfig

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
        ConfigFactory.parseResourceAnySyntax(ActorSystem.getClass, "/" + defaultLocation, configParseOptions)))
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
  // FIXME: do not publish this
  def deadLetterMailbox: Mailbox

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
  def registerOnTermination(code: ⇒ Unit)

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

class ActorSystemImpl(val name: String, val applicationConfig: Config) extends ActorSystem {

  import ActorSystem._

  val settings = new Settings(applicationConfig)

  protected def systemImpl = this

  private[akka] def systemActorOf(props: Props, address: String): ActorRef = provider.actorOf(this, props, systemGuardian, address, true)

  import settings._

  // this provides basic logging (to stdout) until .start() is called below
  val eventStream = new EventStream(DebugEventStream)
  eventStream.startStdoutLogger(settings)
  val log = new BusLogging(eventStream, "ActorSystem") // “this” used only for .getClass in tagging messages

  val scheduler = new DefaultScheduler(new HashedWheelTimer(log, Executors.defaultThreadFactory, settings.SchedulerTickDuration, settings.SchedulerTicksPerWheel))

  val provider: ActorRefProvider = {
    val providerClass = ReflectiveAccess.getClassFor(ProviderClass) match {
      case Left(e)  ⇒ throw e
      case Right(b) ⇒ b
    }
    val arguments = Seq(
      classOf[Settings] -> settings,
      classOf[EventStream] -> eventStream,
      classOf[Scheduler] -> scheduler)
    val types: Array[Class[_]] = arguments map (_._1) toArray
    val values: Array[AnyRef] = arguments map (_._2) toArray

    ReflectiveAccess.createInstance[ActorRefProvider](providerClass, types, values) match {
      case Left(e: InvocationTargetException) ⇒ throw e.getTargetException
      case Left(e)                            ⇒ throw e
      case Right(p)                           ⇒ p
    }
  }

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

  val dispatcherFactory = new Dispatchers(settings, DefaultDispatcherPrerequisites(eventStream, deadLetterMailbox, scheduler))
  implicit val dispatcher = dispatcherFactory.defaultGlobalDispatcher

  //FIXME Set this to a Failure when things bubble to the top
  def terminationFuture: Future[Unit] = provider.terminationFuture
  def guardian: ActorRef = provider.guardian
  def systemGuardian: ActorRef = provider.systemGuardian
  def deathWatch: DeathWatch = provider.deathWatch
  def nodename: String = provider.nodename
  def clustername: String = provider.clustername

  private final val nextName = new AtomicLong
  override protected def randomName(): String = Helpers.base64(nextName.incrementAndGet())

  def /(actorName: String): ActorPath = guardian.path / actorName

  private lazy val _start: this.type = {
    provider.init(this)
    deadLetters.init(dispatcher, provider.rootPath)
    // this starts the reaper actor and the user-configured logging subscribers, which are also actors
    eventStream.start(this)
    eventStream.startDefaultLoggers(this)
    loadExtensions()
    this
  }

  def start() = _start

  def registerOnTermination(code: ⇒ Unit) { terminationFuture onComplete (_ ⇒ code) }
  def registerOnTermination(code: Runnable) { terminationFuture onComplete (_ ⇒ code.run) }

  // TODO shutdown all that other stuff, whatever that may be
  def stop() {
    guardian.stop()
    terminationFuture onComplete (_ ⇒ scheduler.stop())
    terminationFuture onComplete (_ ⇒ dispatcher.shutdown())
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
