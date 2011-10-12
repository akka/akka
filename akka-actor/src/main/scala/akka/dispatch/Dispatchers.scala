/**
 *   Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import akka.actor.LocalActorRef
import akka.actor.newUuid
import akka.util.{ Duration, ReflectiveAccess }
import akka.config.Configuration
import java.util.concurrent.TimeUnit
import akka.AkkaApplication

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
 *     .setKeepAliveTimeInMillis(60000)
 *     .setRejectionPolicy(new CallerRunsPolicy)
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
 *     .setKeepAliveTimeInMillis(60000)
 *     .setRejectionPolicy(new CallerRunsPolicy())
 *     .build();
 * </pre>
 * <p/>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class Dispatchers(val application: AkkaApplication) {
  val ThroughputDeadlineTimeMillis = application.AkkaConfig.DispatcherThroughputDeadlineTime.toMillis.toInt
  val MailboxType: MailboxType =
    if (application.AkkaConfig.MailboxCapacity < 1) UnboundedMailbox()
    else BoundedMailbox(application.AkkaConfig.MailboxCapacity, application.AkkaConfig.MailboxPushTimeout)
  val DispatcherShutdownMillis = application.AkkaConfig.DispatcherDefaultShutdown.toMillis

  lazy val defaultGlobalDispatcher =
    application.config.getSection("akka.actor.default-dispatcher").flatMap(from) getOrElse newDispatcher("AkkaDefaultGlobalDispatcher", 1, MailboxType).build

  /**
   * Creates an thread based dispatcher serving a single actor through the same single thread.
   * Uses the default timeout
   * <p/>
   * E.g. each actor consumes its own thread.
   */
  def newPinnedDispatcher(actor: LocalActorRef) = actor match {
    case null ⇒ new PinnedDispatcher(null, "anon", MailboxType, DispatcherShutdownMillis)
    case some ⇒ new PinnedDispatcher(some.underlying, some.underlying.uuid.toString, MailboxType, DispatcherShutdownMillis)
  }

  /**
   * Creates an thread based dispatcher serving a single actor through the same single thread.
   * If capacity is negative, it's Integer.MAX_VALUE
   * <p/>
   * E.g. each actor consumes its own thread.
   */
  def newPinnedDispatcher(actor: LocalActorRef, mailboxType: MailboxType) = actor match {
    case null ⇒ new PinnedDispatcher(null, "anon", mailboxType, DispatcherShutdownMillis)
    case some ⇒ new PinnedDispatcher(some.underlying, some.underlying.uuid.toString, mailboxType, DispatcherShutdownMillis)
  }

  /**
   * Creates an thread based dispatcher serving a single actor through the same single thread.
   * <p/>
   * E.g. each actor consumes its own thread.
   */
  def newPinnedDispatcher(name: String, mailboxType: MailboxType) =
    new PinnedDispatcher(null, name, mailboxType, DispatcherShutdownMillis)

  /**
   * Creates an thread based dispatcher serving a single actor through the same single thread.
   * <p/>
   * E.g. each actor consumes its own thread.
   */
  def newPinnedDispatcher(name: String) =
    new PinnedDispatcher(null, name, MailboxType, DispatcherShutdownMillis)

  /**
   * Creates a executor-based event-driven dispatcher serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newDispatcher(name: String) =
    ThreadPoolConfigDispatcherBuilder(config ⇒ new Dispatcher(name, application.AkkaConfig.DispatcherThroughput,
      ThroughputDeadlineTimeMillis, MailboxType, config, DispatcherShutdownMillis), ThreadPoolConfig())

  /**
   * Creates a executor-based event-driven dispatcher serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newDispatcher(name: String, throughput: Int, mailboxType: MailboxType) =
    ThreadPoolConfigDispatcherBuilder(config ⇒
      new Dispatcher(name, throughput, ThroughputDeadlineTimeMillis, mailboxType, config, DispatcherShutdownMillis), ThreadPoolConfig())

  /**
   * Creates a executor-based event-driven dispatcher serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newDispatcher(name: String, throughput: Int, throughputDeadlineMs: Int, mailboxType: MailboxType) =
    ThreadPoolConfigDispatcherBuilder(config ⇒
      new Dispatcher(name, throughput, throughputDeadlineMs, mailboxType, config, DispatcherShutdownMillis), ThreadPoolConfig())

  /**
   * Creates a executor-based event-driven dispatcher, with work-stealing, serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newBalancingDispatcher(name: String) =
    ThreadPoolConfigDispatcherBuilder(config ⇒ new BalancingDispatcher(name, application.AkkaConfig.DispatcherThroughput,
      ThroughputDeadlineTimeMillis, MailboxType, config, DispatcherShutdownMillis), ThreadPoolConfig())

  /**
   * Creates a executor-based event-driven dispatcher, with work-stealing, serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newBalancingDispatcher(name: String, throughput: Int) =
    ThreadPoolConfigDispatcherBuilder(config ⇒
      new BalancingDispatcher(name, throughput, ThroughputDeadlineTimeMillis, MailboxType, config, DispatcherShutdownMillis), ThreadPoolConfig())

  /**
   * Creates a executor-based event-driven dispatcher, with work-stealing, serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newBalancingDispatcher(name: String, throughput: Int, mailboxType: MailboxType) =
    ThreadPoolConfigDispatcherBuilder(config ⇒
      new BalancingDispatcher(name, throughput, ThroughputDeadlineTimeMillis, mailboxType, config, DispatcherShutdownMillis), ThreadPoolConfig())

  /**
   * Creates a executor-based event-driven dispatcher, with work-stealing, serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newBalancingDispatcher(name: String, throughput: Int, throughputDeadlineMs: Int, mailboxType: MailboxType) =
    ThreadPoolConfigDispatcherBuilder(config ⇒
      new BalancingDispatcher(name, throughput, throughputDeadlineMs, mailboxType, config, DispatcherShutdownMillis), ThreadPoolConfig())
  /**
   * Utility function that tries to load the specified dispatcher config from the akka.conf
   * or else use the supplied default dispatcher
   */
  def fromConfig(key: String, default: ⇒ MessageDispatcher = defaultGlobalDispatcher): MessageDispatcher =
    application.config getSection key flatMap from getOrElse default

  /*
   * Creates of obtains a dispatcher from a ConfigMap according to the format below
   *
   * default-dispatcher {
   *   type = "Dispatcher"         # Must be one of the following
   *                               # Dispatcher, (BalancingDispatcher, only valid when all actors using it are of the same type),
   *                               # A FQCN to a class inheriting MessageDispatcherConfigurator with a no-arg visible constructor
   *   name = "MyDispatcher"       # Optional, will be a generated UUID if omitted
   *   keep-alive-time = 60        # Keep alive time for threads in akka.time-unit
   *   core-pool-size-factor = 1.0 # No of core threads ... ceil(available processors * factor)
   *   max-pool-size-factor  = 4.0 # Max no of threads ... ceil(available processors * factor)
   *   executor-bounds = -1        # Makes the Executor bounded, -1 is unbounded
   *   allow-core-timeout = on     # Allow core threads to time out
   *   rejection-policy = "caller-runs" # abort, caller-runs, discard-oldest, discard
   *   throughput = 5              # Throughput for Dispatcher
   * }
   * ex: from(config.getConfigMap(identifier).get)
   *
   * Gotcha: Only configures the dispatcher if possible
   * Returns: None if "type" isn't specified in the config
   * Throws: IllegalArgumentException if the value of "type" is not valid
   *         IllegalArgumentException if it cannot create the MessageDispatcherConfigurator
   */
  def from(cfg: Configuration): Option[MessageDispatcher] = {
    cfg.getString("type") flatMap {
      case "Dispatcher"          ⇒ Some(new DispatcherConfigurator(application))
      case "BalancingDispatcher" ⇒ Some(new BalancingDispatcherConfigurator(application))
      case "GlobalDispatcher"    ⇒ None //TODO FIXME remove this
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
    } map {
      _ configure cfg
    }
  }
}

