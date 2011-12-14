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
 * for different environments. Use the `lookup` or `newFromConfig` method to create
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

  val defaultDispatcherConfig = settings.config.getConfig("akka.actor.default-dispatcher")

  lazy val defaultGlobalDispatcher: MessageDispatcher =
    from(defaultDispatcherConfig) getOrElse {
      throw new ConfigurationException("Wrong configuration [akka.actor.default-dispatcher]")
    }

  // FIXME: Dispatchers registered here are are not removed, see ticket #1494
  private val dispatchers = new ConcurrentHashMap[String, MessageDispatcher]

  /**
   * Returns a dispatcher as specified in configuration, or if not defined it uses
   * the default dispatcher. The same dispatcher instance is returned for subsequent
   * lookups.
   */
  def lookup(key: String): MessageDispatcher = {
    dispatchers.get(key) match {
      case null ⇒
        // It doesn't matter if we create a dispatcher that isn't used due to concurrent lookup.
        // That shouldn't happen often and in case it does the actual ExecutorService isn't 
        // created until used, i.e. cheap.
        val newDispatcher = newFromConfig(key)
        dispatchers.putIfAbsent(key, newDispatcher) match {
          case null     ⇒ newDispatcher
          case existing ⇒ existing
        }
      case existing ⇒ existing
    }
  }

  /**
   * Creates an thread based dispatcher serving a single actor through the same single thread.
   * <p/>
   * E.g. each actor consumes its own thread.
   */
  def newPinnedDispatcher(name: String, mailboxType: MailboxType) =
    new PinnedDispatcher(prerequisites, null, name, mailboxType, settings.DispatcherDefaultShutdown)

  /**
   * Creates an thread based dispatcher serving a single actor through the same single thread.
   * <p/>
   * E.g. each actor consumes its own thread.
   */
  def newPinnedDispatcher(name: String) =
    new PinnedDispatcher(prerequisites, null, name, MailboxType, settings.DispatcherDefaultShutdown)

  /**
   * Creates a executor-based event-driven dispatcher serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newDispatcher(name: String) =
    ThreadPoolConfigDispatcherBuilder(config ⇒ new Dispatcher(prerequisites, name, settings.DispatcherThroughput,
      settings.DispatcherThroughputDeadlineTime, MailboxType, config, settings.DispatcherDefaultShutdown), ThreadPoolConfig())

  /**
   * Creates a executor-based event-driven dispatcher serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newDispatcher(name: String, throughput: Int, mailboxType: MailboxType) =
    ThreadPoolConfigDispatcherBuilder(config ⇒
      new Dispatcher(prerequisites, name, throughput, settings.DispatcherThroughputDeadlineTime, mailboxType,
        config, settings.DispatcherDefaultShutdown), ThreadPoolConfig())

  /**
   * Creates a executor-based event-driven dispatcher serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newDispatcher(name: String, throughput: Int, throughputDeadline: Duration, mailboxType: MailboxType) =
    ThreadPoolConfigDispatcherBuilder(config ⇒
      new Dispatcher(prerequisites, name, throughput, throughputDeadline, mailboxType, config, settings.DispatcherDefaultShutdown), ThreadPoolConfig())

  /**
   * Creates a executor-based event-driven dispatcher, with work-sharing, serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newBalancingDispatcher(name: String) =
    ThreadPoolConfigDispatcherBuilder(config ⇒ new BalancingDispatcher(prerequisites, name, settings.DispatcherThroughput,
      settings.DispatcherThroughputDeadlineTime, MailboxType, config, settings.DispatcherDefaultShutdown), ThreadPoolConfig())

  /**
   * Creates a executor-based event-driven dispatcher, with work-sharing, serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newBalancingDispatcher(name: String, throughput: Int) =
    ThreadPoolConfigDispatcherBuilder(config ⇒
      new BalancingDispatcher(prerequisites, name, throughput, settings.DispatcherThroughputDeadlineTime, MailboxType,
        config, settings.DispatcherDefaultShutdown), ThreadPoolConfig())

  /**
   * Creates a executor-based event-driven dispatcher, with work-sharing, serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newBalancingDispatcher(name: String, throughput: Int, mailboxType: MailboxType) =
    ThreadPoolConfigDispatcherBuilder(config ⇒
      new BalancingDispatcher(prerequisites, name, throughput, settings.DispatcherThroughputDeadlineTime, mailboxType,
        config, settings.DispatcherDefaultShutdown), ThreadPoolConfig())

  /**
   * Creates a executor-based event-driven dispatcher, with work-sharing, serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newBalancingDispatcher(name: String, throughput: Int, throughputDeadline: Duration, mailboxType: MailboxType) =
    ThreadPoolConfigDispatcherBuilder(config ⇒
      new BalancingDispatcher(prerequisites, name, throughput, throughputDeadline, mailboxType,
        config, settings.DispatcherDefaultShutdown), ThreadPoolConfig())

  /**
   * Creates a new dispatcher as specified in configuration
   * or if not defined it uses the supplied dispatcher.
   * Uses default values from default-dispatcher, i.e. all options doesn't need to be defined.
   */
  def newFromConfig(key: String, default: ⇒ MessageDispatcher, cfg: Config): MessageDispatcher = {
    import scala.collection.JavaConverters._
    def simpleName = key.substring(key.lastIndexOf('.') + 1)
    cfg.hasPath(key) match {
      case false ⇒ default
      case true ⇒
        val conf = cfg.getConfig(key)
        val confWithName = conf.withFallback(ConfigFactory.parseMap(Map("name" -> simpleName).asJava))
        from(confWithName).getOrElse(throw new ConfigurationException("Wrong configuration [%s]".format(key)))
    }
  }

  /**
   * Creates a new dispatcher as specified in configuration, or if not defined it uses
   * the default dispatcher.
   * Uses default configuration values from default-dispatcher, i.e. all options doesn't
   * need to be defined.
   */
  def newFromConfig(key: String): MessageDispatcher = newFromConfig(key, defaultGlobalDispatcher, settings.config)

  /*
   * Creates of obtains a dispatcher from a ConfigMap according to the format below.
   * Uses default values from default-dispatcher.
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
   * ex: from(config.getConfig(identifier).get)
   *
   * Gotcha: Only configures the dispatcher if possible
   * Throws: IllegalArgumentException if the value of "type" is not valid
   *         IllegalArgumentException if it cannot create the MessageDispatcherConfigurator
   */
  def from(cfg: Config): Option[MessageDispatcher] = {
    val cfgWithFallback = cfg.withFallback(defaultDispatcherConfig)

    val dispatcherConfigurator = cfgWithFallback.getString("type") match {
      case "Dispatcher"          ⇒ Some(new DispatcherConfigurator())
      case "BalancingDispatcher" ⇒ Some(new BalancingDispatcherConfigurator())
      case fqn ⇒
        ReflectiveAccess.getClassFor[MessageDispatcherConfigurator](fqn) match {
          case Right(clazz) ⇒
            ReflectiveAccess.createInstance[MessageDispatcherConfigurator](clazz, Array[Class[_]](), Array[AnyRef]()) match {
              case Right(configurator) ⇒ Some(configurator)
              case Left(exception) ⇒
                throw new IllegalArgumentException(
                  "Cannot instantiate MessageDispatcherConfigurator type [%s], make sure it has a default no-args constructor" format fqn, exception)
            }
          case Left(exception) ⇒
            throw new IllegalArgumentException("Unknown MessageDispatcherConfigurator type [%s]" format fqn, exception)
        }
    }

    dispatcherConfigurator map (_.configure(cfgWithFallback, settings, prerequisites))
  }
}

class DispatcherConfigurator() extends MessageDispatcherConfigurator() {
  def configure(config: Config, settings: Settings, prerequisites: DispatcherPrerequisites): MessageDispatcher = {
    configureThreadPool(config,
      settings,
      threadPoolConfig ⇒ new Dispatcher(prerequisites,
        config.getString("name"),
        config.getInt("throughput"),
        Duration(config.getNanoseconds("throughput-deadline-time"), TimeUnit.NANOSECONDS),
        mailboxType(config, settings),
        threadPoolConfig,
        settings.DispatcherDefaultShutdown)).build
  }
}

class BalancingDispatcherConfigurator() extends MessageDispatcherConfigurator() {
  def configure(config: Config, settings: Settings, prerequisites: DispatcherPrerequisites): MessageDispatcher = {
    configureThreadPool(config,
      settings,
      threadPoolConfig ⇒ new BalancingDispatcher(prerequisites,
        config.getString("name"),
        config.getInt("throughput"),
        Duration(config.getNanoseconds("throughput-deadline-time"), TimeUnit.NANOSECONDS),
        mailboxType(config, settings),
        threadPoolConfig,
        settings.DispatcherDefaultShutdown)).build
  }
}
