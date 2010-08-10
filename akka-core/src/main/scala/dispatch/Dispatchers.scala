/**
 *  Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import se.scalablesolutions.akka.config.Config.config

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
object Dispatchers {
  val THROUGHPUT = config.getInt("akka.actor.throughput", 5)

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
}
