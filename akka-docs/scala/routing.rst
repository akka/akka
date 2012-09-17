
.. _routing-scala:

Routing (Scala)
===============

A Router is an actor that routes incoming messages to outbound actors.
The router routes the messages sent to it to its underlying actors called 'routees'.

Akka comes with some defined routers out of the box, but as you will see in this chapter it
is really easy to create your own. The routers shipped with Akka are:

* ``akka.routing.RoundRobinRouter``
* ``akka.routing.RandomRouter``
* ``akka.routing.SmallestMailboxRouter``
* ``akka.routing.BroadcastRouter``
* ``akka.routing.ScatterGatherFirstCompletedRouter``
* ``akka.routing.ConsistentHashingRouter``

Routers In Action
^^^^^^^^^^^^^^^^^

This is an example of how to create a router that is defined in configuration:

.. includecode:: code/docs/routing/RouterViaConfigDocSpec.scala#config-round-robin

.. includecode:: code/docs/routing/RouterViaConfigDocSpec.scala#configurableRouting

This is an example of how to programmatically create a router and set the number of routees it should create:

.. includecode:: code/docs/routing/RouterViaProgramExample.scala#programmaticRoutingNrOfInstances

You can also give the router already created routees as in:

.. includecode:: code/docs/routing/RouterViaProgramExample.scala#programmaticRoutingRoutees

.. note::

    No actor factory or class needs to be provided in this
    case, as the ``Router`` will not create any children on its own (which is not
    true anymore when using a resizer). The routees can also be specified by giving
    their path strings.


When you create a router programmatically you define the number of routees *or* you pass already created routees to it.
If you send both parameters to the router *only* the latter will be used, i.e. ``nrOfInstances`` is disregarded.

*It is also worth pointing out that if you define the ``router`` in the
configuration file then this value will be used instead of any programmatically
sent parameters. The decision whether to create a router at all, on the other
hand, must be taken within the code, i.e. you cannot make something a router by
external configuration alone (see below for details).*

Once you have the router actor it is just to send messages to it as you would to any actor:

.. code-block:: scala

  router ! MyMsg

The router will forward the message to its routees according to its routing policy.

Remotely Deploying Routees
**************************

In addition to being able to supply looked-up remote actors as routees, you can
make the router deploy its created children on a set of remote hosts; this will
be done in round-robin fashion. In order to do that, wrap the router
configuration in a :class:`RemoteRouterConfig`, attaching the remote addresses of
the nodes to deploy to. Naturally, this requires your to include the
``akka-remote`` module on your classpath:

.. includecode:: code/docs/routing/RouterViaProgramExample.scala#remoteRoutees

How Routing is Designed within Akka
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Routers behave like single actors, but they should also not hinder scalability.
This apparent contradiction is solved by making routers be represented by a
special :class:`RoutedActorRef` (implementation detail, what the user gets is
an :class:`ActorRef` as usual) which dispatches incoming messages destined
for the routees without actually invoking the router actor’s behavior (and thus
avoiding its mailbox; the single router actor’s task is to manage all aspects
related to the lifecycle of the routees). This means that the code which decides
which route to take is invoked concurrently from all possible senders and hence
must be thread-safe, it cannot live the simple and happy life of code within an
actor.

There is one part in the above paragraph which warrants some more background
explanation: Why does a router need a “head” which is actual parent to all the
routees? The initial design tried to side-step this issue, but location
transparency as well as mandatory parental supervision required a redesign.
Each of the actors which the router spawns must have its unique identity, which
translates into a unique actor path. Since the router has only one given name
in its parent’s context, another level in the name space is needed, which
according to the addressing semantics implies the existence of an actor with
the router’s name. This is not only necessary for the internal messaging
involved in creating, restarting and terminating actors, it is also needed when
the pooled actors need to converse with other actors and receive replies in a
deterministic fashion. Since each actor knows its own external representation
as well as that of its parent, the routees decide where replies should be sent
when reacting to a message:

.. includecode:: code/docs/actor/ActorDocSpec.scala#reply-with-sender

.. includecode:: code/docs/actor/ActorDocSpec.scala#reply-without-sender

It is apparent now why routing needs to be enabled in code rather than being
possible to “bolt on” later: whether or not an actor is routed means a change
to the actor hierarchy, changing the actor paths of all children of the router.
The routees especially do need to know that they are routed to in order to
choose the sender reference for any messages they dispatch as shown above.

Routers vs. Supervision
^^^^^^^^^^^^^^^^^^^^^^^

