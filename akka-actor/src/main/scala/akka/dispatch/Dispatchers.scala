/**
 *   Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.dispatch

import akka.actor.{Actor, ActorRef}
import akka.actor.newUuid
import akka.config.Config._
import akka.util.{Duration, Logging}

import net.lag.configgy.ConfigMap

import java.util.concurrent.ThreadPoolExecutor.{AbortPolicy, CallerRunsPolicy, DiscardOldestPolicy, DiscardPolicy}
import java.util.concurrent.TimeUnit

/**
 * Scala API. Dispatcher factory.
 * <p/>
 * Example usage:
 * <pre/>
 *   val dispatcher = Dispatchers.newExecutorBasedEventDrivenDispatcher("name")
 *   dispatcher
 *     .withNewThreadPoolWithBoundedBlockingQueue(100)
 *     .setCorePoolSize(16)
 *     .setMaxPoolSize(128)
 *     .setKeepAliveTimeInMillis(60000)
 *     .setRejectionPolicy(new CallerRunsPolicy)
 *     .buildThreadPool
 * </pre>
 * <p/>
 * Java API. Dispatcher factory.
 * <p/>
 * Example usage:
 * <pre/>
 *   MessageDispatcher dispatcher = Dispatchers.newExecutorBasedEventDrivenDispatcher("name");
 *   dispatcher
 *     .withNewThreadPoolWithBoundedBlockingQueue(100)
 *     .setCorePoolSize(16)
 *     .setMaxPoolSize(128)
 *     .setKeepAliveTimeInMillis(60000)
 *     .setRejectionPolicy(new CallerRunsPolicy)
 *     .buildThreadPool();
 * </pre>
 * <p/>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Dispatchers extends Logging {
  val THROUGHPUT                      = config.getInt("akka.actor.throughput", 5)
  val DEFAULT_SHUTDOWN_TIMEOUT        = config.getLong("akka.actor.dispatcher-shutdown-timeout").
                                        map(time => Duration(time, TIME_UNIT)).
                                        getOrElse(Duration(1000,TimeUnit.MILLISECONDS))
  val MAILBOX_CAPACITY                = config.getInt("akka.actor.default-dispatcher.mailbox-capacity", -1)
  val MAILBOX_PUSH_TIME_OUT           = Duration(config.getInt("akka.actor.default-dispatcher.mailbox-push-timeout-time", 10), TIME_UNIT)
  val THROUGHPUT_DEADLINE_TIME        = Duration(config.getInt("akka.actor.throughput-deadline-time",-1), TIME_UNIT)
  val THROUGHPUT_DEADLINE_TIME_MILLIS = THROUGHPUT_DEADLINE_TIME.toMillis.toInt
  val MAILBOX_TYPE: MailboxType       = if (MAILBOX_CAPACITY < 0) UnboundedMailbox() else BoundedMailbox()

  lazy val defaultGlobalDispatcher = {
    config.getConfigMap("akka.actor.default-dispatcher").flatMap(from).getOrElse(globalExecutorBasedEventDrivenDispatcher)
  }

  object globalHawtDispatcher extends HawtDispatcher

  object globalExecutorBasedEventDrivenDispatcher extends ExecutorBasedEventDrivenDispatcher("global", THROUGHPUT, THROUGHPUT_DEADLINE_TIME_MILLIS, MAILBOX_TYPE)

  /**
   * Creates an event-driven dispatcher based on the excellent HawtDispatch library.
   * <p/>
   * Can be beneficial to use the <code>HawtDispatcher.pin(self)</code> to "pin" an actor to a specific thread.
   * <p/>
   * See the ScalaDoc for the {@link akka.dispatch.HawtDispatcher} for details.
   */
  def newHawtDispatcher(aggregate: Boolean) = new HawtDispatcher(aggregate)

  /**
   * Creates an thread based dispatcher serving a single actor through the same single thread.
   * Uses the default timeout
   * <p/>
   * E.g. each actor consumes its own thread.
   */
  def newThreadBasedDispatcher(actor: ActorRef) = new ThreadBasedDispatcher(actor)

  /**
   * Creates an thread based dispatcher serving a single actor through the same single thread.
   * Uses the default timeout
   * If capacity is negative, it's Integer.MAX_VALUE
   * <p/>
   * E.g. each actor consumes its own thread.
   */
  def newThreadBasedDispatcher(actor: ActorRef, mailboxCapacity: Int) = new ThreadBasedDispatcher(actor, mailboxCapacity)

  /**
   * Creates an thread based dispatcher serving a single actor through the same single thread.
   * If capacity is negative, it's Integer.MAX_VALUE
   * <p/>
   * E.g. each actor consumes its own thread.
   */
  def newThreadBasedDispatcher(actor: ActorRef, mailboxCapacity: Int, pushTimeOut: Duration) =
    new ThreadBasedDispatcher(actor, mailboxCapacity, pushTimeOut)

  /**
   * Creates a executor-based event-driven dispatcher serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newExecutorBasedEventDrivenDispatcher(name: String) =
    ThreadPoolConfigDispatcherBuilder(config => new ExecutorBasedEventDrivenDispatcher(name,config),ThreadPoolConfig())

  /**
   * Creates a executor-based event-driven dispatcher serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newExecutorBasedEventDrivenDispatcher(name: String, throughput: Int, mailboxType: MailboxType) =
    ThreadPoolConfigDispatcherBuilder(config =>
      new ExecutorBasedEventDrivenDispatcher(name, throughput, THROUGHPUT_DEADLINE_TIME_MILLIS, mailboxType, config),ThreadPoolConfig())


  /**
   * Creates a executor-based event-driven dispatcher serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newExecutorBasedEventDrivenDispatcher(name: String, throughput: Int, throughputDeadlineMs: Int, mailboxType: MailboxType) =
    ThreadPoolConfigDispatcherBuilder(config =>
      new ExecutorBasedEventDrivenDispatcher(name, throughput, throughputDeadlineMs, mailboxType, config),ThreadPoolConfig())

  /**
   * Creates a executor-based event-driven dispatcher with work stealing (TODO: better doc) serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newExecutorBasedEventDrivenWorkStealingDispatcher(name: String): ThreadPoolConfigDispatcherBuilder =
    newExecutorBasedEventDrivenWorkStealingDispatcher(name,MAILBOX_TYPE)

  /**
   * Creates a executor-based event-driven dispatcher with work stealing (TODO: better doc) serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newExecutorBasedEventDrivenWorkStealingDispatcher(name: String, mailboxType: MailboxType) =
    ThreadPoolConfigDispatcherBuilder(config => new ExecutorBasedEventDrivenWorkStealingDispatcher(name,mailboxType,config),ThreadPoolConfig())

  /**
   * Utility function that tries to load the specified dispatcher config from the akka.conf
   * or else use the supplied default dispatcher
   */
  def fromConfig(key: String, default: => MessageDispatcher = defaultGlobalDispatcher): MessageDispatcher =
    config getConfigMap key flatMap from getOrElse default

  /*
   * Creates of obtains a dispatcher from a ConfigMap according to the format below
   *
   * default-dispatcher {
   *   type = "GlobalExecutorBasedEventDriven" # Must be one of the following, all "Global*" are non-configurable
   *                               # (ExecutorBasedEventDrivenWorkStealing), ExecutorBasedEventDriven,
   *                               # Hawt, GlobalExecutorBasedEventDriven, GlobalHawt
   *   keep-alive-time = 60        # Keep alive time for threads
   *   core-pool-size-factor = 1.0 # No of core threads ... ceil(available processors * factor)
   *   max-pool-size-factor  = 4.0 # Max no of threads ... ceil(available processors * factor)
   *   executor-bounds = -1        # Makes the Executor bounded, -1 is unbounded
   *   allow-core-timeout = on     # Allow core threads to time out
   *   rejection-policy = "caller-runs" # abort, caller-runs, discard-oldest, discard
   *   throughput = 5              # Throughput for ExecutorBasedEventDrivenDispatcher
   *   aggregate = off             # Aggregate on/off for HawtDispatchers
   * }
   * ex: from(config.getConfigMap(identifier).get)
   *
   * Gotcha: Only configures the dispatcher if possible
   * Returns: None if "type" isn't specified in the config
   * Throws: IllegalArgumentException if the value of "type" is not valid
   */
  def from(cfg: ConfigMap): Option[MessageDispatcher] = {
    lazy val name = cfg.getString("name", newUuid.toString)

    def configureThreadPool(createDispatcher: => (ThreadPoolConfig) => MessageDispatcher): ThreadPoolConfigDispatcherBuilder = {
      import ThreadPoolConfigDispatcherBuilder.conf_?

      //Apply the following options to the config if they are present in the cfg
      ThreadPoolConfigDispatcherBuilder(createDispatcher,ThreadPoolConfig()).configure(
        conf_?(cfg getInt    "keep-alive-time"      )(time   => _.setKeepAliveTime(Duration(time, TIME_UNIT))),
        conf_?(cfg getDouble "core-pool-size-factor")(factor => _.setCorePoolSizeFromFactor(factor)),
        conf_?(cfg getDouble "max-pool-size-factor" )(factor => _.setMaxPoolSizeFromFactor(factor)),
        conf_?(cfg getInt    "executor-bounds"      )(bounds => _.setExecutorBounds(bounds)),
        conf_?(cfg getBool   "allow-core-timeout"   )(allow  => _.setAllowCoreThreadTimeout(allow)),
        conf_?(cfg getString "rejection-policy" map {
          case "abort"          => new AbortPolicy()
          case "caller-runs"    => new CallerRunsPolicy()
          case "discard-oldest" => new DiscardOldestPolicy()
          case "discard"        => new DiscardPolicy()
          case x                => throw new IllegalArgumentException("[%s] is not a valid rejectionPolicy!" format x)
        })(policy => _.setRejectionPolicy(policy)))
    }

    lazy val mailboxType: MailboxType = {
      val capacity = cfg.getInt("mailbox-capacity", MAILBOX_CAPACITY)
      // FIXME how do we read in isBlocking for mailbox? Now set to 'false'.
      if (capacity < 0) UnboundedMailbox()
      else BoundedMailbox(false, capacity, Duration(cfg.getInt("mailbox-push-timeout", MAILBOX_PUSH_TIME_OUT.toMillis.toInt), TIME_UNIT))
    }

    cfg.getString("type") map {
      case "ExecutorBasedEventDriven" =>
        configureThreadPool(threadPoolConfig => new ExecutorBasedEventDrivenDispatcher(
          name,
          cfg.getInt("throughput", THROUGHPUT),
          cfg.getInt("throughput-deadline", THROUGHPUT_DEADLINE_TIME_MILLIS),
          mailboxType,
          threadPoolConfig)).build

      case "ExecutorBasedEventDrivenWorkStealing" =>
        configureThreadPool(poolCfg => new ExecutorBasedEventDrivenWorkStealingDispatcher(name, mailboxType,poolCfg)).build

      case "Hawt"                                 => new HawtDispatcher(cfg.getBool("aggregate",true))
      case "GlobalExecutorBasedEventDriven"       => globalExecutorBasedEventDrivenDispatcher
      case "GlobalHawt"                           => globalHawtDispatcher
      case unknown                                => throw new IllegalArgumentException("Unknown dispatcher type [%s]" format unknown)
    }
  }
}
