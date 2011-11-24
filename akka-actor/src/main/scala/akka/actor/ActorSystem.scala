/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.config.ConfigurationException
import akka.actor._
import akka.event._
import akka.dispatch._
import akka.util.duration._
import java.net.InetAddress
import com.eaio.uuid.UUID
import akka.serialization.Serialization
import akka.remote.RemoteAddress
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
import java.util.concurrent.{ TimeUnit, Executors, ConcurrentHashMap }

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
    val config: ConfigRoot = ConfigFactory.emptyRoot("akka").withFallback(cfg).withFallback(referenceConfig).resolve()

    import scala.collection.JavaConverters._
    import config._
    val ConfigVersion = getString("akka.version")

    val ProviderClass = getString("akka.actor.provider")

    val ActorTimeout = Timeout(Duration(getMilliseconds("akka.actor.timeout"), MILLISECONDS))
    val SerializeAllMessages = getBoolean("akka.actor.serialize-messages")

    val TestTimeFactor = getDouble("akka.test.timefactor")
    val SingleExpectDefaultTimeout = Duration(getMilliseconds("akka.test.single-expect-default"), MILLISECONDS)
    val TestEventFilterLeeway = Duration(getMilliseconds("akka.test.filter-leeway"), MILLISECONDS)

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

    val SchedulerTickDuration = Duration(getMilliseconds("akka.scheduler.tickDuration"), TimeUnit.MILLISECONDS)
    val SchedulerTicksPerWheel = getInt("akka.scheduler.ticksPerWheel")

    // FIXME move to cluster extension
    val ClusterEnabled = EnabledModules exists (_ == "cluster")
    val ClusterName = getString("akka.cluster.name")

    // FIXME move to remote extension
    val RemoteTransport = getString("akka.remote.layer")
    val RemoteServerPort = getInt("akka.remote.server.port")
    val FailureDetectorThreshold = getInt("akka.remote.failure-detector.threshold")
    val FailureDetectorMaxSampleSize = getInt("akka.remote.failure-detector.max-sample-size")

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
abstract class ActorSystem extends ActorRefFactory with TypedActorFactory {
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
   * Construct a path below the application guardian to be used with [[ActorSystem.actorFor]].
   */
  def /(name: String): ActorPath

  /**
   * The root path for all actors within this actor system, including remote
   * address if enabled.
   */
  def rootPath: ActorPath

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

  // FIXME: Serialization should be an extension
  def serialization: Serialization
  // FIXME: TypedActor should be an extension
  def typedActor: TypedActor

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
   * Register an [[akka.actor.Extension]] within this actor system. The supplied
   * object is interrogated for the extension’s key with which the extension is
   * accessible from anywhere you have a reference to this actor system in
   * scope, e.g. within actors (see [[ActorSystem.extension]]).
   *
   * Extensions can be registered automatically by adding their fully-qualified
   * class name to the `akka.extensions` configuration key.
   */
  def registerExtension(ext: Extension[_ <: AnyRef])

  /**
   * Obtain a reference to a registered extension by passing in the key which
   * the extension object returned from its init method (typically a static
   * field or Scala `object`):
   *
   * {{{
   * class MyActor extends Actor {
   *   val ext: MyExtension = context.app.extension(MyExtension.key)
   * }
   * }}}
   *
   * Throws IllegalArgumentException if the extension key is not found.
   */
  def extension[T <: AnyRef](key: ExtensionKey[T]): T

  /**
   * Query presence of a specific extension. Beware that this key needs to be
   * “the same” as the one used for registration (it is using a HashMap).
   */
  def hasExtension(key: ExtensionKey[_]): Boolean
}

class ActorSystemImpl(val name: String, _config: Config) extends ActorSystem {

  import ActorSystem._

  val settings = new Settings(_config)

  protected def systemImpl = this

  private[akka] def systemActorOf(props: Props, address: String): ActorRef = provider.actorOf(this, props, systemGuardian, address, true)

  import settings._

  val address = RemoteAddress(System.getProperty("akka.remote.hostname") match {
    case null | "" ⇒ InetAddress.getLocalHost.getHostAddress
    case value     ⇒ value
  }, System.getProperty("akka.remote.port") match {
    case null | "" ⇒ settings.RemoteServerPort
    case value     ⇒ value.toInt
  })

