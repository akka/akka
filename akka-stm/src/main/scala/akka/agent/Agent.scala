/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.agent

import akka.stm._
import akka.actor.Actor
import akka.japi.{ Function ⇒ JFunc, Procedure ⇒ JProc }
import akka.dispatch.{ DefaultCompletableFuture, Dispatchers, Future }

/**
 * Used internally to send functions.
 */
private[akka] case class Update[T](function: T ⇒ T)
private[akka] case object Get

/**
 * Factory method for creating an Agent.
 */
object Agent {
  def apply[T](initialValue: T) = new Agent(initialValue)
}

/**
 * The Agent class was inspired by agents in Clojure.
 *
 * Agents provide asynchronous change of individual locations. Agents
 * are bound to a single storage location for their lifetime, and only
 * allow mutation of that location (to a new state) to occur as a result
 * of an action. Update actions are functions that are asynchronously
 * applied to the Agent's state and whose return value becomes the
 * Agent's new state. The state of an Agent should be immutable.
 *
 * While updates to Agents are asynchronous, the state of an Agent is
 * always immediately available for reading by any thread (using ''get''
 * or ''apply'') without any messages.
 *
 * Agents are reactive. The update actions of all Agents get interleaved
 * amongst threads in a thread pool. At any point in time, at most one
 * ''send'' action for each Agent is being executed. Actions dispatched to
 * an agent from another thread will occur in the order they were sent,
 * potentially interleaved with actions dispatched to the same agent from
 * other sources.
 *
 * If an Agent is used within an enclosing transaction, then it will
 * participate in that transaction. Agents are integrated with the STM -
 * any dispatches made in a transaction are held until that transaction
 * commits, and are discarded if it is retried or aborted.
 * <br/><br/>
 *
 * Example of usage:
 * {{{
 * val agent = Agent(5)
 *
 * agent send (_ * 2)
 *
 * ...
 *
 * val result = agent()
 * // use result ...
 *
 * agent.close
 * }}}
 * <br/>
 *
 * Agent is also monadic, which means that you can compose operations using
 * for-comprehensions. In monadic usage the original agents are not touched
 * but new agents are created. So the old values (agents) are still available
 * as-is. They are so-called 'persistent'.
 * <br/><br/>
 *
 * Example of monadic usage:
 * {{{
 * val agent1 = Agent(3)
 * val agent2 = Agent(5)
 *
 * for (value <- agent1) {
 *   result = value + 1
 * }
 *
 * val agent3 = for (value <- agent1) yield value + 1
 *
 * val agent4 = for {
 *   value1 <- agent1
 *   value2 <- agent2
 * } yield value1 + value2
 *
 * agent1.close
 * agent2.close
 * agent3.close
 * agent4.close
 * }}}
 */
class Agent[T](initialValue: T) {
  private[akka] val ref = Ref(initialValue)
  private[akka] val updater = Actor.actorOf(new AgentUpdater(this)).start()

  /**
   * Read the internal state of the agent.
   */
  def get() = ref.get

  /**
   * Read the internal state of the agent.
   */
  def apply() = get

  /**
   * Dispatch a function to update the internal state.
   */
  def send(f: T ⇒ T): Unit = {
    def dispatch = updater ! Update(f)
    if (Stm.activeTransaction) { get; deferred(dispatch) }
    else dispatch
  }

  /**
   * Dispatch a function to update the internal state, and return a Future where that new state can be obtained
   * within the given timeout
   */
  def alter(f: T ⇒ T)(timeout: Long): Future[T] = {
    def dispatch = updater.!!!(Update(f), timeout)
    if (Stm.activeTransaction) {
      val result = new DefaultCompletableFuture[T](timeout)
      get //Join xa
      deferred {
        result completeWith dispatch
      } //Attach deferred-block to current transaction
      result
    } else dispatch
  }

  /**
   * Dispatch a new value for the internal state. Behaves the same
   * as sending a function (x => newValue).
   */
  def send(newValue: T): Unit = send(x ⇒ newValue)

  /**
   * Dispatch a new value for the internal state. Behaves the same
   * as sending a function (x => newValue).
   */
  def update(newValue: T) = send(newValue)

  /**
   * Dispatch a function to update the internal state but on its own thread.
   * This does not use the reactive thread pool and can be used for long-running
   * or blocking operations. Dispatches using either `sendOff` or `send` will
   * still be executed in order.
   */
  def sendOff(f: T ⇒ T): Unit = send((value: T) ⇒ {
    suspend
    val threadBased = Actor.actorOf(new ThreadBasedAgentUpdater(this)).start()
    threadBased ! Update(f)
    value
  })

