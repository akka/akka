/**
 *   Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import akka.actor.LocalActorRef
import akka.actor.newUuid
import akka.util.{ Duration, ReflectiveAccess }
import akka.actor.ActorSystem
import akka.event.EventStream
import akka.actor.Scheduler
import akka.actor.ActorSystem.Settings
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import akka.config.ConfigurationException
import akka.event.Logging
import akka.event.Logging.Debug

trait DispatcherPrerequisites {
  def eventStream: EventStream
  def deadLetterMailbox: Mailbox
  def scheduler: Scheduler
}

case class DefaultDispatcherPrerequisites(
  val eventStream: EventStream,
  val deadLetterMailbox: Mailbox,
  val scheduler: Scheduler) extends DispatcherPrerequisites

/**
 * It is recommended to define the dispatcher in configuration to allow for tuning
 * for different environments. Use the `lookup` method to create
 * a dispatcher as specified in configuration.
 *
 * Scala API. Dispatcher factory.
 * <p/>
 * Example usage:
 * <pre/>
 *   val dispatcher = Dispatchers.newDispatcher("name")
 *   dispatcher
 *     .withNewThreadPoolWithLinkedBlockingQueueWithCapacity(100)
 *     .setCorePoolSize(16)
 *     .setMaxPoolSize(128)
 *     .setKeepAliveTime(60 seconds)
 *     .build
 * </pre>
 * <p/>
 * Java API. Dispatcher factory.
 * <p/>
 * Example usage:
 * <pre/>
 *   MessageDispatcher dispatcher = Dispatchers.newDispatcher("name");
 *   dispatcher
 *     .withNewThreadPoolWithLinkedBlockingQueueWithCapacity(100)
 *     .setCorePoolSize(16)
 *     .setMaxPoolSize(128)
 *     .setKeepAliveTime(60 seconds)
 *     .build();
 * </pre>
 * <p/>
 */
class Dispatchers(val settings: ActorSystem.Settings, val prerequisites: DispatcherPrerequisites) {

  val MailboxType: MailboxType =
    if (settings.MailboxCapacity < 1) UnboundedMailbox()
    else BoundedMailbox(settings.MailboxCapacity, settings.MailboxPushTimeout)

  val defaultDispatcherConfig = {
    val key = "akka.actor.default-dispatcher"
    keyConfig(key).withFallback(settings.config.getConfig(key))
  }

  private lazy val defaultDispatcherConfigurator: MessageDispatcherConfigurator =
    configuratorFrom(defaultDispatcherConfig)

  lazy val defaultGlobalDispatcher: MessageDispatcher = defaultDispatcherConfigurator.dispatcher()

  // FIXME: Dispatchers registered here are are not removed, see ticket #1494
  private val dispatcherConfigurators = new ConcurrentHashMap[String, MessageDispatcherConfigurator]

  /**
   * Returns a dispatcher as specified in configuration, or if not defined it uses
   * the default dispatcher. The same dispatcher instance is returned for subsequent
   * lookups.
   */
  def lookup(key: String): MessageDispatcher = {
    val configurator = dispatcherConfigurators.get(key) match {
      case null ⇒
        // It doesn't matter if we create a dispatcher configurator that isn't used due to concurrent lookup.
        // That shouldn't happen often and in case it does the actual dispatcher isn't
        // created until used, i.e. cheap.
        val newConfigurator =
          if (settings.config.hasPath(key)) {
            configuratorFrom(config(key))
          } else {
            // FIXME Remove println
            println("#### Dispatcher [%s] not configured, using default-dispatcher".format(key))
            prerequisites.eventStream.publish(Debug("Dispatchers",
              "Dispatcher [%s] not configured, using default-dispatcher".format(key)))
            defaultDispatcherConfigurator
          }

        dispatcherConfigurators.putIfAbsent(key, newConfigurator) match {
          case null     ⇒ newConfigurator
          case existing ⇒ existing
        }

      case existing ⇒ existing
    }
    configurator.dispatcher()
  }

  // FIXME #1458: Not sure if we should have this, but needed it temporary for PriorityDispatcherSpec, ActorModelSpec and DispatcherDocSpec
  def register(key: String, dispatcherConfigurator: MessageDispatcherConfigurator): Unit = {
    dispatcherConfigurators.putIfAbsent(key, dispatcherConfigurator)
  }

  private def config(key: String): Config = {
    import scala.collection.JavaConverters._
    def simpleName = key.substring(key.lastIndexOf('.') + 1)
    keyConfig(key)
      .withFallback(settings.config.getConfig(key))
      .withFallback(ConfigFactory.parseMap(Map("name" -> simpleName).asJava))
      .withFallback(defaultDispatcherConfig)
  }

  private def keyConfig(key: String): Config = {
    import scala.collection.JavaConverters._
    ConfigFactory.parseMap(Map("key" -> key).asJava)
  }