As explained in the previous section, routers create new actor instances as
children of the “head” router, who therefor also is their supervisor. The
supervisor strategy of this actor can be configured by means of the
:meth:`RouterConfig.supervisorStrategy` property, which is supported for all
built-in router types. It defaults to “always escalate”, which leads to the
application of the router’s parent’s supervision directive to all children of
the router uniformly (i.e. not only the one which failed). It should be
mentioned that the router overrides the default behavior of terminating all
children upon restart, which means that a restart—while re-creating them—does
not have an effect on the number of actors in the pool.

Setting the strategy is easily done:

.. includecode:: ../../akka-actor-tests/src/test/scala/akka/routing/RoutingSpec.scala#supervision
   :include: supervision
   :exclude: custom-strategy

Another potentially useful approach is to give the router the same strategy as
its parent, which effectively treats all actors in the pool as if they were
direct children of their grand-parent instead.

.. note::

  If the child of a router terminates, the router will not automatically spawn
  a new child. In the event that all children of a router have terminated the
  router will terminate itself.

Router usage
^^^^^^^^^^^^

In this section we will describe how to use the different router types.
First we need to create some actors that will be used in the examples:

.. includecode:: code/docs/routing/RouterTypeExample.scala#printlnActor

and

.. includecode:: code/docs/routing/RouterTypeExample.scala#fibonacciActor


RoundRobinRouter
****************
Routes in a `round-robin <http://en.wikipedia.org/wiki/Round-robin>`_ fashion to its routees.
Code example:

.. includecode:: code/docs/routing/RouterTypeExample.scala#roundRobinRouter

When run you should see a similar output to this:

.. code-block:: scala

  Received message '1' in actor $b
  Received message '2' in actor $c
  Received message '3' in actor $d
  Received message '6' in actor $b
  Received message '4' in actor $e
  Received message '8' in actor $d
  Received message '5' in actor $f
  Received message '9' in actor $e
  Received message '10' in actor $f
  Received message '7' in actor $c

If you look closely to the output you can see that each of the routees received two messages which
is exactly what you would expect from a round-robin router to happen.
(The name of an actor is automatically created in the format ``$letter`` unless you specify it -
hence the names printed above.)

This is an example of how to define a round-robin router in configuration:

.. includecode:: code/docs/routing/RouterViaConfigDocSpec.scala#config-round-robin

RandomRouter
************
As the name implies this router type selects one of its routees randomly and forwards
the message it receives to this routee.
This procedure will happen each time it receives a message.
Code example:

.. includecode:: code/docs/routing/RouterTypeExample.scala#randomRouter

When run you should see a similar output to this:

.. code-block:: scala

  Received message '1' in actor $e
  Received message '2' in actor $c
  Received message '4' in actor $b
  Received message '5' in actor $d
  Received message '3' in actor $e
  Received message '6' in actor $c
  Received message '7' in actor $d
  Received message '8' in actor $e
  Received message '9' in actor $d
  Received message '10' in actor $d

The result from running the random router should be different, or at least random, every time you run it.
Try to run it a couple of times to verify its behavior if you don't trust us.

This is an example of how to define a random router in configuration:

.. includecode:: code/docs/routing/RouterViaConfigDocSpec.scala#config-random

SmallestMailboxRouter
*********************
A Router that tries to send to the non-suspended routee with fewest messages in mailbox.
The selection is done in this order:

 * pick any idle routee (not processing message) with empty mailbox
 * pick any routee with empty mailbox
 * pick routee with fewest pending messages in mailbox
 * pick any remote routee, remote actors are consider lowest priority,
   since their mailbox size is unknown

Code example:

.. includecode:: code/docs/routing/RouterTypeExample.scala#smallestMailboxRouter


This is an example of how to define a smallest-mailbox router in configuration:

.. includecode:: code/docs/routing/RouterViaConfigDocSpec.scala#config-smallest-mailbox

BroadcastRouter
***************
A broadcast router forwards the message it receives to *all* its routees.
Code example:

.. includecode:: code/docs/routing/RouterTypeExample.scala#broadcastRouter

When run you should see a similar output to this:

.. code-block:: scala

  Received message 'this is a broadcast message' in actor $f
  Received message 'this is a broadcast message' in actor $d
  Received message 'this is a broadcast message' in actor $e
  Received message 'this is a broadcast message' in actor $c
  Received message 'this is a broadcast message' in actor $b

As you can see here above each of the routees, five in total, received the broadcast message.

This is an example of how to define a broadcast router in configuration:

.. includecode:: code/docs/routing/RouterViaConfigDocSpec.scala#config-broadcast


ScatterGatherFirstCompletedRouter
*********************************
The ScatterGatherFirstCompletedRouter will send the message on to all its routees as a future.
It then waits for first result it gets back. This result will be sent back to original sender.
Code example:

