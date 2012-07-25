/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.agent

import akka.actor._
import akka.japi.{ Function ⇒ JFunc, Procedure ⇒ JProc }
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.stm._
import concurrent.{ ExecutionContext, Future, Promise, Await }
import concurrent.util.Duration

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
  def apply[T](initialValue: T)(implicit system: ActorSystem) = new Agent(initialValue, system, system)
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
class Agent[T](initialValue: T, refFactory: ActorRefFactory, system: ActorSystem) {
  private val ref = Ref(initialValue)
  private val updater = refFactory.actorOf(Props(new AgentUpdater(this, ref))).asInstanceOf[InternalActorRef] //TODO can we avoid this somehow?

  def this(initialValue: T, system: ActorSystem) = this(initialValue, system, system)

  /**
   * Read the internal state of the agent.
   */
  def get(): T = ref.single.get

  /**
   * Read the internal state of the agent.
   */
  def apply(): T = get

  /**
   * Dispatch a function to update the internal state.
   */
  def send(f: T ⇒ T): Unit = {
    def dispatch = updater ! Update(f)
    Txn.findCurrent match {
      case Some(txn) ⇒ Txn.afterCommit(status ⇒ dispatch)(txn)
      case _         ⇒ dispatch
    }
  }

  /**
   * Dispatch a function to update the internal state, and return a Future where
   * that new state can be obtained within the given timeout.
   */
  def alter(f: T ⇒ T)(implicit timeout: Timeout): Future[T] = {
    def dispatch = ask(updater, Alter(f)).asInstanceOf[Future[T]]
    Txn.findCurrent match {
      case Some(txn) ⇒
        val result = Promise[T]()
        Txn.afterCommit(status ⇒ result completeWith dispatch)(txn)
        result.future
      case _ ⇒ dispatch
    }
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
  def update(newValue: T): Unit = send(newValue)

  /**
   * Dispatch a function to update the internal state but on its own thread.
   * This does not use the reactive thread pool and can be used for long-running
   * or blocking operations. Dispatches using either `sendOff` or `send` will
   * still be executed in order.
   */
  def sendOff(f: T ⇒ T)(implicit ec: ExecutionContext): Unit = {
    send((value: T) ⇒ {
      suspend()
      Future(ref.single.transformAndGet(f)).andThen({ case _ ⇒ resume() })
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
  def alterOff(f: T ⇒ T)(implicit timeout: Timeout, ec: ExecutionContext): Future[T] = {
    val result = Promise[T]()
    send((value: T) ⇒ {
      suspend()
      result completeWith Future(ref.single.transformAndGet(f)).andThen({ case _ ⇒ resume() })
      value
    })
    result.future
  }

  /**
   * A future to the current value that will be completed after any currently
   * queued updates.
   */
  def future(implicit timeout: Timeout): Future[T] = (updater ? Get).asInstanceOf[Future[T]] //Known to be safe

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
  def suspend(): Unit = updater.suspend()

  /**
   * Resumes processing of `send` actions for the agent.
   */
  def resume(): Unit = updater.resume(inResponseToFailure = false)

  /**
   * Closes the agents and makes it eligible for garbage collection.
   * A closed agent cannot accept any `send` actions.
   */
  def close(): Unit = updater.stop()

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
  def alter(f: JFunc[T, T], timeout: Duration): Future[T] = alter(x ⇒ f(x))(timeout)

  /**
   * Java API:
   * Dispatch a function to update the internal state but on its own thread.
   * This does not use the reactive thread pool and can be used for long-running
   * or blocking operations. Dispatches using either `sendOff` or `send` will
   * still be executed in order.
   */
  def sendOff(f: JFunc[T, T], ec: ExecutionContext): Unit = sendOff(x ⇒ f(x))(ec)

  /**
   * Java API:
   * Dispatch a function to update the internal state but on its own thread,
   * and return a Future where that new state can be obtained within the given timeout.
   * This does not use the reactive thread pool and can be used for long-running
   * or blocking operations. Dispatches using either `alterOff` or `alter` will
   * still be executed in order.
   */
  def alterOff(f: JFunc[T, T], timeout: Duration, ec: ExecutionContext): Unit = alterOff(x ⇒ f(x))(Timeout(timeout), ec)

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
 *
 * INTERNAL API
 */
private[akka] class AgentUpdater[T](agent: Agent[T], ref: Ref[T]) extends Actor {
  def receive = {
    case u: Update[_] ⇒ update(u.function.asInstanceOf[T ⇒ T])
    case a: Alter[_]  ⇒ sender ! update(a.function.asInstanceOf[T ⇒ T])
    case Get          ⇒ sender ! agent.get
    case _            ⇒
  }

  def update(function: T ⇒ T): T = ref.single.transformAndGet(function)
}