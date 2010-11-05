/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import akka.stm.Ref
import akka.config.RemoteAddress
import akka.japi.{Function => JFunc, Procedure => JProc}
import akka.AkkaException

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch

class AgentException private[akka](message: String) extends AkkaException(message)

/**
* The Agent class was strongly inspired by the agent principle in Clojure.
* <p/>
*
* Agents provide independent, asynchronous change of individual locations.
* Agents are bound to a single storage location for their lifetime, and
* only allow mutation of that location (to a new state) to occur as a
* result of an action. Actions are functions (with, optionally, additional
* arguments) that are asynchronously applied to an Agent's state and whose
* return value becomes the Agent's new state. Because the set of functions
* is open, the set of actions supported by an Agent is also open, a sharp
* contrast to pattern matching message handling loops provided by Actors.
* <p/>
*
* Agents are reactive, not autonomous - there is no imperative message loop
* and no blocking receive. The state of an Agent should be itself immutable
* (preferably an instance of one of Akka's persistent collections), and the
* state of an Agent is always immediately available for reading by any
* thread (using the '()' function) without any messages, i.e. observation
* does not require cooperation or coordination.
* <p/>
*
* The actions of all Agents get interleaved amongst threads in a thread pool.
* At any point in time, at most one action for each Agent is being executed.
* Actions dispatched to an agent from another single agent or thread will
* occur in the order they were sent, potentially interleaved with actions
* dispatched to the same agent from other sources.
* <p/>
*
* If an Agent is used within an enclosing transaction, then it will
* participate in that transaction.
* <p/>
*
* Example of usage:
* <pre>
* val agent = Agent(5)
*
* agent send (_ + 1)
* agent send (_ * 2)
*
* val result = agent()
* ... // use result
*
* agent.close
* </pre>
* <p/>
*
* Agent is also monadic, which means that you can compose operations using
* for-comprehensions. In monadic usage the original agents are not touched
* but new agents are created. So the old values (agents) are still available
* as-is. They are so-called 'persistent'.
* <p/>
*
* Example of monadic usage:
* <pre>
* val agent1 = Agent(3)
* val agent2 = Agent(5)
*
* for (value <- agent1) {
*   result = value + 1
* }
*
* val agent3 =
*   for (value <- agent1) yield value + 1
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
* </pre>
* <p/>
*
* <b>IMPORTANT</b>:
* <p/>
* You can *not* call 'agent.get', 'agent()' or use the monadic 'foreach',
* 'map' and 'flatMap' within an enclosing transaction since that would block
* the transaction indefinitely. But all other operations are fine. The system
* will raise an error (e.g. *not* deadlock) if you try to do so, so as long as
* you test your application thoroughly you should be fine.
*
* @author Viktor Klang
* @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
*/
sealed class Agent[T] private (initialValue: T, remote: Option[RemoteAddress] = None) {

  import Agent._
  import Actor._

  val dispatcher = remote match {
    case Some(address) =>
      val d = actorOf(new AgentDispatcher[T]())
      d.makeRemote(remote.get.hostname, remote.get.port)
      d.start
      d ! Value(initialValue)
      d
    case None =>
      actorOf(new AgentDispatcher(initialValue)).start
  }

  /**
  * Submits a request to read the internal state.
  *
  * A copy of the internal state will be returned, depending on the underlying
  * effective copyStrategy. Internally leverages the asynchronous getValue()
  * method and then waits for its result on a CountDownLatch.
  */
  final def get: T = {
    if (dispatcher.isTransactionInScope) throw new AgentException(
      "Can't call Agent.get within an enclosing transaction."+
      "\n\tWould block indefinitely.\n\tPlease refactor your code.")
    val f = (dispatcher.!!![T](Read, java.lang.Long.MAX_VALUE)).await
    if (f.exception.isDefined) throw f.exception.get
    else f.result.getOrElse(throw new IllegalStateException("Agent remote request timed out"))
  }

  /**
   * Submits a request to read the internal state. A copy of the internal state will be
   * returned, depending on the underlying effective copyStrategy. Internally leverages
   * the asynchronous getValue() method and then waits for its result on a CountDownLatch.
   */
  final def apply(): T = get

  /**
   * Submits the provided function for execution against the internal agent's state.
   */
  final def apply(message: (T => T)): Unit = dispatcher ! Function(message)

  /**
   * Submits the provided function for execution against the internal agent's state.
   * Java API
   */
  final def apply(message: JFunc[T,T]): Unit = dispatcher ! Function((t: T) => message(t))


  /**
   * Submits a new value to be set as the new agent's internal state.
   */
  final def apply(message: T): Unit = dispatcher ! Value(message)

