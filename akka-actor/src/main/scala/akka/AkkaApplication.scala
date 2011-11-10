/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka

import akka.config._
import akka.actor._
import akka.dispatch._
import akka.event._
import akka.util.duration._
import java.net.InetAddress
import com.eaio.uuid.UUID
import akka.dispatch.{ Dispatchers, Future }
import akka.util.Duration
import akka.util.ReflectiveAccess
import akka.routing.Routing
import akka.remote.RemoteSupport
import akka.serialization.Serialization
import java.net.InetSocketAddress
import org.jboss.netty.akka.util.HashedWheelTimer

object AkkaApplication {

  type AkkaConfig = a.AkkaConfig.type forSome { val a: AkkaApplication }

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

  def apply(name: String, config: Configuration) = new AkkaApplication(name, config)

  def apply(name: String): AkkaApplication = new AkkaApplication(name)

  def apply(): AkkaApplication = new AkkaApplication()

  sealed trait ExitStatus
  case object Stopped extends ExitStatus
  case class Failed(cause: Throwable) extends ExitStatus

}

class AkkaApplication(val name: String, val config: Configuration) extends ActorRefFactory with TypedActorFactory {

  def this(name: String) = this(name, AkkaApplication.defaultConfig)
  def this() = this("default")

  import AkkaApplication._

  object AkkaConfig {
    import config._
    val ConfigVersion = getString("akka.version", Version)

    val ProviderClass = getString("akka.actor.provider", "akka.actor.LocalActorRefProvider")

    val DefaultTimeUnit = Duration.timeUnit(getString("akka.time-unit", "seconds"))
    val ActorTimeout = Timeout(Duration(getInt("akka.actor.timeout", 5), DefaultTimeUnit))
    val ActorTimeoutMillis = ActorTimeout.duration.toMillis
    val SerializeAllMessages = getBool("akka.actor.serialize-messages", false)

    val TestTimeFactor = getDouble("akka.test.timefactor", 1.0)
    val TestEventFilterLeeway = Duration(getDouble("akka.test.filter-leeway", 0.5), DefaultTimeUnit)

    val LogLevel = getString("akka.loglevel", "INFO")
    val StdoutLogLevel = getString("akka.stdout-loglevel", LogLevel)
    val EventHandlers = getList("akka.event-handlers")
    val AddLoggingReceive = getBool("akka.actor.debug.receive", false)
    val DebugAutoReceive = getBool("akka.actor.debug.autoreceive", false)
    val DebugLifecycle = getBool("akka.actor.debug.lifecycle", false)
    val FsmDebugEvent = getBool("akka.actor.debug.fsm", false)
    val DebugMainBus = getBool("akka.actor.debug.mainbus", false)

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
  }

  object MistSettings {
    val JettyServer = "jetty"
    val TimeoutAttribute = "timeout"

    val ConnectionClose = config.getBool("akka.http.connection-close", true)
    val RootActorBuiltin = config.getBool("akka.http.root-actor-builtin", true)
    val RootActorID = config.getString("akka.http.root-actor-id", "_httproot")
    val DefaultTimeout = config.getLong("akka.http.timeout", 1000)
    val ExpiredHeaderName = config.getString("akka.http.expired-header-name", "Async-Timeout")
    val ExpiredHeaderValue = config.getString("akka.http.expired-header-value", "expired")
  }

  private[akka] def systemActorOf(props: Props, address: String): ActorRef = provider.actorOf(props, systemGuardian, address, true)

  import AkkaConfig._

  if (ConfigVersion != Version)
    throw new ConfigurationException("Akka JAR version [" + Version +
      "] does not match the provided config version [" + ConfigVersion + "]")

  val startTime = System.currentTimeMillis
  def uptime = (System.currentTimeMillis - startTime) / 1000

  val nodename: String = System.getProperty("akka.cluster.nodename") match {
    case null | "" ⇒ new UUID().toString
    case value     ⇒ value
  }

  val defaultAddress = new InetSocketAddress(System.getProperty("akka.remote.hostname") match {
    case null | "" ⇒ InetAddress.getLocalHost.getHostAddress
    case value     ⇒ value
  }, System.getProperty("akka.remote.port") match {
    case null | "" ⇒ AkkaConfig.RemoteServerPort
    case value     ⇒ value.toInt
  })

  def hostname: String = defaultAddress.getAddress.getHostAddress

  def port: Int = defaultAddress.getPort

  // this provides basic logging (to stdout) until .start() is called below
  val mainbus = new MainBus(DebugMainBus)
  mainbus.startStdoutLogger(AkkaConfig)
  val log = new BusLogging(mainbus, this)

  // TODO correctly pull its config from the config
  val dispatcherFactory = new Dispatchers(this)

  implicit val dispatcher = dispatcherFactory.defaultGlobalDispatcher

  // Start the scheduler before the provider (to prevent null pointers from happening in e.g. Gossiper)
  val scheduler = new DefaultScheduler(new HashedWheelTimer)

  // TODO think about memory consistency effects when doing funky stuff inside constructor
  val reflective = new ReflectiveAccess(this)

  // TODO think about memory consistency effects when doing funky stuff inside constructor
  val provider: ActorRefProvider = reflective.createProvider

  def terminationFuture: Future[ExitStatus] = provider.terminationFuture

  terminationFuture.onComplete(_ ⇒ scheduler.stop())

  private class Guardian extends Actor {
    def receive = {
      case Terminated(_) ⇒ context.self.stop()
    }
  }
  private class SystemGuardian extends Actor {
    def receive = {
      case Terminated(_) ⇒
        mainbus.stopDefaultLoggers()
        context.self.stop()
    }
  }
  private val guardianFaultHandlingStrategy = {
    import akka.actor.FaultHandlingStrategy._
    OneForOneStrategy {
      case _: ActorKilledException         ⇒ Stop
      case _: ActorInitializationException ⇒ Stop
      case _: Exception                    ⇒ Restart
    }
  }
  private val guardianProps = Props(new Guardian).withFaultHandler(guardianFaultHandlingStrategy)

  private val guardianInChief: ActorRef =
    provider.actorOf(guardianProps, provider.theOneWhoWalksTheBubblesOfSpaceTime, "GuardianInChief", true)

  protected[akka] val guardian: ActorRef =
    provider.actorOf(guardianProps, guardianInChief, "ApplicationSupervisor", true)

  protected[akka] val systemGuardian: ActorRef =
    provider.actorOf(guardianProps.withCreator(new SystemGuardian), guardianInChief, "SystemSupervisor", true)

  // TODO think about memory consistency effects when doing funky stuff inside constructor
  val deadLetters = new DeadLetterActorRef(this)

  val deathWatch = provider.createDeathWatch()

  // chain death watchers so that killing guardian stops the application
  deathWatch.subscribe(systemGuardian, guardian)
  deathWatch.subscribe(guardianInChief, systemGuardian)

  // this starts the reaper actor and the user-configured logging subscribers, which are also actors
  mainbus.start(this)
  mainbus.startDefaultLoggers(this, AkkaConfig)

  // TODO think about memory consistency effects when doing funky stuff inside an ActorRefProvider's constructor
  val deployer = new Deployer(this)

  // TODO think about memory consistency effects when doing funky stuff inside constructor
  val typedActor = new TypedActor(this)

  // TODO think about memory consistency effects when doing funky stuff inside constructor
  val serialization = new Serialization(this)

  // TODO shutdown all that other stuff, whatever that may be
  def stop(): Unit = {
    guardian.stop()
  }

  terminationFuture.onComplete(_ ⇒ dispatcher.shutdown())

}