class DispatcherConfigurator(application: AkkaApplication) extends MessageDispatcherConfigurator(application) {
  def configure(config: Configuration): MessageDispatcher = {
    configureThreadPool(config, threadPoolConfig ⇒ new Dispatcher(
      config.getString("name", newUuid.toString),
      config.getInt("throughput", application.AkkaConfig.DispatcherThroughput),
      config.getInt("throughput-deadline-time", application.AkkaConfig.DispatcherThroughputDeadlineTime.toMillis.toInt),
      mailboxType(config),
      threadPoolConfig,
      application.AkkaConfig.DispatcherDefaultShutdown.toMillis)).build
  }
}

class BalancingDispatcherConfigurator(application: AkkaApplication) extends MessageDispatcherConfigurator(application) {
  def configure(config: Configuration): MessageDispatcher = {
    configureThreadPool(config, threadPoolConfig ⇒ new BalancingDispatcher(
      config.getString("name", newUuid.toString),
      config.getInt("throughput", application.AkkaConfig.DispatcherThroughput),
      config.getInt("throughput-deadline-time", application.AkkaConfig.DispatcherThroughputDeadlineTime.toMillis.toInt),
      mailboxType(config),
      threadPoolConfig,
      application.AkkaConfig.DispatcherDefaultShutdown.toMillis)).build
  }
}
