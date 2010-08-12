/**
 *  Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
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
  val THROUGHPUT = config.getInt("akka.actor.throughput", 5)


  val defaultGlobalDispatcher = fromConfig("akka.actor.default-dispatcher",globalExecutorBasedEventDrivenDispatcher)

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
   * Creates a executor-based event-driven dispatcher serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newExecutorBasedEventDrivenDispatcher(name: String, throughput: Int = THROUGHPUT) = new ExecutorBasedEventDrivenDispatcher(name, throughput)

  /**
   * Creates a executor-based event-driven dispatcher serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newExecutorBasedEventDrivenDispatcher(name: String) = new ExecutorBasedEventDrivenDispatcher(name, THROUGHPUT)

  /**
   * Creates a executor-based event-driven dispatcher with work stealing (TODO: better doc) serving multiple (millions) of actors through a thread pool.
   * <p/>
   * Has a fluent builder interface for configuring its semantics.
   */
  def newExecutorBasedEventDrivenWorkStealingDispatcher(name: String) = new ExecutorBasedEventDrivenWorkStealingDispatcher(name)

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
   * Creates an thread based dispatcher serving a single actor through the same single thread.
   * <p/>
   * E.g. each actor consumes its own thread.
   */
  def newThreadBasedDispatcher(actor: ActorRef) = new ThreadBasedDispatcher(actor)

  def fromConfig(identifier: String, defaultDispatcher: => MessageDispatcher = defaultGlobalDispatcher): MessageDispatcher = {
    config.getConfigMap(identifier).map(from).getOrElse(defaultDispatcher)  
  }

  def from(map: ConfigMap): MessageDispatcher = {
    lazy val name = map.getString("name",UUID.newUuid.toString)

    val dispatcher = map.getString("type").map({
      case "ReactorBasedSingleThreadEventDriven"       => newReactorBasedSingleThreadEventDrivenDispatcher(name)

      case "ExecutorBasedEventDrivenWorkStealing"      => newExecutorBasedEventDrivenWorkStealingDispatcher(name)

      case "ExecutorBasedEventDriven"                  => newExecutorBasedEventDrivenDispatcher(name,map.getInt("throughput",THROUGHPUT))

      case "ReactorBasedThreadPoolEventDriven"         => newReactorBasedThreadPoolEventDrivenDispatcher(name)

      case "Hawt"                                      => newHawtDispatcher(map.getBool("aggregate").getOrElse(true))

      case "GlobalReactorBasedSingleThreadEventDriven" => globalReactorBasedSingleThreadEventDrivenDispatcher

      case "GlobalReactorBasedThreadPoolEventDriven"   => globalReactorBasedThreadPoolEventDrivenDispatcher

      case "GlobalExecutorBasedEventDriven"            => globalExecutorBasedEventDrivenDispatcher

      case "GlobalHawt"                                => globalHawtDispatcher

      case "Default"                                   => defaultGlobalDispatcher

      case unknown => throw new IllegalArgumentException("Unknown dispatcher type %s" format unknown)

    }).get

    if(dispatcher.isInstanceOf[ThreadPoolBuilder]) {
      val configurable = dispatcher.asInstanceOf[ThreadPoolBuilder]
      
      map.getInt("keep-alive-ms").foreach(configurable.setKeepAliveTimeInMillis(_))

      map.getInt("core-pool-size").foreach(configurable.setCorePoolSize(_))

      map.getInt("max-pool-size").foreach(configurable.setMaxPoolSize(_))

      map.getString("rejection-policy").map(_ match {

        case "abort"          => new AbortPolicy()

        case "caller-runs"    => new CallerRunsPolicy()

        case "discard-oldest" => new DiscardOldestPolicy()

        case "discard"        => new DiscardPolicy()

        case x => throw new IllegalArgumentException("[%s] is not a valid rejectionPolicy!" format x)

      }).foreach(configurable.setRejectionPolicy(_))
    }

    log.info("Dispatchers.from: %s:%s for %s",dispatcher.getClass.getName,dispatcher,map.getString("type").getOrElse("<null>"))

    dispatcher
  }
}
