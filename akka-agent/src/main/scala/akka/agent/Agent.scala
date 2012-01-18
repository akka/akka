/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.agent

import akka.actor._
import akka.japi.{ Function ⇒ JFunc, Procedure ⇒ JProc }
import akka.dispatch._
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.stm._

/**
 * Used internally to send functions.
 */
private[akka] case class Update[T](function: T ⇒ T)
private[akka] case class Alter[T](function: T ⇒ T)
private[akka] case object Get

/**
 * Factory method for creating an Agent.
 */
object Agent {
  def apply[T](initialValue: T)(implicit system: ActorSystem) = new Agent(initialValue, system)
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
class Agent[T](initialValue: T, system: ActorSystem) {
  private[akka] val ref = Ref(initialValue)
  private[akka] val updater = system.actorOf(Props(new AgentUpdater(this))).asInstanceOf[LocalActorRef] //TODO can we avoid this somehow?

  /**
   * Read the internal state of the agent.
   */
  def get() = ref.single.get

  /**
   * Read the internal state of the agent.
   */
  def apply() = get

  /**
   * Dispatch a function to update the internal state.
   */
  def send(f: T ⇒ T): Unit = {
    def dispatch = updater ! Update(f)
    val txn = Txn.findCurrent
    if (txn.isDefined) Txn.afterCommit(status ⇒ dispatch)(txn.get)
    else dispatch
  }

  /**
   * Dispatch a function to update the internal state, and return a Future where
   * that new state can be obtained within the given timeout.
   */
  def alter(f: T ⇒ T)(timeout: Timeout): Future[T] = {
    def dispatch = updater.?(Alter(f), timeout).asInstanceOf[Future[T]]
    val txn = Txn.findCurrent
    if (txn.isDefined) {
      val result = Promise[T]()(system.dispatcher)
      Txn.afterCommit(status ⇒ result completeWith dispatch)(txn.get)
      result
    } else dispatch
  }

  /**
   * Dispatch a new value for the internal state. Behaves the same
   * as sending a function (x => newValue).
   */
  def send(newValue: T): Unit = send(_ ⇒ newValue)

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
  def sendOff(f: T ⇒ T): Unit = {
    send((value: T) ⇒ {
      suspend()
      val threadBased = system.actorOf(Props(new ThreadBasedAgentUpdater(this)).withDispatcher("akka.agent.send-off-dispatcher"))
      threadBased ! Update(f)
      value
    })
  }

  /**
   * Dispatch a function to update the internal state but on its own thread,
   * and return a Future where that new state can be obtained within the given timeout.
   * This does not use the reactive thread pool and can be used for long-running
   * or blocking operations. Dispatches using either `alterOff` or `alter` will
   * still be executed in order.
   */
  def alterOff(f: T ⇒ T)(timeout: Timeout): Future[T] = {
    val result = Promise[T]()(system.dispatcher)
    send((value: T) ⇒ {
      suspend()
      val threadBased = system.actorOf(Props(new ThreadBasedAgentUpdater(this)).withDispatcher("akka.agent.alter-off-dispatcher"))
      result completeWith threadBased.?(Alter(f), timeout).asInstanceOf[Future[T]]
      value
    })
    result
  }

  /**
   * A future to the current value that will be completed after any currently
   * queued updates.
   */
  def future(implicit timeout: Timeout): Future[T] = (updater ? Get).asInstanceOf[Future[T]]

  /**
   * Gets this agent's value after all currently queued updates have completed.
   */
  def await(implicit timeout: Timeout): T = Await.result(future, timeout.duration)

  /**
   * Map this agent to a new agent, applying the function to the internal state.
   * Does not change the value of this agent.
   */
  def map[B](f: T ⇒ B): Agent[B] = Agent(f(get))(system)

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
  def suspend() = updater.suspend()

  /**
   * Resumes processing of `send` actions for the agent.
   */
  def resume() = updater.resume()

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
  def map[B](f: JFunc[T, B]): Agent[B] = Agent(f(get))(system)

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
  def receive = {
    case u: Update[_] ⇒ update(u.function.asInstanceOf[T ⇒ T])
    case a: Alter[_]  ⇒ sender ! update(a.function.asInstanceOf[T ⇒ T])
    case Get          ⇒ sender ! agent.get
    case _            ⇒
  }

  def update(function: T ⇒ T): T = agent.ref.single.transformAndGet(function)
}

/**
 * Thread-based agent updater actor. Used internally for `sendOff` actions.
 */
class ThreadBasedAgentUpdater[T](agent: Agent[T]) extends Actor {
  def receive = {
    case u: Update[_] ⇒ try {
      update(u.function.asInstanceOf[T ⇒ T])
    } finally {
      agent.resume()
      context.stop(self)
    }
    case a: Alter[_] ⇒ try {
      sender ! update(a.function.asInstanceOf[T ⇒ T])
    } finally {
      agent.resume()
      context.stop(self)
    }
    case _ ⇒ context.stop(self)
  }

  def update(function: T ⇒ T): T = agent.ref.single.transformAndGet(function)
}