  /**
   * Submits the provided function of type 'T => T' for execution against the internal agent's state.
   */
  final def send(message: (T) => T): Unit = dispatcher ! Function(message)

  /**
   * Submits the provided function of type 'T => T' for execution against the internal agent's state.
   * Java API
   */
  final def send(message: JFunc[T,T]): Unit = dispatcher ! Function((t: T) => message(t))

  /**
   * Submits a new value to be set as the new agent's internal state.
   */
  final def send(message: T): Unit = dispatcher ! Value(message)

  /**
   * Asynchronously submits a procedure of type 'T => Unit' to read the internal state.
   * The supplied procedure will be executed on the returned internal state value. A copy
   * of the internal state will be used, depending on the underlying effective copyStrategy.
   * Does not change the value of the agent (this).
   */
  final def sendProc(f: (T) => Unit): Unit = dispatcher ! Procedure(f)

  /**
   * Asynchronously submits a procedure of type 'T => Unit' to read the internal state.
   * The supplied procedure will be executed on the returned internal state value. A copy
   * of the internal state will be used, depending on the underlying effective copyStrategy.
   * Does not change the value of the agent (this).
   * Java API
   */
  final def sendProc(f: JProc[T]): Unit = dispatcher ! Procedure((t: T) => f(t))

  /**
   * Applies function with type 'T => B' to the agent's internal state and then returns a new agent with the result.
   * Does not change the value of the agent (this).
   */
  final def map[B](f: (T) => B): Agent[B] = Agent(f(get), remote)

  /**
   * Applies function with type 'T => B' to the agent's internal state and then returns a new agent with the result.
   * Does not change the value of the agent (this).
   */
  final def flatMap[B](f: (T) => Agent[B]): Agent[B] = Agent(f(get)(), remote)

  /**
   * Applies function with type 'T => B' to the agent's internal state.
   * Does not change the value of the agent (this).
   */
  final def foreach(f: (T) => Unit): Unit = f(get)

  /**
   * Applies function with type 'T => B' to the agent's internal state and then returns a new agent with the result.
   * Does not change the value of the agent (this).
   * Java API
   */
  final def map[B](f: JFunc[T, B]): Agent[B] = Agent(f(get), remote)

  /**
   * Applies function with type 'T => B' to the agent's internal state and then returns a new agent with the result.
   * Does not change the value of the agent (this).
   * Java API
   */
  final def flatMap[B](f: JFunc[T, Agent[B]]): Agent[B] = Agent(f(get)(), remote)

  /**
   * Applies procedure with type T to the agent's internal state.
   * Does not change the value of the agent (this).
   * Java API
   */
  final def foreach(f: JProc[T]): Unit = f(get)

  /**
   * Closes the agents and makes it eligable for garbage collection.
   *
   * A closed agent can never be used again.
   */
  def close = dispatcher.stop
}

/**
 * Provides factory methods to create Agents.
 *
 * @author Viktor Klang
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Agent {
  import Actor._
  
  /**
   * The internal messages for passing around requests.
   */
  private[akka] case class Value[T](value: T)
  private[akka] case class Function[T](fun: ((T) => T))
  private[akka] case class Procedure[T](fun: ((T) => Unit))
  private[akka] case object Read

  /**
   * Creates a new Agent of type T with the initial value of value.
   */
  def apply[T](value: T): Agent[T] =
    apply(value, None)

  /**
   * Creates an Agent backed by a client managed Actor if Some(remoteAddress)
   * or a local agent if None
   */
  def apply[T](value: T, remoteAddress: Option[RemoteAddress]): Agent[T] =
    new Agent[T](value, remoteAddress)

  /**
   * Creates an Agent backed by a client managed Actor
   */
  def apply[T](value: T, remoteAddress: RemoteAddress): Agent[T] =
    apply(value, Some(remoteAddress))
}

/**
 * Agent dispatcher Actor.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final class AgentDispatcher[T] private (ref: Ref[T]) extends Transactor {
  import Agent._

  private[akka] def this(initialValue: T) = this(Ref(initialValue))
  private[akka] def this() = this(Ref[T]())

  private val value = ref

  log.debug("Starting up Agent [%s]", self.uuid)

  /**
   * Periodically handles incoming messages.
   */
  def receive = {
    case Value(v: T)                  => swap(v)
    case Read                         => self.reply_?(value.get())
    case Function(fun: (T => T))      => swap(fun(value.getOrWait))
    case Procedure(proc: (T => Unit)) => proc(value.getOrElse(
        throw new AgentException("Could not read Agent's value; value is null")))
  }

  /**
   * Performs a CAS operation, atomically swapping the internal state with the value
   * provided as a by-name parameter.
   */
  private def swap(newData: => T): Unit = value.swap(newData)
}
