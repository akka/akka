/**
 *   Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.config.Config.config
import net.lag.configgy.ConfigMap
import se.scalablesolutions.akka.util.UUID
import java.util.concurrent.ThreadPoolExecutor.{AbortPolicy, CallerRunsPolicy, DiscardOldestPolicy, DiscardPolicy}

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
  val THROUGHPUT       = config.getInt("akka.actor.throughput", 5)
  val MAILBOX_CAPACITY = config.getInt("akka.actor.default-dispatcher.mailbox-capacity", 1000)

  lazy val defaultGlobalDispatcher = {
    config.getConfigMap("akka.actor.default-dispatcher").flatMap(from).getOrElse(globalExecutorBasedEventDrivenDispatcher)
  }

  object globalHawtDispatcher extends HawtDispatcher

  object globalExecutorBasedEventDrivenDispatcher extends ExecutorBasedEventDrivenDispatcher("global") {
    override def register(actor: ActorRef) = {
      if (isShutdown) init
      super.register(actor)
    }
  }

  object globalReactorBasedSingleThreadEventDrivenDispatcher extends ReactorBasedSingleThreadEventDrivenDispatcher("global")

  object globalReactorBasedThreadPoolEventDrivenDispatcher extends ReactorBasedThreadPoolEventDrivenDispatcher("global")

  /**
   * Creates an event-driven dispatcher based on the excellent HawtDispatch library.
   * <p/>
   * Can be beneficial to use the <code>HawtDispatcher.pin(self)</code> to "pin" an actor to a specific thread.
   * <p/>
   * See the ScalaDoc for the {@link se.scalablesolutions.akka.dispatch.HawtDispatcher} for details.
   */
  def newHawtDispatcher(aggregate: Boolean) = new HawtDispatcher(aggregate)

  /**
   * Creates an thread based dispatcher serving a single actor through the same single thread.
   * <p/>
   * E.g. each actor consumes its own thread.
   */
  def newThreadBasedDispatcher(actor: ActorRef) = new ThreadBasedDispatcher(actor)

  /**
   * Creates an thread based dispatcher serving a single actor through the same single thread.
   * <p/>
   * E.g. each actor consumes its own thread.
   */
  def newThreadBasedDispatcher(actor: ActorRef, mailboxCapacity: Int) = new ThreadBasedDispatcher(actor, mailboxCapacity)

  /**
   * Creates a executor-based event-driven dispatcher serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newExecutorBasedEventDrivenDispatcher(name: String) = new ExecutorBasedEventDrivenDispatcher(name, THROUGHPUT)

  /**
   * Creates a executor-based event-driven dispatcher serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newExecutorBasedEventDrivenDispatcher(name: String, throughput: Int) = new ExecutorBasedEventDrivenDispatcher(name, throughput)

  /**
   * Creates a executor-based event-driven dispatcher serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newExecutorBasedEventDrivenDispatcher(name: String, throughput: Int, mailboxCapacity: Int) = new ExecutorBasedEventDrivenDispatcher(name, throughput, mailboxCapacity)

  /**
   * Creates a executor-based event-driven dispatcher with work stealing (TODO: better doc) serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newExecutorBasedEventDrivenWorkStealingDispatcher(name: String) = new ExecutorBasedEventDrivenWorkStealingDispatcher(name)

  /**
   * Creates a executor-based event-driven dispatcher with work stealing (TODO: better doc) serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newExecutorBasedEventDrivenWorkStealingDispatcher(name: String, mailboxCapacity: Int) = new ExecutorBasedEventDrivenWorkStealingDispatcher(name, mailboxCapacity)

  /**
   * Creates a reactor-based event-driven dispatcher serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newReactorBasedThreadPoolEventDrivenDispatcher(name: String) = new ReactorBasedThreadPoolEventDrivenDispatcher(name)

  /**
   * Creates a reactor-based event-driven dispatcher serving multiple (millions) of actors through a single thread.
   */
  def newReactorBasedSingleThreadEventDrivenDispatcher(name: String) = new ReactorBasedSingleThreadEventDrivenDispatcher(name)

  /**
   * Utility function that tries to load the specified dispatcher config from the akka.conf
   * or else use the supplied default dispatcher
   */
  def fromConfig(key: String, default: => MessageDispatcher = defaultGlobalDispatcher): MessageDispatcher =
    config.getConfigMap(key).flatMap(from).getOrElse(default)

  /*
   * Creates of obtains a dispatcher from a ConfigMap according to the format below
   *
   * default-dispatcher {
   *   type = "GlobalExecutorBasedEventDriven" # Must be one of the following, all "Global*" are non-configurable
   *                               # ReactorBasedSingleThreadEventDriven, (ExecutorBasedEventDrivenWorkStealing), ExecutorBasedEventDriven,
   *                               # ReactorBasedThreadPoolEventDriven, Hawt, GlobalReactorBasedSingleThreadEventDriven,
   *                               # GlobalReactorBasedThreadPoolEventDriven, GlobalExecutorBasedEventDriven, GlobalHawt
   *   keep-alive-ms = 60000       # Keep alive time for threads
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
    lazy val name = cfg.getString("name", UUID.newUuid.toString)

    val dispatcher: Option[MessageDispatcher] = cfg.getString("type") map {
      case "ReactorBasedSingleThreadEventDriven"       => newReactorBasedSingleThreadEventDrivenDispatcher(name)
      case "ExecutorBasedEventDrivenWorkStealing"      => newExecutorBasedEventDrivenWorkStealingDispatcher(name)
      case "ExecutorBasedEventDriven"                  => newExecutorBasedEventDrivenDispatcher(name,cfg.getInt("throughput",THROUGHPUT))
      case "ReactorBasedThreadPoolEventDriven"         => newReactorBasedThreadPoolEventDrivenDispatcher(name)
      case "Hawt"                                      => newHawtDispatcher(cfg.getBool("aggregate").getOrElse(true))
      case "GlobalReactorBasedSingleThreadEventDriven" => globalReactorBasedSingleThreadEventDrivenDispatcher
      case "GlobalReactorBasedThreadPoolEventDriven"   => globalReactorBasedThreadPoolEventDrivenDispatcher
      case "GlobalExecutorBasedEventDriven"            => globalExecutorBasedEventDrivenDispatcher
      case "GlobalHawt"                                => globalHawtDispatcher

      case unknown => throw new IllegalArgumentException("Unknown dispatcher type [%s]" format unknown)
    }

    dispatcher foreach {
      case d: ThreadPoolBuilder => d.configureIfPossible( builder => {

        cfg.getInt("keep-alive-ms").foreach(builder.setKeepAliveTimeInMillis(_))
        cfg.getDouble("core-pool-size-factor").foreach(builder.setCorePoolSizeFromFactor(_))
        cfg.getDouble("max-pool-size-factor").foreach(builder.setMaxPoolSizeFromFactor(_))
        cfg.getInt("executor-bounds").foreach(builder.setExecutorBounds(_))
        cfg.getBool("allow-core-timeout").foreach(builder.setAllowCoreThreadTimeout(_))
        cfg.getInt("mailbox-capacity").foreach(builder.setMailboxCapacity(_))

        cfg.getString("rejection-policy").map({
          case "abort"          => new AbortPolicy()
          case "caller-runs"    => new CallerRunsPolicy()
          case "discard-oldest" => new DiscardOldestPolicy()
          case "discard"        => new DiscardPolicy()
          case x => throw new IllegalArgumentException("[%s] is not a valid rejectionPolicy!" format x)
        }).foreach(builder.setRejectionPolicy(_))
      })
      case _ =>
    }

    dispatcher
  }
}
