Agents (Scala)
==============

.. sidebar:: Contents

   .. contents:: :local:
   
Agents in Akka were inspired by `agents in Clojure <http://clojure.org/agents>`_.

Agents provide asynchronous change of individual locations. Agents are bound to a single storage location for their lifetime, and only allow mutation of that location (to a new state) to occur as a result of an action. Update actions are functions that are asynchronously applied to the Agent's state and whose return value becomes the Agent's new state. The state of an Agent should be immutable.

While updates to Agents are asynchronous, the state of an Agent is always immediately available for reading by any thread (using ``get`` or ``apply``) without any messages.

Agents are reactive. The update actions of all Agents get interleaved amongst threads in a thread pool. At any point in time, at most one ``send`` action for each Agent is being executed. Actions dispatched to an agent from another thread will occur in the order they were sent, potentially interleaved with actions dispatched to the same agent from other sources.

If an Agent is used within an enclosing transaction, then it will participate in that transaction. Agents are integrated with the STM - any dispatches made in a transaction are held until that transaction commits, and are discarded if it is retried or aborted.

Creating and stopping Agents
----------------------------

Agents are created by invoking ``Agent(value)`` passing in the Agent's initial value.

.. code-block:: scala

  val agent = Agent(5)

An Agent will be running until you invoke ``close`` on it. Then it will be eligible for garbage collection (unless you hold on to it in some way).

.. code-block:: scala

  agent.close()

Updating Agents
---------------

You update an Agent by sending a function that transforms the current value or by sending just a new value. The Agent will apply the new value or function atomically and asynchronously. The update is done in a fire-forget manner and you are only guaranteed that it will be applied. There is no guarantee of when the update will be applied but dispatches to an Agent from a single thread will occur in order. You apply a value or a function by invoking the ``send`` function.

.. code-block:: scala

  // send a value
  agent send 7

  // send a function
  agent send (_ + 1)
  agent send (_ * 2)

You can also dispatch a function to update the internal state but on its own thread. This does not use the reactive thread pool and can be used for long-running or blocking operations. You do this with the ``sendOff`` method. Dispatches using either ``sendOff`` or ``send`` will still be executed in order.

.. code-block:: scala

  // sendOff a function
  agent sendOff (longRunningOrBlockingFunction)

Reading an Agent's value
------------------------

Agents can be dereferenced, e.g. you can get an Agent's value, by invoking the Agent with parenthesis like this:

.. code-block:: scala

  val result = agent()

Or by using the get method.

.. code-block:: scala

  val result = agent.get

Reading an Agent's current value does not involve any message passing and happens immediately. So while updates to an Agent are asynchronous, reading the state of an Agent is synchronous.

Awaiting an Agent's value
-------------------------

It is also possible to read the value after all currently queued ``send``\s have completed. You can do this with ``await``:

.. code-block:: scala

  val result = agent.await

You can also get a ``Future`` to this value, that will be completed after the currently queued updates have completed:

.. code-block:: scala

  val future = agent.future
  // ...
  val result = future.await.result.get

Transactional Agents
--------------------

If an Agent is used within an enclosing transaction, then it will participate in that transaction. If you send to an Agent within a transaction then the dispatch to the Agent will be held until that transaction commits, and discarded if the transaction is aborted.

.. code-block:: scala

  import akka.agent.Agent
  import akka.stm._

  def transfer(from: Agent[Int], to: Agent[Int], amount: Int): Boolean = {
    atomic {
      if (from.get < amount) false
      else {
        from send (_ - amount)
        to send (_ + amount)
        true
      }
    }
  }

  val from = Agent(100)
  val to = Agent(20)
  val ok = transfer(from, to, 50)

  from() // -> 50
  to()   // -> 70

Monadic usage
-------------

Agents are also monadic, allowing you to compose operations using for-comprehensions. In a monadic usage, new Agents are created leaving the original Agents untouched. So the old values (Agents) are still available as-is. They are so-called 'persistent'.

Example of a monadic usage:

.. code-block:: scala

  val agent1 = Agent(3)
  val agent2 = Agent(5)

  // uses foreach
  var result = 0
  for (value <- agent1) {
    result = value + 1
  }

  // uses map
  val agent3 =
    for (value <- agent1) yield value + 1

  // uses flatMap
  val agent4 = for {
    value1 <- agent1
    value2 <- agent2
  } yield value1 + value2

  agent1.close()
  agent2.close()
  agent3.close()
  agent4.close()
