/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.config._
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

  val envConf = System.getenv("AKKA_MODE") match {
    case null | "" ⇒ None
    case value     ⇒ Some(value)
  }

  val systemConf = System.getProperty("akka.mode") match {
    case null | "" ⇒ None
    case value     ⇒ Some(value)
  }

  val defaultLocation = (systemConf orElse envConf).map("akka." + _ + ".conf").getOrElse("akka.conf")

  val fromProperties = try {
    Some(Configuration.fromFile(System.getProperty("akka.config", "")))
  } catch { case _ ⇒ None }

  val fromClasspath = try {
    Some(Configuration.fromResource(defaultLocation, getClass.getClassLoader))
  } catch { case _ ⇒ None }

  val fromHome = try {
    Some(Configuration.fromFile(GlobalHome.get + "/config/" + defaultLocation))
  } catch { case _ ⇒ None }

  val emptyConfig = Configuration.fromString("akka { version = \"" + Version + "\" }")

  val defaultConfig = fromProperties orElse fromClasspath orElse fromHome getOrElse emptyConfig

  def create(name: String, config: Configuration): ActorSystem = apply(name, config)
  def apply(name: String, config: Configuration): ActorSystem = new ActorSystemImpl(name, config).start()

  def create(name: String): ActorSystem = apply(name)
  def apply(name: String): ActorSystem = apply(name, defaultConfig)

  def create(): ActorSystem = apply()
  def apply(): ActorSystem = apply("default")

  sealed trait ExitStatus
  case object Stopped extends ExitStatus
  case class Failed(cause: Throwable) extends ExitStatus

  class AkkaConfig(val config: Configuration) {
    import config._
    val ConfigVersion = getString("akka.version", Version)

    val ProviderClass = getString("akka.actor.provider", "akka.actor.LocalActorRefProvider")

    val DefaultTimeUnit = Duration.timeUnit(getString("akka.time-unit", "seconds"))
    val ActorTimeout = Timeout(Duration(getInt("akka.actor.timeout", 5), DefaultTimeUnit))
    val ActorTimeoutMillis = ActorTimeout.duration.toMillis
    val SerializeAllMessages = getBool("akka.actor.serialize-messages", false)

    val TestTimeFactor =
      try java.lang.Double.parseDouble(System.getProperty("akka.test.timefactor")) catch {
        case _: Exception ⇒ getDouble("akka.test.timefactor", 1.0)
      }
    val SingleExpectDefaultTimeout = Duration(getDouble("akka.test.single-expect-default", 1), DefaultTimeUnit)
    val TestEventFilterLeeway = Duration(getDouble("akka.test.filter-leeway", 0.5), DefaultTimeUnit)

    val LogLevel = getString("akka.loglevel", "INFO")
    val StdoutLogLevel = getString("akka.stdout-loglevel", LogLevel)
    val EventHandlers = getList("akka.event-handlers")
    val AddLoggingReceive = getBool("akka.actor.debug.receive", false)
    val DebugAutoReceive = getBool("akka.actor.debug.autoreceive", false)
    val DebugLifecycle = getBool("akka.actor.debug.lifecycle", false)
    val FsmDebugEvent = getBool("akka.actor.debug.fsm", false)
    val DebugEventStream = getBool("akka.actor.debug.event-stream", false)

    val DispatcherThroughput = getInt("akka.actor.throughput", 5)
    val DispatcherDefaultShutdown = getLong("akka.actor.dispatcher-shutdown-timeout").
      map(time ⇒ Duration(time, DefaultTimeUnit)).getOrElse(1 second)
    val MailboxCapacity = getInt("akka.actor.default-dispatcher.mailbox-capacity", -1)
    val MailboxPushTimeout = Duration(getInt("akka.actor.default-dispatcher.mailbox-push-timeout-time", 10), DefaultTimeUnit)
    val DispatcherThroughputDeadlineTime = Duration(getInt("akka.actor.throughput-deadline-time", -1), DefaultTimeUnit)

    val Home = getString("akka.home")
    val BootClasses = getList("akka.boot")

    val EnabledModules = getList("akka.enabled-modules")
    val ClusterEnabled = EnabledModules exists (_ == "cluster")
    val ClusterName = getString("akka.cluster.name", "default")

    val RemoteTransport = getString("akka.remote.layer", "akka.remote.netty.NettyRemoteSupport")
    val RemoteServerPort = getInt("akka.remote.server.port", 2552)

    val FailureDetectorThreshold: Int = getInt("akka.remote.failure-detector.threshold", 8)
    val FailureDetectorMaxSampleSize: Int = getInt("akka.remote.failure-detector.max-sample-size", 1000)

    if (ConfigVersion != Version)
      throw new ConfigurationException("Akka JAR version [" + Version +
        "] does not match the provided config version [" + ConfigVersion + "]")

  }

}

abstract class ActorSystem extends ActorRefFactory with TypedActorFactory {
  import ActorSystem._

  def name: String
  def AkkaConfig: AkkaConfig
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

class ActorSystemImpl(val name: String, config: Configuration) extends ActorSystem {

  import ActorSystem._

  val AkkaConfig = new AkkaConfig(config)

  protected def app = this

  private[akka] def systemActorOf(props: Props, address: String): ActorRef = provider.actorOf(this, props, systemGuardian, address, true)

  import AkkaConfig._

  val address = RemoteAddress(System.getProperty("akka.remote.hostname") match {
    case null | "" ⇒ InetAddress.getLocalHost.getHostAddress
    case value     ⇒ value
  }, System.getProperty("akka.remote.port") match {
    case null | "" ⇒ AkkaConfig.RemoteServerPort
    case value     ⇒ value.toInt
  })

  // this provides basic logging (to stdout) until .start() is called below
  val eventStream = new EventStream(DebugEventStream)
  eventStream.startStdoutLogger(AkkaConfig)
  val log = new BusLogging(eventStream, this) // “this” used only for .getClass in tagging messages

  /**
   * The root actor path for this application.
   */
  val rootPath: ActorPath = new RootActorPath(address)

  val deadLetters = new DeadLetterActorRef(eventStream, rootPath / "nul")
  val deadLetterMailbox = new Mailbox(null) {
    becomeClosed()
    override def dispatcher = null //MessageDispatcher.this
    override def enqueue(receiver: ActorRef, envelope: Envelope) { deadLetters ! DeadLetter(envelope.message, envelope.sender, receiver) }
    override def dequeue() = null
    override def systemEnqueue(receiver: ActorRef, handle: SystemMessage) { deadLetters ! DeadLetter(handle, receiver, receiver) }
    override def systemDrain(): SystemMessage = null
    override def hasMessages = false
    override def hasSystemMessages = false
    override def numberOfMessages = 0
  }

  val scheduler = new DefaultScheduler(new HashedWheelTimer(log, Executors.defaultThreadFactory, 100, TimeUnit.MILLISECONDS, 512))

  // TODO correctly pull its config from the config
  val dispatcherFactory = new Dispatchers(AkkaConfig, eventStream, deadLetterMailbox, scheduler)
  implicit val dispatcher = dispatcherFactory.defaultGlobalDispatcher

  deadLetters.init(dispatcher)

  val provider: ActorRefProvider = {
    val providerClass = ReflectiveAccess.getClassFor(ProviderClass) match {
      case Left(e)  ⇒ throw e
      case Right(b) ⇒ b
    }
    val arguments = List(
      classOf[AkkaConfig] -> AkkaConfig,
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
    _serialization = new Serialization(this)
    _typedActor = new TypedActor(AkkaConfig, _serialization)
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
