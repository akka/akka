/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.agent

import scala.concurrent.stm._
import scala.concurrent.{ ExecutionContext, Future, Promise }
import akka.util.SerializedSuspendableExecutionContext

@deprecated("Agents are deprecated and scheduled for removal in the next major version, use Actors instead.", since = "2.5.0")
object Agent {
  /**
   * Factory method for creating an Agent.
   */
  @deprecated("Agents are deprecated and scheduled for removal in the next major version, use Actors instead.", since = "2.5.0")
  def apply[T](initialValue: T)(implicit context: ExecutionContext): Agent[T] = new SecretAgent(initialValue, context)

  /**
   * Java API: Factory method for creating an Agent.
   * @deprecated Agents are deprecated and scheduled for removal in the next major version, use Actors instead.i
   */
  @Deprecated
  @deprecated("Agents are deprecated and scheduled for removal in the next major version, use Actors instead.", since = "2.5.0")
  def create[T](initialValue: T, context: ExecutionContext): Agent[T] = Agent(initialValue)(context)

  /**
   * Default agent implementation.
   */
  private final class SecretAgent[T](initialValue: T, context: ExecutionContext) extends Agent[T] {
    private val ref = Ref(initialValue)
    private val updater = SerializedSuspendableExecutionContext(10)(context)

    def get(): T = ref.single.get

    def send(newValue: T): Unit = withinTransaction(new Runnable { def run = ref.single.update(newValue) })

    def send(f: T ⇒ T): Unit = withinTransaction(new Runnable { def run = ref.single.transform(f) })

    def sendOff(f: T ⇒ T)(implicit ec: ExecutionContext): Unit = withinTransaction(
      new Runnable {
        def run =
          try updater.suspend() finally ec.execute(new Runnable { def run = try ref.single.transform(f) finally updater.resume() })
      })

    def alter(newValue: T): Future[T] = doAlter({ ref.single.update(newValue); newValue })

    def alter(f: T ⇒ T): Future[T] = doAlter(ref.single.transformAndGet(f))

    def alterOff(f: T ⇒ T)(implicit ec: ExecutionContext): Future[T] = {
      val result = Promise[T]()
      withinTransaction(new Runnable {
        def run = {
          updater.suspend()
          result completeWith Future(try ref.single.transformAndGet(f) finally updater.resume())
        }
      })
      result.future
    }

    /**
     * Internal helper method
     */
    private final def withinTransaction(run: Runnable): Unit = {
      Txn.findCurrent match {
        case Some(txn) ⇒ Txn.afterCommit(_ ⇒ updater.execute(run))(txn)
        case _         ⇒ updater.execute(run)
      }
    }

    /**
     * Internal helper method
     */
    private final def doAlter(f: ⇒ T): Future[T] = {
      Txn.findCurrent match {
        case Some(txn) ⇒
          val result = Promise[T]()
          Txn.afterCommit(status ⇒ result completeWith Future(f)(updater))(txn)
          result.future
        case _ ⇒ Future(f)(updater)
      }
    }

    def future(): Future[T] = Future(ref.single.get)(updater)

    def map[B](f: T ⇒ B): Agent[B] = Agent(f(get))(updater)

    def flatMap[B](f: T ⇒ Agent[B]): Agent[B] = f(get)

    def foreach[U](f: T ⇒ U): Unit = f(get)
  }
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
 * }}}
 *
 * Agent is also monadic, which means that you can compose operations using
 * for-comprehensions. In monadic usage the original agents are not touched
 * but new agents are created. So the old values (agents) are still available
 * as-is. They are so-called 'persistent'.
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
 * }}}
 *
 * ==DEPRECATED STM SUPPORT==
 *
 * Agents participating in enclosing STM transaction is a deprecated feature in 2.3.
 *
 * If an Agent is used within an enclosing transaction, then it will
 * participate in that transaction. Agents are integrated with the STM -
 * any dispatches made in a transaction are held until that transaction
 * commits, and are discarded if it is retried or aborted.
 *
 * @deprecated Agents are deprecated and scheduled for removal in the next major version, use Actors instead.
 */
@deprecated("Agents are deprecated and scheduled for removal in the next major version, use Actors instead.", since = "2.5.0")
abstract class Agent[T] {

  /**
   * Java API: Read the internal state of the agent.
   */
  def get(): T

  /**
   * Read the internal state of the agent.
   */
  def apply(): T = get

  /**
   * Dispatch a new value for the internal state. Behaves the same
   * as sending a function (x => newValue).
   */
  def send(newValue: T): Unit

  /**
   * Dispatch a function to update the internal state.
   * In Java, pass in an instance of `akka.dispatch.Mapper`.
   */
  def send(f: T ⇒ T): Unit

  /**
   * Dispatch a function to update the internal state but on its own thread.
   * This does not use the reactive thread pool and can be used for long-running
   * or blocking operations. Dispatches using either `sendOff` or `send` will
   * still be executed in order.
   * In Java, pass in an instance of `akka.dispatch.Mapper`.
   */
  def sendOff(f: T ⇒ T)(implicit ec: ExecutionContext): Unit

  /**
   * Dispatch an update to the internal state, and return a Future where
   * that new state can be obtained.
   * In Java, pass in an instance of `akka.dispatch.Mapper`.
   */
  def alter(newValue: T): Future[T]

  /**
   * Dispatch a function to update the internal state, and return a Future where
   * that new state can be obtained.
   * In Java, pass in an instance of `akka.dispatch.Mapper`.
   */
  def alter(f: T ⇒ T): Future[T]

  /**
   * Dispatch a function to update the internal state but on its own thread,
   * and return a Future where that new state can be obtained.
   * This does not use the reactive thread pool and can be used for long-running
   * or blocking operations. Dispatches using either `alterOff` or `alter` will
   * still be executed in order.
   * In Java, pass in an instance of `akka.dispatch.Mapper`.
   */
  def alterOff(f: T ⇒ T)(implicit ec: ExecutionContext): Future[T]

  /**
   * A future to the current value that will be completed after any currently
   * queued updates.
   */
  def future(): Future[T]

  /**
   * Map this agent to a new agent, applying the function to the internal state.
   * Does not change the value of this agent.
   * In Java, pass in an instance of `akka.dispatch.Mapper`.
   */
  def map[B](f: T ⇒ B): Agent[B]

  /**
   * Flatmap this agent to a new agent, applying the function to the internal state.
   * Does not change the value of this agent.
   * In Java, pass in an instance of `akka.dispatch.Mapper`.
   */
  def flatMap[B](f: T ⇒ Agent[B]): Agent[B]

  /**
   * Applies the function to the internal state. Does not change the value of this agent.
   * In Java, pass in an instance of `akka.dispatch.Foreach`.
   */
  def foreach[U](f: T ⇒ U): Unit
}
