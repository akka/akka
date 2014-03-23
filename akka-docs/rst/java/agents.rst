.. _agents-java:

########
 Agents
########

Agents in Akka are inspired by `agents in Clojure`_.

.. _agents in Clojure: http://clojure.org/agents

Agents provide asynchronous change of individual locations. Agents are bound to
a single storage location for their lifetime, and only allow mutation of that
location (to a new state) to occur as a result of an action. Update actions are
functions that are asynchronously applied to the Agent's state and whose return
value becomes the Agent's new state. The state of an Agent should be immutable.

While updates to Agents are asynchronous, the state of an Agent is always
immediately available for reading by any thread (using ``get``) without any messages.

Agents are reactive. The update actions of all Agents get interleaved amongst
threads in an ``ExecutionContext``. At any point in time, at most one ``send`` action for
each Agent is being executed. Actions dispatched to an agent from another thread
will occur in the order they were sent, potentially interleaved with actions
dispatched to the same agent from other threads.



Creating Agents
===============

Agents are created by invoking ``new Agent<ValueType>(value, executionContext)`` – passing in the Agent's initial
value and providing an ``ExecutionContext`` to be used for it:

.. includecode:: code/docs/agent/AgentDocTest.java
   :include: import-agent,create
   :language: java

Reading an Agent's value
========================

Agents can be dereferenced (you can get an Agent's value) by invoking the Agent
with ``get()`` like this:

.. includecode:: code/docs/agent/AgentDocTest.java#read-get
   :language: java

Reading an Agent's current value does not involve any message passing and
happens immediately. So while updates to an Agent are asynchronous, reading the
state of an Agent is synchronous.

You can also get a ``Future`` to the Agents value, that will be completed after the
currently queued updates have completed:

.. includecode:: code/docs/agent/AgentDocTest.java
   :include: import-future,read-future
   :language: java

See :ref:`futures-java` for more information on ``Futures``.

Updating Agents (send & alter)
==============================

You update an Agent by sending a function (``akka.dispatch.Mapper``) that transforms the current value or
by sending just a new value. The Agent will apply the new value or function
atomically and asynchronously. The update is done in a fire-forget manner and
you are only guaranteed that it will be applied. There is no guarantee of when
the update will be applied but dispatches to an Agent from a single thread will
occur in order. You apply a value or a function by invoking the ``send``
function.

.. includecode:: code/docs/agent/AgentDocTest.java
   :include: import-function,send
   :language: java

You can also dispatch a function to update the internal state but on its own
thread. This does not use the reactive thread pool and can be used for
long-running or blocking operations. You do this with the ``sendOff``
method. Dispatches using either ``sendOff`` or ``send`` will still be executed
in order.

.. includecode:: code/docs/agent/AgentDocTest.java
   :include: import-function,send-off
   :language: java

All ``send`` methods also have a corresponding ``alter`` method that returns a ``Future``.
See :ref:`futures-java` for more information on ``Futures``.

.. includecode:: code/docs/agent/AgentDocTest.java
   :include: import-future,import-function,alter
   :language: java

.. includecode:: code/docs/agent/AgentDocTest.java
   :include: import-future,import-function,alter-off
   :language: java

Configuration
=============

There are several configuration properties for the agents module, please refer
to the :ref:`reference configuration <config-akka-agent>`.

Deprecated Transactional Agents
===============================

Agents participating in enclosing STM transaction is a deprecated feature in 2.3.

If an Agent is used within an enclosing ``Scala STM transaction``, then it will participate in
that transaction. If you send to an Agent within a transaction then the dispatch
to the Agent will be held until that transaction commits, and discarded if the
transaction is aborted.