  // FIXME #1458: Remove these newDispatcher methods, but still need them temporary for PriorityDispatcherSpec, ActorModelSpec and DispatcherDocSpec
  /**
   * Creates a executor-based event-driven dispatcher serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newDispatcher(name: String) =
    ThreadPoolConfigDispatcherBuilder(config ⇒ new Dispatcher(prerequisites, name, name, settings.DispatcherThroughput,
      settings.DispatcherThroughputDeadlineTime, MailboxType, config, settings.DispatcherDefaultShutdown), ThreadPoolConfig())

  /**
   * Creates a executor-based event-driven dispatcher serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newDispatcher(name: String, throughput: Int, mailboxType: MailboxType) =
    ThreadPoolConfigDispatcherBuilder(config ⇒
      new Dispatcher(prerequisites, name, name, throughput, settings.DispatcherThroughputDeadlineTime, mailboxType,
        config, settings.DispatcherDefaultShutdown), ThreadPoolConfig())

  /**
   * Creates a executor-based event-driven dispatcher serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newDispatcher(name: String, throughput: Int, throughputDeadline: Duration, mailboxType: MailboxType) =
    ThreadPoolConfigDispatcherBuilder(config ⇒
      new Dispatcher(prerequisites, name, name, throughput, throughputDeadline, mailboxType, config, settings.DispatcherDefaultShutdown), ThreadPoolConfig())

  /*
   * Creates of obtains a dispatcher from a Config according to the format below.
   *
   * my-dispatcher {
   *   type = "Dispatcher"         # Must be one of the following
   *                               # Dispatcher, (BalancingDispatcher, only valid when all actors using it are of the same type),
   *                               # A FQCN to a class inheriting MessageDispatcherConfigurator with a no-arg visible constructor
   *   name = "MyDispatcher"       # Optional, will be a generated UUID if omitted
   *   keep-alive-time = 60        # Keep alive time for threads in akka.time-unit
   *   core-pool-size-factor = 1.0 # No of core threads ... ceil(available processors * factor)
   *   max-pool-size-factor  = 4.0 # Max no of threads ... ceil(available processors * factor)
   *   allow-core-timeout = on     # Allow core threads to time out
   *   throughput = 5              # Throughput for Dispatcher
   * }
   * ex: from(config.getConfig(key))
   *
   * The Config must also contain a `key` property, which is the identifying key of the dispatcher.
   *
   * Throws: IllegalArgumentException if the value of "type" is not valid
   *         IllegalArgumentException if it cannot create the MessageDispatcherConfigurator
   */
  private[akka] def from(cfg: Config): MessageDispatcher = {
    configuratorFrom(cfg).dispatcher()
  }

  private def configuratorFrom(cfg: Config): MessageDispatcherConfigurator = {
    if (!cfg.hasPath("key")) throw new IllegalArgumentException("Missing dispatcher 'key' property in config: " + cfg.root.render)

    cfg.getString("type") match {
      case "Dispatcher"          ⇒ new DispatcherConfigurator(cfg, prerequisites)
      case "BalancingDispatcher" ⇒ new BalancingDispatcherConfigurator(cfg, prerequisites)
      case "PinnedDispatcher"    ⇒ new PinnedDispatcherConfigurator(cfg, prerequisites)
      case fqn ⇒
        val constructorSignature = Array[Class[_]](classOf[Config], classOf[DispatcherPrerequisites])
        ReflectiveAccess.createInstance[MessageDispatcherConfigurator](fqn, constructorSignature, Array[AnyRef](cfg, prerequisites)) match {
          case Right(configurator) ⇒ configurator
          case Left(exception) ⇒
            throw new IllegalArgumentException(
              ("Cannot instantiate MessageDispatcherConfigurator type [%s], defined in [%s], " +
                "make sure it has constructor with [com.typesafe.config.Config] and " +
                "[akka.dispatch.DispatcherPrerequisites] parameters")
                .format(fqn, cfg.getString("key")), exception)
        }
    }
  }
}

class DispatcherConfigurator(config: Config, prerequisites: DispatcherPrerequisites)
  extends MessageDispatcherConfigurator(config, prerequisites) {

  private val instance =
    configureThreadPool(config,
      threadPoolConfig ⇒ new Dispatcher(prerequisites,
        config.getString("name"),
        config.getString("key"),
        config.getInt("throughput"),
        Duration(config.getNanoseconds("throughput-deadline-time"), TimeUnit.NANOSECONDS),
        mailboxType,
        threadPoolConfig,
        Duration(config.getMilliseconds("shutdown-timeout"), TimeUnit.MILLISECONDS))).build

  /**
   * Returns the same dispatcher instance for each invocation
   */
  override def dispatcher(): MessageDispatcher = instance
}

class BalancingDispatcherConfigurator(config: Config, prerequisites: DispatcherPrerequisites)
  extends MessageDispatcherConfigurator(config, prerequisites) {

  private val instance =
    configureThreadPool(config,
      threadPoolConfig ⇒ new BalancingDispatcher(prerequisites,
        config.getString("name"),
        config.getString("key"),
        config.getInt("throughput"),
        Duration(config.getNanoseconds("throughput-deadline-time"), TimeUnit.NANOSECONDS),
        mailboxType,
        threadPoolConfig,
        Duration(config.getMilliseconds("shutdown-timeout"), TimeUnit.MILLISECONDS))).build

  /**
   * Returns the same dispatcher instance for each invocation
   */
  override def dispatcher(): MessageDispatcher = instance
}

class PinnedDispatcherConfigurator(config: Config, prerequisites: DispatcherPrerequisites)
  extends MessageDispatcherConfigurator(config, prerequisites) {
  /**
   * Creates new dispatcher for each invocation.
   */
  override def dispatcher(): MessageDispatcher =
    new PinnedDispatcher(prerequisites, null, config.getString("name"), config.getString("key"), mailboxType,
      Duration(config.getMilliseconds("shutdown-timeout"), TimeUnit.MILLISECONDS))

}