.. includecode:: code/docs/routing/RouterTypeExample.scala#scatterGatherFirstCompletedRouter

When run you should see this:

.. code-block:: scala

  The result of calculating Fibonacci for 10 is 55

From the output above you can't really see that all the routees performed the calculation, but they did!
The result you see is from the first routee that returned its calculation to the router.

This is an example of how to define a scatter-gather router in configuration:

.. includecode:: code/docs/routing/RouterViaConfigDocSpec.scala#config-scatter-gather


ConsistentHashingRouter
***********************

The ConsistentHashingRouter uses `consistent hashing <http://en.wikipedia.org/wiki/Consistent_hashing>`_
to select a connection based on the sent message. This 
`article <http://weblogs.java.net/blog/tomwhite/archive/2007/11/consistent_hash.html>`_ gives good 
insight into how consistent hashing is implemented.

There is 3 ways to define what data to use for the consistent hash key.

* You can define ``consistentHashRoute`` of the router to map incoming
  messages to their consistent hash key. This makes the decision
  transparent for the sender.

* The messages may implement ``akka.routing.ConsistentHashingRouter.ConsistentHashable``.
  The key is part of the message and it's convenient to define it together
  with the message definition.
 
* The messages can be be wrapped in a ``akka.routing.ConsistentHashingRouter.ConsistentHashableEnvelope``
  to define what data to use for the consistent hash key. The sender knows
  the key to use.
 
These ways to define the consistent hash key can be use together and at
the same time for one router. The ``consistentHashRoute`` is tried first.

Code example:

.. includecode:: code/docs/routing/ConsistentHashingRouterDocSpec.scala#cache-actor

.. includecode:: code/docs/routing/ConsistentHashingRouterDocSpec.scala#consistent-hashing-router

In the above example you see that the ``Get`` message implements ``ConsistentHashable`` itself,
while the ``Entry`` message is wrapped in a ``ConsistentHashableEnvelope``. The ``Evict``
message is handled by the ``consistentHashRoute`` partial function.

This is an example of how to define a consistent-hashing router in configuration:

.. includecode:: code/docs/routing/RouterViaConfigDocSpec.scala#config-consistent-hashing


Broadcast Messages
^^^^^^^^^^^^^^^^^^

There is a special type of message that will be sent to all routees regardless of the router.
This message is called ``Broadcast`` and is used in the following manner:

.. code-block:: scala

  router ! Broadcast("Watch out for Davy Jones' locker")

Only the actual message is forwarded to the routees, i.e. "Watch out for Davy Jones' locker" in the example above.
It is up to the routee implementation whether to handle the broadcast message or not.

Dynamically Resizable Routers
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

All routers can be used with a fixed number of routees or with a resize strategy to adjust the number
of routees dynamically.

This is an example of how to create a resizable router that is defined in configuration:

.. includecode:: code/docs/routing/RouterViaConfigDocSpec.scala#config-resize

.. includecode:: code/docs/routing/RouterViaConfigDocSpec.scala#configurableRoutingWithResizer

Several more configuration options are available and described in ``akka.actor.deployment.default.resizer``
section of the reference :ref:`configuration`.

This is an example of how to programmatically create a resizable router:

.. includecode:: code/docs/routing/RouterViaProgramExample.scala#programmaticRoutingWithResizer

*It is also worth pointing out that if you define the ``router`` in the configuration file then this value
will be used instead of any programmatically sent parameters.*

.. note::

  Resizing is triggered by sending messages to the actor pool, but it is not
  completed synchronously; instead a message is sent to the “head”
  :class:`Router` to perform the size change. Thus you cannot rely on resizing
  to instantaneously create new workers when all others are busy, because the
  message just sent will be queued to the mailbox of a busy actor. To remedy
  this, configure the pool to use a balancing dispatcher, see `Configuring
  Dispatchers`_ for more information.

Custom Router
^^^^^^^^^^^^^

You can also create your own router should you not find any of the ones provided by Akka sufficient for your needs.
In order to roll your own router you have to fulfill certain criteria which are explained in this section.

The router created in this example is a simple vote counter. It will route the votes to specific vote counter actors.
In this case we only have two parties the Republicans and the Democrats. We would like a router that forwards all
democrat related messages to the Democrat actor and all republican related messages to the Republican actor.

We begin with defining the class:

.. includecode:: ../../akka-actor-tests/src/test/scala/akka/routing/RoutingSpec.scala#crRouter
   :exclude: crRoute

The next step is to implement the ``createRoute`` method in the class just defined:

.. includecode:: ../../akka-actor-tests/src/test/scala/akka/routing/RoutingSpec.scala#crRoute

As you can see above we start off by creating the routees and put them in a collection.

