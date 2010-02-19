// ScalaAgent
//
// Copyright © 2008-9 The original author or authors
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

import se.scalablesolutions.akka.state.TransactionalState
import se.scalablesolutions.akka.stm.Transaction.atomic

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch}

/**
* The Agent class was strongly inspired by the agent principle in Clojure. Essentially, an agent wraps a shared mutable state
* and hides it behind a message-passing interface. Agents accept messages and process them on behalf of the wrapped state.
* Typically agents accept functions / commands as messages and ensure the submitted commands are executed against the internal
* agent's state in a thread-safe manner (sequentially).
* The submitted functions / commands take the internal state as a parameter and their output becomes the new internal state value.
* The code that is submitted to an agent doesn't need to pay attention to threading or synchronization, the agent will
* provide such guarantees by itself.
* See the examples of use for more details.
*
* @author Vaclav Pech
* Date: Oct 18, 2009
*
* AKKA retrofit by
* @author Viktor Klang
* Date: Jan 24 2010
*/
sealed class Agent[T] private (initialValue: T) extends Actor {
  import Agent._

  private val value = TransactionalState.newRef[T]

  updateData(initialValue)

  /**
  * Periodically handles incoming messages
  */
  def receive = {
    case FunctionHolder(fun: (T => T)) => atomic { updateData(fun(value.getOrWait)) }

    case ValueHolder(x: T) => updateData(x)

    case ProcedureHolder(fun: (T => Unit)) => atomic { fun(copyStrategy(value.getOrWait)) }
  }

  /**
   * Specifies how a copy of the value is made, defaults to using identity
   */
  protected def copyStrategy(t : T) : T = t


  /**
  * Updates the internal state with the value provided as a by-name parameter
  */
  private final def updateData(newData: => T) : Unit = atomic { value.swap(newData) }

  /**
  * Submits a request to read the internal state.
  * A copy of the internal state will be returned, depending on the underlying effective copyStrategy.
  * Internally leverages the asynchronous getValue() method and then waits for its result on a CountDownLatch.
  */
  final def get : T = {
    val ref = new AtomicReference[T]
    val latch = new CountDownLatch(1)
    get((x: T) => {ref.set(x); latch.countDown})
    latch.await
    ref.get
  }

  /**
  * Asynchronously submits a request to read the internal state. The supplied function will be executed on the returned internal state value.
  * A copy of the internal state will be used, depending on the underlying effective copyStrategy.
  */
  final def get(message: (T => Unit)) : Unit = this ! ProcedureHolder(message)

  /**
  * Submits a request to read the internal state.
  * A copy of the internal state will be returned, depending on the underlying effective copyStrategy.
  * Internally leverages the asynchronous getValue() method and then waits for its result on a CountDownLatch.
  */
  final def apply() : T = get

  /**
  * Asynchronously submits a request to read the internal state. The supplied function will be executed on the returned internal state value.
  * A copy of the internal state will be used, depending on the underlying effective copyStrategy.
  */
// final def apply(message: (T => Unit)) : Unit = get(message)

  /**
  * Submits the provided function for execution against the internal agent's state
  */
  final def apply(message: (T => T)) : Unit = this ! FunctionHolder(message)

  /**
  * Submits a new value to be set as the new agent's internal state
  */
  final def apply(message: T) : Unit = this ! ValueHolder(message)

  /**
  * Submits the provided function for execution against the internal agent's state
  */
  final def update(message: (T => T)) : Unit = this ! FunctionHolder(message)

  /**
  * Submits a new value to be set as the new agent's internal state
  */
  final def update(message: T) : Unit = this ! ValueHolder(message)
}

/**
* Provides factory methods to create Agents.
*/
object Agent {
  /**
  * The internal messages for passing around requests
  */
  private case class ProcedureHolder[T](val fun: ((T) => Unit))
  private case class FunctionHolder[T](val fun: ((T) => T))
  private case class ValueHolder[T](val value: T)

  /**
   * Creates a new Agent of type T with the initial value of value
   */
  def apply[T](value:T) : Agent[T] = new Agent(value)

  /**
   * Creates a new Agent of type T with the initial value of value and with the specified copy function
   */
  def apply[T](value:T, newCopyStrategy: (T) => T) = new Agent(value) {
    override def copyStrategy(t : T) = newCopyStrategy(t)
  }
}
