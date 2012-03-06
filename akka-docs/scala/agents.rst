.. _agents-scala:

################
 Agents (Scala)
################

Agents in Akka are inspired by `agents in Clojure`_.

.. _agents in Clojure: http://clojure.org/agents

Agents provide asynchronous change of individual locations. Agents are bound to
a single storage location for their lifetime, and only allow mutation of that
location (to a new state) to occur as a result of an action. Update actions are
functions that are asynchronously applied to the Agent's state and whose return
value becomes the Agent's new state. The state of an Agent should be immutable.

While updates to Agents are asynchronous, the state of an Agent is always
immediately available for reading by any thread (using ``get`` or ``apply``)
without any messages.

Agents are reactive. The update actions of all Agents get interleaved amongst
threads in a thread pool. At any point in time, at most one ``send`` action for
each Agent is being executed. Actions dispatched to an agent from another thread
will occur in the order they were sent, potentially interleaved with actions
dispatched to the same agent from other sources.

If an Agent is used within an enclosing transaction, then it will participate in
that transaction. Agents are integrated with Scala STM - any dispatches made in
a transaction are held until that transaction commits, and are discarded if it
is retried or aborted.


Creating and stopping Agents
============================

Agents are created by invoking ``Agent(value)`` passing in the Agent's initial
value:

.. includecode:: code/akka/docs/agent/AgentDocSpec.scala#create

Note that creating an Agent requires an implicit ``ActorSystem`` (for creating
the underlying actors). See :ref:`actor-systems` for more information about
actor systems. An ActorSystem can be in implicit scope when creating an Agent:

.. includecode:: code/akka/docs/agent/AgentDocSpec.scala#create-implicit-system

Or the ActorSystem can be passed explicitly when creating an Agent:

.. includecode:: code/akka/docs/agent/AgentDocSpec.scala#create-explicit-system

An Agent will be running until you invoke ``close`` on it. Then it will be
eligible for garbage collection (unless you hold on to it in some way).

.. includecode:: code/akka/docs/agent/AgentDocSpec.scala#close


Updating Agents
===============

You update an Agent by sending a function that transforms the current value or
by sending just a new value. The Agent will apply the new value or function
atomically and asynchronously. The update is done in a fire-forget manner and
you are only guaranteed that it will be applied. There is no guarantee of when
the update will be applied but dispatches to an Agent from a single thread will
occur in order. You apply a value or a function by invoking the ``send``
function.

.. includecode:: code/akka/docs/agent/AgentDocSpec.scala#send

You can also dispatch a function to update the internal state but on its own
thread. This does not use the reactive thread pool and can be used for
long-running or blocking operations. You do this with the ``sendOff``
method. Dispatches using either ``sendOff`` or ``send`` will still be executed
in order.

.. includecode:: code/akka/docs/agent/AgentDocSpec.scala#send-off


Reading an Agent's value
========================

Agents can be dereferenced (you can get an Agent's value) by invoking the Agent
with parentheses like this:

.. includecode:: code/akka/docs/agent/AgentDocSpec.scala#read-apply

Or by using the get method:

.. includecode:: code/akka/docs/agent/AgentDocSpec.scala#read-get

Reading an Agent's current value does not involve any message passing and
happens immediately. So while updates to an Agent are asynchronous, reading the
state of an Agent is synchronous.


Awaiting an Agent's value
=========================

It is also possible to read the value after all currently queued sends have
completed. You can do this with ``await``:

.. includecode:: code/akka/docs/agent/AgentDocSpec.scala#read-await

You can also get a ``Future`` to this value, that will be completed after the
currently queued updates have completed:

.. includecode:: code/akka/docs/agent/AgentDocSpec.scala#read-future


Transactional Agents
====================

If an Agent is used within an enclosing transaction, then it will participate in
that transaction. If you send to an Agent within a transaction then the dispatch
to the Agent will be held until that transaction commits, and discarded if the
transaction is aborted. Here's an example:

.. includecode:: code/akka/docs/agent/AgentDocSpec.scala#transfer-example


Monadic usage
=============

Agents are also monadic, allowing you to compose operations using
for-comprehensions. In monadic usage, new Agents are created leaving the
original Agents untouched. So the old values (Agents) are still available
as-is. They are so-called 'persistent'.

Example of monadic usage:

.. includecode:: code/akka/docs/agent/AgentDocSpec.scala#monadic-example