Make sure that you don't miss to implement the line below as it is *really* important.
It registers the routees internally and failing to call this method will
cause a ``ActorInitializationException`` to be thrown when the router is used.
Therefore always make sure to do the following in your custom router:

.. includecode:: ../../akka-actor-tests/src/test/scala/akka/routing/RoutingSpec.scala#crRegisterRoutees

The routing logic is where your magic sauce is applied. In our example it inspects the message types
and forwards to the correct routee based on this:

.. includecode:: ../../akka-actor-tests/src/test/scala/akka/routing/RoutingSpec.scala#crRoutingLogic

As you can see above what's returned in the partial function is a ``List`` of ``Destination(sender, routee)``.
The sender is what "parent" the routee should see - changing this could be useful if you for example want
another actor than the original sender to intermediate the result of the routee (if there is a result).
For more information about how to alter the original sender we refer to the source code of
`ScatterGatherFirstCompletedRouter <https://github.com/akka/akka/blob/master/akka-actor/src/main/scala/akka/routing/Routing.scala#L375>`_

All in all the custom router looks like this:

.. includecode:: ../../akka-actor-tests/src/test/scala/akka/routing/RoutingSpec.scala#CustomRouter

If you are interested in how to use the VoteCountRouter you can have a look at the test class
`RoutingSpec <https://github.com/akka/akka/blob/master/akka-actor-tests/src/test/scala/akka/routing/RoutingSpec.scala>`_

.. caution::

   When creating a cutom router the resulting RoutedActorRef optimizes the
   sending of the message so that it does NOT go through the router’s mailbox
   unless the route returns an empty recipient set.

   This means that the ``route`` function defined in the ``RouterConfig``
   or the function returned from ``CreateCustomRoute`` in
   ``CustomRouterConfig`` is evaluated concurrently without protection by
   the RoutedActorRef: either provide a reentrant (i.e. pure) implementation
   or do the locking yourself!


Configured Custom Router
************************

It is possible to define configuration properties for custom routers. In the ``router`` property of the deployment
configuration you define the fully qualified class name of the router class. The router class must extend
``akka.routing.RouterConfig`` and and have constructor with ``com.typesafe.config.Config`` parameter.
The deployment section of the configuration is passed to the constructor.

Custom Resizer
**************

A router with dynamically resizable number of routees is implemented by providing a ``akka.routing.Resizer``
in ``resizer`` method of the ``RouterConfig``. See ``akka.routing.DefaultResizer`` for inspiration
of how to write your own resize strategy.

Configuring Dispatchers
^^^^^^^^^^^^^^^^^^^^^^^

The dispatcher for created children of the router will be taken from
:class:`Props` as described in :ref:`dispatchers-scala`. For a dynamic pool it
makes sense to configure the :class:`BalancingDispatcher` if the precise
routing is not so important (i.e. no consistent hashing or round-robin is
required); this enables newly created routees to pick up work immediately by
stealing it from their siblings.

.. note::

   If you provide a collection of actors to route to, then they will still use the same dispatcher
   that was configured for them in their ``Props``, it is not possible to change an actors dispatcher
   after it has been created.

The “head” router cannot always run on the same dispatcher, because it
does not process the same type of messages, hence this special actor does
not use the dispatcher configured in :class:`Props`, but takes the
``routerDispatcher`` from the :class:`RouterConfig` instead, which defaults to
the actor system’s default dispatcher. All standard routers allow setting this
property in their constructor or factory method, custom routers have to
implement the method in a suitable way.

.. includecode:: code/docs/routing/RouterDocSpec.scala#dispatchers

.. note::

   It is not allowed to configure the ``routerDispatcher`` to be a
   :class:`BalancingDispatcher` since the messages meant for the special
   router actor cannot be processed by any other actor.

At first glance there seems to be an overlap between the
:class:`BalancingDispatcher` and Routers, but they complement each other.
The balancing dispatcher is in charge of running the actors while the routers
are in charge of deciding which message goes where. A router can also have
children that span multiple actor systems, even remote ones, but a dispatcher
lives inside a single actor system.

When using a :class:`RoundRobinRouter` with a :class:`BalancingDispatcher`
there are some configuration settings to take into account.

- There can only be ``nr-of-instances`` messages being processed at the same
  time no matter how many threads are configured for the
  :class:`BalancingDispatcher`.

- Having ``throughput`` set to a low number makes no sense since you will only
  be handing off to another actor that processes the same :class:`MailBox`
  as yourself, which can be costly. Either the message just got into the
  mailbox and you can receive it as well as anybody else, or everybody else
  is busy and you are the only one available to receive the message.

- Resizing the number of routees only introduce inertia, since resizing
  is performed at specified intervals, but work stealing is instantaneous.
