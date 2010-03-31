// Copyright © 2008-10 The original author or authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
 
package se.scalablesolutions.akka.actor
 
import se.scalablesolutions.akka.stm.Ref
 
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch

class AgentException private[akka](message: String) extends RuntimeException(message)
 
/**
* The Agent class was strongly inspired by the agent principle in Clojure. 
* Essentially, an agent wraps a shared mutable state and hides it behind 
* a message-passing interface. Agents accept messages and process them on 
* behalf of the wrapped state.
* 
* Typically agents accept functions / commands as messages and ensure the 
* submitted commands are executed against the internal agent's state in a 
* thread-safe manner (sequentially).
* 
* The submitted functions / commands take the internal state as a parameter 
* and their output becomes the new internal state value.
* 
* The code that is submitted to an agent doesn't need to pay attention to 
* threading or synchronization, the agent will provide such guarantees by itself.
*
* If an Agent is used within an enclosing transaction, then it will participate
* in that transaction. 
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
* 
* Example of monadic usage:
* <pre>
* val agent1 = Agent(3)
* val agent2 = Agent(5)
*
* for {
*   first <- agent1
*   second <- agent2
*   if first == second
* } process(first, second)
*
* agent1.close
* agent2.close
* </pre>
* 
* NOTE: You can't call 'agent.get' or 'agent()' within an enclosing transaction since 
* that will block the transaction indefinitely. But 'agent.send' or 'Agent(value)' 
* is fine.
* 
* @author Viktor Klang
* @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
*/
sealed class Agent[T] private (initialValue: T) extends Transactor {
  import Agent._
  private lazy val value = Ref[T]()
  
  start
  this !! Value(initialValue)
 
  /**
   * Periodically handles incoming messages.
   */
  def receive = {
    case Value(v: T) =>                  
      swap(v)
    case Function(fun: (T => T)) =>      
      swap(fun(value.getOrWait))
    case Procedure(proc: (T => Unit)) => 
      proc(copyStrategy(value.getOrElse(throw new AgentException("Could not read Agent's value; value is null"))))
  }
 
  /**
   * Specifies how a copy of the value is made, defaults to using identity.
   */
  protected def copyStrategy(t: T): T = t
 
  /**
   * Performs a CAS operation, atomically swapping the internal state with the value 
   * provided as a by-name parameter.
   */
  private final def swap(newData: => T): Unit = value.swap(newData)
 
  /**
  * Submits a request to read the internal state.
  * 
  * A copy of the internal state will be returned, depending on the underlying 
  * effective copyStrategy. Internally leverages the asynchronous getValue() 
  * method and then waits for its result on a CountDownLatch.
  */
  final def get: T = {
    if (isTransactionInScope) throw new AgentException(
      "Can't call Agent.get within an enclosing transaction.\n\tWould block indefinitely.\n\tPlease refactor your code.")
    val ref = new AtomicReference[T]
    val latch = new CountDownLatch(1)
    sendProc((x: T) => {ref.set(x); latch.countDown})
    latch.await
    ref.get
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
  final def apply(message: (T => T)): Unit = this ! Function(message)
 
  /**
   * Submits a new value to be set as the new agent's internal state.
   */
  final def apply(message: T): Unit = this ! Value(message)
 
  /**
   * Submits the provided function of type 'T => T' for execution against the internal agent's state.
   */
  final def send(message: (T) => T): Unit = this ! Function(message)
 
  /**
   * Submits a new value to be set as the new agent's internal state.
   */
  final def send(message: T): Unit = this ! Value(message)
  
  /**
   * Asynchronously submits a procedure of type 'T => Unit' to read the internal state. 
   * The supplied procedure will be executed on the returned internal state value. A copy 
   * of the internal state will be used, depending on the underlying effective copyStrategy.
   * Does not change the value of the agent (this).
   */
  final def sendProc(f: (T) => Unit): Unit = this ! Procedure(f)

  /**
   * Applies function with type 'T => B' to the agent's internal state and then returns a new agent with the result.
   * Does not change the value of the agent (this).
   */
  final def map[B](f: (T) => B): Agent[B] = Agent(f(get))

  /**
   * Applies function with type 'T => B' to the agent's internal state and then returns a new agent with the result.
   * Does not change the value of the agent (this).
   */
  final def flatMap[B](f: (T) => Agent[B]): Agent[B] = Agent(f(get)())

  /**
   * Applies function with type 'T => B' to the agent's internal state.
   * Does not change the value of the agent (this).
   */
  final def foreach(f: (T) => Unit): Unit = f(get) 

  /**
   * Closes the agents and makes it eligable for garbage collection.
   * 
   * A closed agent can never be used again.
   */
  def close = stop
}
 
/**
 * Provides factory methods to create Agents.
 *
 * @author Viktor Klang
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Agent { 

  /*
   * The internal messages for passing around requests.
   */
  private case class Value[T](value: T)
  private case class Function[T](fun: ((T) => T))
  private case class Procedure[T](fun: ((T) => Unit))
 
  /**
   * Creates a new Agent of type T with the initial value of value.
   */
  def apply[T](value: T): Agent[T] = new Agent(value)
 
  /**
   * Creates a new Agent of type T with the initial value of value and with the 
   * specified copy function.
   */
  def apply[T](value: T, newCopyStrategy: (T) => T) = new Agent(value) {
    override def copyStrategy(t: T) = newCopyStrategy(t)
  }
}
