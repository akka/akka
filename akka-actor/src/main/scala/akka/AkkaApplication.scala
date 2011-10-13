/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka

import akka.config._
import akka.actor._
import java.net.InetAddress
import com.eaio.uuid.UUID
import dispatch.{ Dispatcher, Dispatchers }
import akka.util.Duration
import util.ReflectiveAccess
import java.util.concurrent.TimeUnit
import akka.dispatch.BoundedMailbox
import akka.dispatch.UnboundedMailbox
import akka.routing.Routing
import remote.RemoteSupport
import akka.serialization.Serialization
import akka.event.EventHandler
import akka.event.EventHandlerLogging
import akka.event.Logging

object AkkaApplication {

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

}

class AkkaApplication(val name: String, val config: Configuration) extends ActorRefFactory {

  def this(name: String) = this(name, AkkaApplication.defaultConfig)
  def this() = this("default")

  import AkkaApplication._

  object AkkaConfig {
    import config._
    val ConfigVersion = getString("akka.version", Version)
    val DefaultTimeUnit = getString("akka.time-unit", "seconds")
    val ActorTimeout = Timeout(Duration(getInt("akka.actor.timeout", 5), DefaultTimeUnit))
    val ActorTimeoutMillis = ActorTimeout.duration.toMillis
    val SerializeAllMessages = getBool("akka.actor.serialize-messages", false)

    val LogLevel = getString("akka.event-handler-level", "INFO")
    val EventHandlers = getList("akka.event-handlers")
    val AddLoggingReceive = getBool("akka.actor.debug.receive", false)
    val DebugAutoReceive = getBool("akka.actor.debug.autoreceive", false)
    val DebugLifecycle = getBool("akka.actor.debug.lifecycle", false)
    val FsmDebugEvent = getBool("akka.actor.debug.fsm", false)

    val DispatcherThroughput = getInt("akka.actor.throughput", 5)
    val DispatcherDefaultShutdown = getLong("akka.actor.dispatcher-shutdown-timeout").
      map(time ⇒ Duration(time, DefaultTimeUnit)).
      getOrElse(Duration(1000, TimeUnit.MILLISECONDS))
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

  import AkkaConfig._

  if (ConfigVersion != Version)
    throw new ConfigurationException("Akka JAR version [" + Version +
      "] does not match the provided config version [" + ConfigVersion + "]")

  val eventHandler = new EventHandler(this)

  val log: Logging = new EventHandlerLogging(eventHandler, this)

  val startTime = System.currentTimeMillis
  def uptime = (System.currentTimeMillis - startTime) / 1000

  val nodename = System.getProperty("akka.cluster.nodename") match {
    case null | "" ⇒ new UUID().toString
    case value     ⇒ value
  }

  val hostname = System.getProperty("akka.remote.hostname") match {
    case null | "" ⇒ InetAddress.getLocalHost.getHostName
    case value     ⇒ value
  }

  // TODO correctly pull its config from the config
  val dispatcherFactory = new Dispatchers(this)

  implicit val dispatcher = dispatcherFactory.defaultGlobalDispatcher

  // TODO think about memory consistency effects when doing funky stuff inside an ActorRefProvider's constructor
  val deployer = new Deployer(this)

  // TODO think about memory consistency effects when doing funky stuff inside an ActorRefProvider's constructor
  val provider: ActorRefProvider = new LocalActorRefProvider(this, deployer)

  /**
   * Handle to the ActorRegistry.
   * TODO: delete me!
   */
  val registry = new ActorRegistry

  // TODO check memory consistency issues
  val reflective = new ReflectiveAccess(this)

  val routing = new Routing(this)

  val remote = reflective.RemoteModule.defaultRemoteSupport map (_.apply) getOrElse null

  val typedActor = new TypedActor(this)

  val serialization = new Serialization(this)

}
