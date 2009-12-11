/**
 *  Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.dispatch

import se.scalablesolutions.akka.actor.Actor

/**
 * Scala API. Dispatcher factory.
 * <p/>
 * Example usage:
 * <pre/>
 *   val dispatcher = Dispatchers.newEventBasedThreadPoolDispatcher("name")
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
 *   MessageDispatcher dispatcher = Dispatchers.newEventBasedThreadPoolDispatcher("name");
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
  object globalExecutorBasedEventDrivenDispatcher extends ExecutorBasedEventDrivenDispatcher("global")
  object globalForkJoinBasedEventDrivenDispatcher extends ForkJoinBasedEventDrivenDispatcher("global")
  object globalReactorBasedSingleThreadEventDrivenDispatcher extends ReactorBasedSingleThreadEventDrivenDispatcher("global")
  object globalReactorBasedThreadPoolEventDrivenDispatcher extends ReactorBasedThreadPoolEventDrivenDispatcher("global")

  /**
   * Creates an event based dispatcher serving multiple (millions) of actors through a thread pool.
   * Has a fluent builder interface for configuring its semantics.
   */
  def newReactorBasedThreadPoolEventDrivenDispatcher(name: String) = new ReactorBasedThreadPoolEventDrivenDispatcher(name)

  /**
   * Creates an event based dispatcher serving multiple (millions) of actors through a single thread.
   */
  def newReactorBasedSingleThreadEventDrivenDispatcher(name: String) = new ReactorBasedSingleThreadEventDrivenDispatcher(name)

  def newExecutorBasedEventDrivenDispatcher(name: String) = new ExecutorBasedEventDrivenDispatcher(name)

  def newForkJoinBasedEventDrivenDispatcher(name: String) = new ForkJoinBasedEventDrivenDispatcher(name)

  /**
   * Creates an thread based dispatcher serving a single actor through the same single thread.
   * E.g. each actor consumes its own thread.
   */
  def newThreadBasedDispatcher(actor: Actor) = new ThreadBasedDispatcher(actor)
}
