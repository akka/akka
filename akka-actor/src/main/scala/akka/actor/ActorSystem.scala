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
import akka.util.Duration
import akka.util.ReflectiveAccess
import akka.serialization.Serialization
import akka.remote.RemoteAddress
import org.jboss.netty.akka.util.HashedWheelTimer
import java.util.concurrent.{ Executors, TimeUnit }
import java.io.File
import com.typesafe.config.Config
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigRoot
import com.typesafe.config.ConfigFactory

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

  sealed trait ExitStatus
  case object Stopped extends ExitStatus
  case class Failed(cause: Throwable) extends ExitStatus

  class Settings(cfg: Config) {
    val config: ConfigRoot = ConfigFactory.emptyRoot("akka").withFallback(cfg).withFallback(DefaultConfigurationLoader.referenceConfig).resolve()

    import scala.collection.JavaConverters._
    import akka.config.ConfigImplicits._
    import config._
    val ConfigVersion = getString("akka.version")

    val ProviderClass = getString("akka.actor.provider")

    val DefaultTimeUnit = Duration.timeUnit(getString("akka.time-unit"))
    val ActorTimeout = Timeout(Duration(getInt("akka.actor.timeout"), DefaultTimeUnit))
    val ActorTimeoutMillis = ActorTimeout.duration.toMillis
    val SerializeAllMessages = getBoolean("akka.actor.serialize-messages")

    val TestTimeFactor = getDouble("akka.test.timefactor")
    val SingleExpectDefaultTimeout = Duration(getDouble("akka.test.single-expect-default"), DefaultTimeUnit)
    val TestEventFilterLeeway = Duration(getDouble("akka.test.filter-leeway"), DefaultTimeUnit)

    val LogLevel = getString("akka.loglevel")
    val StdoutLogLevel = getString("akka.stdout-loglevel")
    val EventHandlers: Seq[String] = getStringList("akka.event-handlers").asScala
    val AddLoggingReceive = getBoolean("akka.actor.debug.receive")
    val DebugAutoReceive = getBoolean("akka.actor.debug.autoreceive")
    val DebugLifecycle = getBoolean("akka.actor.debug.lifecycle")
    val FsmDebugEvent = getBoolean("akka.actor.debug.fsm")
    val DebugEventStream = getBoolean("akka.actor.debug.event-stream")

    val DispatcherThroughput = getInt("akka.actor.default-dispatcher.throughput")
    val DispatcherDefaultShutdown = Duration(getLong("akka.actor.dispatcher-shutdown-timeout"), DefaultTimeUnit)
    val MailboxCapacity = getInt("akka.actor.default-dispatcher.mailbox-capacity")
    val MailboxPushTimeout = Duration(getInt("akka.actor.default-dispatcher.mailbox-push-timeout-time"), DefaultTimeUnit)
    val DispatcherThroughputDeadlineTime = Duration(getInt("akka.actor.default-dispatcher.throughput-deadline-time"), DefaultTimeUnit)

    val Home = config.getStringOption("akka.home")
    val BootClasses: Seq[String] = getStringList("akka.boot").asScala

    val EnabledModules: Seq[String] = getStringList("akka.enabled-modules").asScala

    // TODO move to cluster extension
    val ClusterEnabled = EnabledModules exists (_ == "cluster")
    val ClusterName = getString("akka.cluster.name")

    // TODO move to remote extension
    val RemoteTransport = getString("akka.remote.layer")
    val RemoteServerPort = getInt("akka.remote.server.port")
    val FailureDetectorThreshold = getInt("akka.remote.failure-detector.threshold")
    val FailureDetectorMaxSampleSize = getInt("akka.remote.failure-detector.max-sample-size")

    if (ConfigVersion != Version)
      throw new ConfigurationException("Akka JAR version [" + Version +
        "] does not match the provided config version [" + ConfigVersion + "]")

  }

  object DefaultConfigurationLoader {

    lazy val defaultConfig: Config = fromProperties orElse fromClasspath orElse fromHome getOrElse emptyConfig

    val envConf = System.getenv("AKKA_MODE") match {
      case null | "" ⇒ None
      case value     ⇒ Some(value)
    }

    val systemConf = System.getProperty("akka.mode") match {
      case null | "" ⇒ None
      case value     ⇒ Some(value)
    }

    // file extensions (.conf, .json, .properties), are handled by parseFileAnySyntax
    val defaultLocation = (systemConf orElse envConf).map("akka." + _).getOrElse("akka")
    private def configParseOptions = ConfigParseOptions.defaults.setAllowMissing(false)

    lazy val fromProperties = try {
      val property = Option(System.getProperty("akka.config"))
      property.map(p ⇒
        ConfigFactory.systemProperties().withFallback(
          ConfigFactory.parseFileAnySyntax(new File(p), configParseOptions)))
    } catch { case _ ⇒ None }

    lazy val fromClasspath = try {
      Option(ConfigFactory.systemProperties().withFallback(
        ConfigFactory.parseResourceAnySyntax(ActorSystem.getClass, "/" + defaultLocation, configParseOptions)))
    } catch { case _ ⇒ None }

    lazy val fromHome = try {
      Option(ConfigFactory.systemProperties().withFallback(
        ConfigFactory.parseFileAnySyntax(new File(GlobalHome.get + "/config/" + defaultLocation), configParseOptions)))
    } catch { case _ ⇒ None }

    val referenceConfig: Config =
      ConfigFactory.parseResource(classOf[ActorSystem], "/akka-actor-reference.conf", configParseOptions)

    lazy val emptyConfig = ConfigFactory.systemProperties()

  }

}

