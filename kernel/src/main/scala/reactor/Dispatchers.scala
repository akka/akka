/**
 *  Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.reactor

import kernel.actor.Actor

/**
 * Scala API. Dispatcher factory.
 * <p/>
 * Example usage:
 * <pre/>
 *   val dispatcher = Dispatchers.newEventBasedThreadPoolDispatcher
 *     .withNewThreadPoolWithBoundedBlockingQueue(100)
 *     .setCorePoolSize(16)
 *     .setMaxPoolSize(128)
 *     .setKeepAliveTimeInMillis(60000)
 *     .setRejectionPolicy(new CallerRunsPolicy)
 *     .buildThreadPool
 * </pre>
 * <p/>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Dispatchers extends DispatcherFactory

/**
 * Java API. Dispatcher factory.
 * <p/>
 * Example usage:
 * <pre/>
 *   DispatcherFactory dispatcherFactory = new DispatcherFactory
 *   MessageDispatcher dispatcher = dispatcherFactory.newEventBasedThreadPoolDispatcher
 *     .withNewThreadPoolWithBoundedBlockingQueue(100)
 *     .setCorePoolSize(16)
 *     .setMaxPoolSize(128)
 *     .setKeepAliveTimeInMillis(60000)
 *     .setRejectionPolicy(new CallerRunsPolicy)
 *     .buildThreadPool
 * </pre>
 * <p/>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class DispatcherFactory {
  
  /**
   * Creates an event based dispatcher serving multiple (millions) of actors through a thread pool.
   * Has a fluent builder interface for configuring its semantics.
   */
  def newEventBasedThreadPoolDispatcher = new EventBasedThreadPoolDispatcher
  def newConcurrentEventBasedThreadPoolDispatcher = new EventBasedThreadPoolDispatcher(true)

  /**
   * Creates an event based dispatcher serving multiple (millions) of actors through a single thread.
   */
  def newEventBasedSingleThreadDispatcher = new EventBasedSingleThreadDispatcher

  /**
   * Creates an thread based dispatcher serving a single actor through the same single thread.
   * E.g. each actor consumes its own thread.
   */
  def newThreadBasedDispatcher(actor: Actor) = new ThreadBasedDispatcher(actor)
}