  /**
   * Dispatch a function to update the internal state but on its own thread,
   * and return a Future where that new state can be obtained within the given timeout.
   * This does not use the reactive thread pool and can be used for long-running
   * or blocking operations. Dispatches using either `alterOff` or `alter` will
   * still be executed in order.
   */
  def alterOff(f: T ⇒ T)(timeout: Long): Future[T] = {
    val result = new DefaultCompletableFuture[T](timeout)
    send((value: T) ⇒ {
      suspend
      val threadBased = Actor.actorOf(new ThreadBasedAgentUpdater(this)).start()
      result completeWith threadBased.!!!(Update(f), timeout)
      value
    })
    result
  }

  /**
   * A future to the current value that will be completed after any currently
   * queued updates.
   */
  def future(): Future[T] = (updater !!! Get).asInstanceOf[Future[T]]

  /**
   * Gets this agent's value after all currently queued updates have completed.
   */
  def await(): T = future.await.result.get

  /**
   * Map this agent to a new agent, applying the function to the internal state.
   * Does not change the value of this agent.
   */
  def map[B](f: T ⇒ B): Agent[B] = Agent(f(get))

  /**
   * Flatmap this agent to a new agent, applying the function to the internal state.
   * Does not change the value of this agent.
   */
  def flatMap[B](f: T ⇒ Agent[B]): Agent[B] = f(get)

  /**
   * Applies the function to the internal state. Does not change the value of this agent.
   */
  def foreach[U](f: T ⇒ U): Unit = f(get)

  /**
   * Suspends processing of `send` actions for the agent.
   */
  def suspend() = updater.dispatcher.suspend(updater)

  /**
   * Resumes processing of `send` actions for the agent.
   */
  def resume() = updater.dispatcher.resume(updater)

  /**
   * Closes the agents and makes it eligible for garbage collection.
   * A closed agent cannot accept any `send` actions.
   */
  def close() = updater.stop()

  // ---------------------------------------------
  // Support for Java API Functions and Procedures
  // ---------------------------------------------

  /**
   * Java API:
   * Dispatch a function to update the internal state.
   */
  def send(f: JFunc[T, T]): Unit = send(x ⇒ f(x))

  /**
   * Java API
   * Dispatch a function to update the internal state, and return a Future where that new state can be obtained
   * within the given timeout
   */
  def alter(f: JFunc[T, T], timeout: Long): Future[T] = alter(x ⇒ f(x))(timeout)

  /**
   * Java API:
   * Dispatch a function to update the internal state but on its own thread.
   * This does not use the reactive thread pool and can be used for long-running
   * or blocking operations. Dispatches using either `sendOff` or `send` will
   * still be executed in order.
   */
  def sendOff(f: JFunc[T, T]): Unit = sendOff(x ⇒ f(x))

  /**
   * Java API:
   * Dispatch a function to update the internal state but on its own thread,
   * and return a Future where that new state can be obtained within the given timeout.
   * This does not use the reactive thread pool and can be used for long-running
   * or blocking operations. Dispatches using either `alterOff` or `alter` will
   * still be executed in order.
   */
  def alterOff(f: JFunc[T, T], timeout: Long): Unit = alterOff(x ⇒ f(x))(timeout)

  /**
   * Java API:
   * Map this agent to a new agent, applying the function to the internal state.
   * Does not change the value of this agent.
   */
  def map[B](f: JFunc[T, B]): Agent[B] = Agent(f(get))

  /**
   * Java API:
   * Flatmap this agent to a new agent, applying the function to the internal state.
   * Does not change the value of this agent.
   */
  def flatMap[B](f: JFunc[T, Agent[B]]): Agent[B] = f(get)

  /**
   * Java API:
   * Applies the function to the internal state. Does not change the value of this agent.
   */
  def foreach(f: JProc[T]): Unit = f(get)
}

/**
 * Agent updater actor. Used internally for `send` actions.
 */
class AgentUpdater[T](agent: Agent[T]) extends Actor {
  val txFactory = TransactionFactory(familyName = "AgentUpdater", readonly = false)

  def receive = {
    case update: Update[T] ⇒
      self.tryReply(atomic(txFactory) { agent.ref alter update.function })
    case Get ⇒ self reply agent.get
    case _   ⇒ ()
  }
}

/**
 * Thread-based agent updater actor. Used internally for `sendOff` actions.
 */
class ThreadBasedAgentUpdater[T](agent: Agent[T]) extends Actor {
  self.dispatcher = Dispatchers.newThreadBasedDispatcher(self)

  val txFactory = TransactionFactory(familyName = "ThreadBasedAgentUpdater", readonly = false)

  def receive = {
    case update: Update[T] ⇒ try {
      self.tryReply(atomic(txFactory) { agent.ref alter update.function })
    } finally {
      agent.resume
      self.stop()
    }
    case _ ⇒ self.stop()
  }
}
