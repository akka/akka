/**
 *   Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import akka.actor.LocalActorRef
import akka.actor.newUuid
import akka.util.{ Duration, ReflectiveAccess }
import java.util.concurrent.TimeUnit
import akka.actor.ActorSystem
import akka.event.EventStream
import akka.actor.Scheduler
import akka.actor.ActorSystem.Settings
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

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
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class Dispatchers(val settings: ActorSystem.Settings, val prerequisites: DispatcherPrerequisites) {

  val MailboxType: MailboxType =
    if (settings.MailboxCapacity < 1) UnboundedMailbox()
    else BoundedMailbox(settings.MailboxCapacity, settings.MailboxPushTimeout)

  val defaultDispatcherConfig = settings.config.getConfig("akka.actor.default-dispatcher")

  // TODO PN Shouldn't we fail hard if default-dispatcher is wrong?
  lazy val defaultGlobalDispatcher =
    from(defaultDispatcherConfig) getOrElse newDispatcher("AkkaDefaultGlobalDispatcher", 1, MailboxType).build

  /**
   * Creates an thread based dispatcher serving a single actor through the same single thread.
   * Uses the default timeout
   * <p/>
   * E.g. each actor consumes its own thread.
   */
  def newPinnedDispatcher(actor: LocalActorRef) = actor match {
    case null ⇒ new PinnedDispatcher(prerequisites, null, "anon", MailboxType, settings.DispatcherDefaultShutdown)
    case some ⇒ new PinnedDispatcher(prerequisites, some.underlying, some.address, MailboxType, settings.DispatcherDefaultShutdown)
  }

  /**
   * Creates an thread based dispatcher serving a single actor through the same single thread.
   * If capacity is negative, it's Integer.MAX_VALUE
   * <p/>
   * E.g. each actor consumes its own thread.
   */
  def newPinnedDispatcher(actor: LocalActorRef, mailboxType: MailboxType) = actor match {
    case null ⇒ new PinnedDispatcher(prerequisites, null, "anon", mailboxType, settings.DispatcherDefaultShutdown)
    case some ⇒ new PinnedDispatcher(prerequisites, some.underlying, some.address, mailboxType, settings.DispatcherDefaultShutdown)
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
   * Creates a executor-based event-driven dispatcher, with work-stealing, serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newBalancingDispatcher(name: String) =
    ThreadPoolConfigDispatcherBuilder(config ⇒ new BalancingDispatcher(prerequisites, name, settings.DispatcherThroughput,
      settings.DispatcherThroughputDeadlineTime, MailboxType, config, settings.DispatcherDefaultShutdown), ThreadPoolConfig())

  /**
   * Creates a executor-based event-driven dispatcher, with work-stealing, serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newBalancingDispatcher(name: String, throughput: Int) =
    ThreadPoolConfigDispatcherBuilder(config ⇒
      new BalancingDispatcher(prerequisites, name, throughput, settings.DispatcherThroughputDeadlineTime, MailboxType,
        config, settings.DispatcherDefaultShutdown), ThreadPoolConfig())

  /**
   * Creates a executor-based event-driven dispatcher, with work-stealing, serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newBalancingDispatcher(name: String, throughput: Int, mailboxType: MailboxType) =
    ThreadPoolConfigDispatcherBuilder(config ⇒
      new BalancingDispatcher(prerequisites, name, throughput, settings.DispatcherThroughputDeadlineTime, mailboxType,
        config, settings.DispatcherDefaultShutdown), ThreadPoolConfig())

  /**
   * Creates a executor-based event-driven dispatcher, with work-stealing, serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newBalancingDispatcher(name: String, throughput: Int, throughputDeadline: Duration, mailboxType: MailboxType) =
    ThreadPoolConfigDispatcherBuilder(config ⇒
      new BalancingDispatcher(prerequisites, name, throughput, throughputDeadline, mailboxType,
        config, settings.DispatcherDefaultShutdown), ThreadPoolConfig())
  /**
   * Utility function that tries to load the specified dispatcher config from the akka.conf
   * or if not defined it uses the supplied dispatcher.
   * Uses default values from default-dispatcher, i.e. all options doesn't need to be defined
   * in config.
   */
  def fromConfig(key: String, default: ⇒ MessageDispatcher = defaultGlobalDispatcher, cfg: Config = settings.config): MessageDispatcher = {
    import scala.collection.JavaConverters._
    def simpleName = key.substring(key.lastIndexOf('.') + 1)
    cfg.hasPath(key) match {
      case false ⇒ default
      case true ⇒
        val conf = cfg.getConfig(key)
        val confWithName = conf.withFallback(ConfigFactory.parseMap(Map("name" -> simpleName).asJava))
        from(confWithName).getOrElse(default)
    }
  }

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
