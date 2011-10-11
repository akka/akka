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

object AkkaApplication {

  val VERSION = "2.0-SNAPSHOT"

  val envHome = System.getenv("AKKA_HOME") match {
    case null | "" | "." ⇒ None
    case value           ⇒ Some(value)
  }

  val systemHome = System.getProperty("akka.home") match {
    case null | "" ⇒ None
    case value     ⇒ Some(value)
  }

  val GLOBAL_HOME = systemHome orElse envHome

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
    Some(Configuration.fromFile(GLOBAL_HOME.get + "/config/" + defaultLocation))
  } catch { case _ ⇒ None }

  val emptyConfig = Configuration.fromString("akka { version = \"" + VERSION + "\" }")

  val defaultConfig = fromProperties orElse fromClasspath orElse fromHome getOrElse emptyConfig

  def apply(name: String): AkkaApplication = new AkkaApplication(name)

  def apply(): AkkaApplication = new AkkaApplication()

}

class AkkaApplication(val name: String, val config: Configuration) extends ActorRefFactory {

  def this(name: String) = this(name, AkkaApplication.defaultConfig)
  def this() = this("default")

  import AkkaApplication._

  object AkkaConfig {
    import config._
    val CONFIG_VERSION = getString("akka.version", VERSION)
    val TIME_UNIT = getString("akka.time-unit", "seconds")
    val TIMEOUT = Timeout(Duration(getInt("akka.actor.timeout", 5), TIME_UNIT))
    val TimeoutMillis = TIMEOUT.duration.toMillis
    val SERIALIZE_MESSAGES = getBool("akka.actor.serialize-messages", false)

    val LogLevel = getString("akka.event-handler-level", "INFO")
    val EventHandlers = getList("akka.event-handlers")
    val ADD_LOGGING_RECEIVE = getBool("akka.actor.debug.receive", false)
    val DEBUG_AUTO_RECEIVE = getBool("akka.actor.debug.autoreceive", false)
    val DEBUG_LIFECYCLE = getBool("akka.actor.debug.lifecycle", false)
    val FsmDebugEvent = getBool("akka.actor.debug.fsm", false)

    val DispatcherThroughput = getInt("akka.actor.throughput", 5)
    val DispatcherDefaultShutdown = getLong("akka.actor.dispatcher-shutdown-timeout").
      map(time ⇒ Duration(time, TIME_UNIT)).
      getOrElse(Duration(1000, TimeUnit.MILLISECONDS))
    val MailboxCapacity = getInt("akka.actor.default-dispatcher.mailbox-capacity", -1)
    val MailboxPushTimeout = Duration(getInt("akka.actor.default-dispatcher.mailbox-push-timeout-time", 10), TIME_UNIT)
    val ThroughputDeadlineTime = Duration(getInt("akka.actor.throughput-deadline-time", -1), TIME_UNIT)

    val HOME = getString("akka.home")
    val BOOT_CLASSES = getList("akka.boot")

    val ENABLED_MODULES = getList("akka.enabled-modules")
    val CLUSTER_ENABLED = ENABLED_MODULES exists (_ == "cluster")
    val ClusterName = getString("akka.cluster.name", "default")

    val REMOTE_TRANSPORT = getString("akka.remote.layer", "akka.remote.netty.NettyRemoteSupport")
    val REMOTE_SERVER_PORT = getInt("akka.remote.server.port", 2552)
  }

  // Java API
  val akkaConfig = AkkaConfig

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

  if (CONFIG_VERSION != VERSION)
    throw new ConfigurationException("Akka JAR version [" + VERSION +
      "] does not match the provided config version [" + CONFIG_VERSION + "]")

  // TODO correctly pull its config from the config
  val dispatcherFactory = new Dispatchers(this)

  implicit val dispatcher = dispatcherFactory.defaultGlobalDispatcher

  // TODO think about memory consistency effects when doing funky stuff inside an ActorRefProvider's constructor
  val deployer = new Deployer(this)
  val deployment = new DeploymentConfig(this)

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