abstract class ActorSystem extends ActorRefFactory with TypedActorFactory {
  import ActorSystem._

  def name: String
  def settings: Settings
  def nodename: String

  /**
   * Construct a path below the application guardian.
   */
  def /(name: String): ActorPath
  def rootPath: ActorPath

  val startTime = System.currentTimeMillis
  def uptime = (System.currentTimeMillis - startTime) / 1000

  def eventStream: EventStream
  def log: LoggingAdapter

  def deadLetters: ActorRef
  def deadLetterMailbox: Mailbox

  // FIXME: Serialization should be an extension
  def serialization: Serialization
  // FIXME: TypedActor should be an extension
  def typedActor: TypedActor

  def scheduler: Scheduler
  def dispatcherFactory: Dispatchers
  def dispatcher: MessageDispatcher

  def registerOnTermination(code: ⇒ Unit)
  def registerOnTermination(code: Runnable)
  def stop()
}

class ActorSystemImpl(val name: String, config: Config) extends ActorSystem {

  import ActorSystem._

  val settings = new Settings(config)

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
  val log = new BusLogging(eventStream, this) // “this” used only for .getClass in tagging messages

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

  // FIXME make this configurable
  val scheduler = new DefaultScheduler(new HashedWheelTimer(log, Executors.defaultThreadFactory, 100, TimeUnit.MILLISECONDS, 512))

  // TODO correctly pull its config from the config
  val dispatcherFactory = new Dispatchers(settings, DefaultDispatcherPrerequisites(eventStream, deadLetterMailbox, scheduler))
  implicit val dispatcher = dispatcherFactory.defaultGlobalDispatcher

  deadLetters.init(dispatcher)

  val provider: ActorRefProvider = {
    val providerClass = ReflectiveAccess.getClassFor(ProviderClass) match {
      case Left(e)  ⇒ throw e
      case Right(b) ⇒ b
    }
    val arguments = List(
      classOf[Settings] -> settings,
      classOf[ActorPath] -> rootPath,
      classOf[EventStream] -> eventStream,
      classOf[MessageDispatcher] -> dispatcher,
      classOf[Scheduler] -> scheduler)
    val types: Array[Class[_]] = arguments map (_._1) toArray
    val values: Array[AnyRef] = arguments map (_._2) toArray

    ReflectiveAccess.createInstance[ActorRefProvider](providerClass, types, values) match {
      case Left(e)  ⇒ throw e
      case Right(p) ⇒ p
    }
  }

  def terminationFuture: Future[ExitStatus] = provider.terminationFuture
  def guardian: ActorRef = provider.guardian
  def systemGuardian: ActorRef = provider.systemGuardian
  def deathWatch: DeathWatch = provider.deathWatch
  def nodename: String = provider.nodename

  terminationFuture.onComplete(_ ⇒ scheduler.stop())
  terminationFuture.onComplete(_ ⇒ dispatcher.shutdown())

  @volatile
  private var _serialization: Serialization = _
  def serialization = _serialization
  @volatile
  private var _typedActor: TypedActor = _
  def typedActor = _typedActor

  def /(actorName: String): ActorPath = guardian.path / actorName

  def start(): this.type = {
    if (_serialization != null) throw new IllegalStateException("cannot initialize ActorSystemImpl twice!")
    _serialization = new Serialization(this)
    _typedActor = new TypedActor(settings, _serialization)
    provider.init(this)
    // this starts the reaper actor and the user-configured logging subscribers, which are also actors
    eventStream.start(this)
    eventStream.startDefaultLoggers(this)
    this
  }

  def registerOnTermination(code: ⇒ Unit) { terminationFuture onComplete (_ ⇒ code) }
  def registerOnTermination(code: Runnable) { terminationFuture onComplete (_ ⇒ code.run) }

  // TODO shutdown all that other stuff, whatever that may be
  def stop() {
    guardian.stop()
  }

}