  // this provides basic logging (to stdout) until .start() is called below
  val eventStream = new EventStream(DebugEventStream)
  eventStream.startStdoutLogger(settings)
  val log = new BusLogging(eventStream, "ActorSystem") // “this” used only for .getClass in tagging messages

  /**
   * The root actor path for this application.
   */
  val rootPath: ActorPath = new RootActorPath(address)

  val deadLetters = new DeadLetterActorRef(eventStream, rootPath / "nul")
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

  val scheduler = new DefaultScheduler(new HashedWheelTimer(log, Executors.defaultThreadFactory, settings.SchedulerTickDuration, settings.SchedulerTicksPerWheel))

  // TODO correctly pull its config from the config
  val dispatcherFactory = new Dispatchers(settings, DefaultDispatcherPrerequisites(eventStream, deadLetterMailbox, scheduler))
  implicit val dispatcher = dispatcherFactory.defaultGlobalDispatcher

  deadLetters.init(dispatcher)

  val provider: ActorRefProvider = {
    val providerClass = ReflectiveAccess.getClassFor(ProviderClass) match {
      case Left(e)  ⇒ throw e
      case Right(b) ⇒ b
    }
    val arguments = Seq(
      classOf[Settings] -> settings,
      classOf[ActorPath] -> rootPath,
      classOf[EventStream] -> eventStream,
      classOf[MessageDispatcher] -> dispatcher,
      classOf[Scheduler] -> scheduler)
    val types: Array[Class[_]] = arguments map (_._1) toArray
    val values: Array[AnyRef] = arguments map (_._2) toArray

    ReflectiveAccess.createInstance[ActorRefProvider](providerClass, types, values) match {
      case Left(e: InvocationTargetException) ⇒ throw e.getTargetException
      case Left(e)                            ⇒ throw e
      case Right(p)                           ⇒ p
    }
  }
  //FIXME Set this to a Failure when things bubble to the top
  def terminationFuture: Future[Unit] = provider.terminationFuture
  def guardian: ActorRef = provider.guardian
  def systemGuardian: ActorRef = provider.systemGuardian
  def deathWatch: DeathWatch = provider.deathWatch
  def nodename: String = provider.nodename

  private final val nextName = new AtomicLong
  override protected def randomName(): String = Helpers.base64(nextName.incrementAndGet())

  @volatile
  private var _serialization: Serialization = _
  def serialization = _serialization
  @volatile
  private var _typedActor: TypedActor = _
  def typedActor = _typedActor

  def /(actorName: String): ActorPath = guardian.path / actorName

  private lazy val _start: this.type = {
    _serialization = new Serialization(this)
    _typedActor = new TypedActor(settings, _serialization)
    provider.init(this)
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

  private val extensions = new ConcurrentHashMap[ExtensionKey[_], Extension[_]]

  def registerExtension(ext: Extension[_ <: AnyRef]) {
    val key = ext.init(this)
    extensions.put(key, ext) match {
      case null ⇒
      case old  ⇒ log.warning("replacing extension {}:{} with {}", key, old, ext)
    }
  }

  def extension[T <: AnyRef](key: ExtensionKey[T]): T = extensions.get(key) match {
    case null ⇒ throw new IllegalArgumentException("trying to get non-registered extension " + key)
    case x    ⇒ x.asInstanceOf[T]
  }

  def hasExtension(key: ExtensionKey[_]): Boolean = extensions.get(key) != null

  private def loadExtensions() {
    import scala.collection.JavaConversions._
    settings.config.getStringList("akka.extensions") foreach { fqcn ⇒
      import ReflectiveAccess._
      createInstance[Extension[_ <: AnyRef]](fqcn, noParams, noArgs) match {
        case Left(ex)   ⇒ log.error(ex, "Exception trying to load extension " + fqcn)
        case Right(ext) ⇒ if (ext.isInstanceOf[Extension[_]]) registerExtension(ext) else log.error("Class {} is not an Extension", fqcn)
      }
    }
  }
}
