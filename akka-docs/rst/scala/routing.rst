
.. _routing-scala:

Routing
===============

A Router is an actor that receives messages and efficiently routes them to other actors, known as
its *routees*.

Different routing strategies can be used, according to your application's needs. Akka comes with
several useful routing strategies right out of the box. But, as you will see in this chapter, it is
also possible to :ref:`create your own <custom-router-scala>`.

The routers shipped with Akka are:

* ``akka.routing.RoundRobinRouter``
* ``akka.routing.RandomRouter``
* ``akka.routing.SmallestMailboxRouter``
* ``akka.routing.BroadcastRouter``
* ``akka.routing.ScatterGatherFirstCompletedRouter``
* ``akka.routing.ConsistentHashingRouter``

Routers in Action
^^^^^^^^^^^^^^^^^

Sending a message to a router is easy.

.. code-block:: scala

  router ! MyMsg

A router actor forwards messages to its routees according to its routing policy.

.. note::

    In general, any message sent to a router will be sent onwards to its routees. But there are a
    few exceptions. These are documented in the :ref:`router-special-messages-scala` section below.

Creating a Router
*****************

Routers and routees are closely intertwined. Router actors are created by specifying the desired
*routee* :class:`Props` then attaching the router's :class:`RouterConfig`. When you create a router
actor it will create routees, as needed, as its children.

For example, the following code and configuration snippets show how to create a :ref:`round-robin
<round-robin-router-scala>` router that forwards messages to five ``ExampleActor`` routees. The
routees will be created as the router's children.

.. includecode:: code/docs/routing/RouterViaConfigDocSpec.scala#config-round-robin

.. includecode:: code/docs/routing/RouterViaConfigDocSpec.scala#configurableRouting

Here is the same example, but with the router configuration provided programmatically instead of
from configuration.

.. includecode:: code/docs/routing/RouterViaProgramExample.scala#programmaticRoutingNrOfInstances

Sometimes, rather than having the router create its routees, it is desirable to create routees
separately and provide them to the router for its use. You can do this by passing an
:class:`Iterable` of routees to the router's configuration.

The example below shows how to create a router by providing it with the :class:`ActorRef`\s of three
routee actors.

.. includecode:: code/docs/routing/RouterViaProgramExample.scala#programmaticRoutingRoutees

Routees can also be specified by providing their path strings instead of their :class:`ActorRef`\s.

.. includecode:: code/docs/routing/RouterViaProgramDocSpec.scala#programmaticRoutingRouteePaths

In addition to being able to supply looked-up remote actors as routees, you can ask the router to
deploy its created children on a set of remote hosts. Routees will be deployed in round-robin
fashion. In order to deploy routees remotely, wrap the router configuration in a
:class:`RemoteRouterConfig`, attaching the remote addresses of the nodes to deploy to. Remote
deployment requires the ``akka-remote`` module to be included in the classpath.

.. includecode:: code/docs/routing/RouterViaProgramExample.scala#remoteRoutees

There are a few gotchas to be aware of when creating routers:

* If you define the ``router`` in the configuration file then this value will be used instead of any
  programmatically provided parameters.
* Although routers can be configured in the configuration file, they must still be created
  programmatically, i.e. you cannot make a router through external configuration alone.
* If you provide the ``routees`` in the router configuration then
  the value of ``nrOfInstances``, if provided, will be disregarded.
* When you provide routees programmatically the router will generally ignore the routee
  :class:`Props`, as it does not need to create routees. However, if you use a :ref:`resizable
  router <resizable-routers-scala>` then the routee :class:`Props` will be used whenever the
  resizer creates new routees.

Routers, Routees and Senders
****************************

The router forwards messages onto its routees without changing the original sender. When a routee
replies to a routed message, the reply will be sent to the original sender, not to the router.

When a router creates routees, they are created as the routers children. This gives each routee its
own identity in the actor system.

By default, when a routee sends a message, it will :ref:`implicitly set itself as the sender
<actors-tell-sender-scala>`.

.. includecode:: code/docs/actor/ActorDocSpec.scala#reply-without-sender

However, it is often useful for routees to set the *router* as a sender. For example, you might want
to set the router as the sender if you want to hide the details of the routees behind the router.
The following code snippet shows how to set the parent router as sender.

.. includecode:: code/docs/actor/ActorDocSpec.scala#reply-with-sender

Note that different code would be needed if the routees were not children of the router, i.e. if
they were provided when the router was created.

Routers and Supervision
^^^^^^^^^^^^^^^^^^^^^^^

Routees can be created by a router or provided to the router when it is created. Any routees that
are created by a router will be created as the router's children. The router is therefore also the
children's supervisor.

The supervision strategy of the router actor can be configured with the
:meth:`RouterConfig.supervisorStrategy` property. If no configuration is provided, routers default
to a strategy of “always escalate”. This means that errors are passed up to the router's supervisor
for handling. The router's supervisor will decide what to do about any errors.

Note the router's supervisor will treat the error as an error with the router itself. Therefore a
directive to stop or restart will cause the router *itself* to stop or restart. The router, in
turn, will cause its children to stop and restart.

It should be mentioned that the router's restart behavior has been overridden so that a restart,
while still re-creating the children, will still preserve the same number of actors in the pool.

This means that if you have not specified :meth:`supervisorStrategy` of the router or its parent a
failure in a routee will escalate to the parent of the router, which will by default restart the router,
which will restart all routees (it uses Escalate and does not stop routees during restart). The reason 
is to make the default behave such that adding :meth:`.withRouter` to a child’s definition does not 
change the supervision strategy applied to the child. This might be an inefficiency that you can avoid 
by specifying the strategy when defining the router.

Setting the strategy is easily done:

.. includecode:: ../../../akka-actor-tests/src/test/scala/akka/routing/RoutingSpec.scala#supervision
   :include: supervision
   :exclude: custom-strategy

.. _note-router-terminated-children-scala:

.. note::

  If the child of a router terminates, the router will not automatically spawn
  a new child. In the event that all children of a router have terminated the
  router will terminate itself unless it is a dynamic router, e.g. using
  a resizer.

Router usage
^^^^^^^^^^^^

In this section we will describe how to use the different router types.
First we need to create some actors that will be used in the examples:

.. includecode:: code/docs/routing/RouterTypeExample.scala#printlnActor

and

.. includecode:: code/docs/routing/RouterTypeExample.scala#fibonacciActor

.. _round-robin-router-scala:

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

.. note::

  Broadcast routers always broadcast *every* message to their routees. If you do not want to
  broadcast every message, then you can use a non-broadcasting router and use
  :ref:`broadcast-messages-scala` as needed.


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

* You can define ``hashMapping`` of the router to map incoming
  messages to their consistent hash key. This makes the decision
  transparent for the sender.

* The messages may implement ``akka.routing.ConsistentHashingRouter.ConsistentHashable``.
  The key is part of the message and it's convenient to define it together
  with the message definition.
 
* The messages can be be wrapped in a ``akka.routing.ConsistentHashingRouter.ConsistentHashableEnvelope``
  to define what data to use for the consistent hash key. The sender knows
  the key to use.
 
These ways to define the consistent hash key can be use together and at
the same time for one router. The ``hashMapping`` is tried first.

Code example:

.. includecode:: code/docs/routing/ConsistentHashingRouterDocSpec.scala#cache-actor

.. includecode:: code/docs/routing/ConsistentHashingRouterDocSpec.scala#consistent-hashing-router

In the above example you see that the ``Get`` message implements ``ConsistentHashable`` itself,
while the ``Entry`` message is wrapped in a ``ConsistentHashableEnvelope``. The ``Evict``
message is handled by the ``hashMapping`` partial function.

This is an example of how to define a consistent-hashing router in configuration:

.. includecode:: code/docs/routing/RouterViaConfigDocSpec.scala#config-consistent-hashing

.. _router-special-messages-scala:

Handling for Special Messages
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Most messages sent to routers will be forwarded according to the routers' usual routing rules.
However there are a few types of messages that have special behavior.

.. _broadcast-messages-scala:

Broadcast Messages
******************

A ``Broadcast`` message can be used to send a message to *all* of a router's routees. When a router
receives a ``Broadcast`` message, it will broadcast that message's *payload* to all routees, no
matter how that router would normally route its messages.

The example below shows how you would use a ``Broadcast`` message to send a very important message
to every routee of a router.

.. includecode:: code/docs/routing/RouterViaProgramDocSpec.scala#broadcastDavyJonesWarning

In this example the router receives the ``Broadcast`` message, extracts its payload
(``"Watch out for Davy Jones' locker"``), and then sends the payload on to all of the router's
routees. It is up to each each routee actor to handle the received payload message.

PoisonPill Messages
*******************

A ``PoisonPill`` message has special handling for all actors, including for routers. When any actor
receives a ``PoisonPill`` message, that actor will be stopped. See the :ref:`poison-pill-scala`
documentation for details.

.. includecode:: code/docs/routing/RouterViaProgramDocSpec.scala#poisonPill

For a router, which normally passes on messages to routees, it is important to realised that
``PoisonPill`` messages are processed by the router only. ``PoisonPill`` messages sent to a router
will *not* be sent on to routees.

However, a ``PoisonPill`` message sent to a router may still affect its routees, because it will
stop the router and when the router stops it also stops its children. Stopping children is normal
actor behavior. The router will stop routees that it has created as children. Each child will
process its current message and then tstop. This may lead to some messages being unprocessed.
See the documentation on :ref:`stopping-actors-scala` for more information.

If you wish to stop a router and its routees, but you would like the routees to first process all
the messages currently in their mailboxes, then you should not send a ``PoisonPill`` message to the
router. Instead you should wrap a ``PoisonPill`` message inside a broadcast message so that each
routee will the ``PoisonPill`` message directly. Note that this will stop all routees, even if the
routees aren't children of the router, i.e. even routees programmatically provided to the router.

.. includecode:: code/docs/routing/RouterViaProgramDocSpec.scala#broadcastPoisonPill

With the code shown above, each routee will receive a ``PoisonPill`` message. Each routee will
continue to process its messages as normal, eventually processing the ``PoisonPill``. This will
cause the routee to stop. After all routees have stopped the router will itself be :ref:`stopped
automatically <note-router-terminated-children-scala>` unless it is a dynamic router, e.g. using
a resizer.

.. note::

  Brendan W McAdams' excellent blog post `Distributing Akka Workloads - And Shutting Down Afterwards
  <http://blog.evilmonkeylabs.com/2013/01/17/Distributing_Akka_Workloads_And_Shutting_Down_After/>`_
  discusses in more detail how ``PoisonPill`` messages can be used to shut down routers and routees.

Kill Messages
*************

``Kill`` messages are another type of message that has special handling. See
:ref:`killing-actors-scala` for general information about how actors handle ``Kill`` messages.

When a ``Kill`` message is sent to a router the router processes the message internally, and does
*not* send it on to its routees. The router will throw an :class:`ActorKilledException` and fail. It
will then be either resumed, restarted or terminated, depending how it is supervised.

Routees that are children of the router will also be suspended, and will be affected by the
supervision directive that is applied to the router. Routees that are not the routers children, i.e.
those that were created externally to the router, will not be affected.

.. includecode:: code/docs/routing/RouterViaProgramDocSpec.scala#kill

As with the ``PoisonPill`` message, there is a distinction between killing a router, which
indirectly kills its children (who happen to be routees), and killing routees directly (some of whom
may not be children.) To kill routees directly the router should be sent a ``Kill`` message wrapped
in a ``Broadcast`` message.

.. includecode:: code/docs/routing/RouterViaProgramDocSpec.scala#broadcastKill

.. _resizable-routers-scala:

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

.. _router-design-scala:

How Routing is Designed within Akka
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

On the surface routers look like normal actors, but they are actually implemented differently.
Routers are designed to be extremely efficient at receiving messages and passing them quickly on to
routees.

A normal actor can be used for routing messages, but an actor's single-threaded processing can
become a bottleneck. Routers can achieve much higher throughput with an optimization to the usual
message-processing pipeline that allows concurrent routing. This is achieved by embedding routers'
routing logic directly in their :class:`ActorRef` rather than in the router actor. Messages sent to
a router's :class:`ActorRef` can be immediately routed to the routee, bypassing the single-threaded
router actor entirely.

The cost to this is, of course, that the internals of routing code are more complicated than if
routers were implemented with normal actors. Fortunately all of this complexity is invisible to
consumers of the routing API. However, it is something to be aware of when implementing your own
routers.

.. _custom-router-scala:

Custom Router
^^^^^^^^^^^^^

You can create your own router should you not find any of the ones provided by Akka sufficient for your needs.
In order to roll your own router you have to fulfill certain criteria which are explained in this section.

Before creating your own router you should consider whether a normal actor with router-like
behavior might do the job just as well as a full-blown router. As explained
:ref:`above <router-design-scala>`, the primary benefit of routers over normal actors is their
higher performance. But they are somewhat more complicated to write than normal actors. Therefore if
lower maximum throughput is acceptable in your application you may wish to stick with traditional
actors. This section, however, assumes that you wish to get maximum performance and so demonstrates
how you can create your own router.

The router created in this example is a simple vote counter. It will route the votes to specific vote counter actors.
In this case we only have two parties the Republicans and the Democrats. We would like a router that forwards all
democrat related messages to the Democrat actor and all republican related messages to the Republican actor.

We begin with defining the class:

.. includecode:: ../../../akka-actor-tests/src/test/scala/akka/routing/RoutingSpec.scala#crRouter
   :exclude: crRoute

The next step is to implement the ``createRoute`` method in the class just defined:

.. includecode:: ../../../akka-actor-tests/src/test/scala/akka/routing/RoutingSpec.scala#crRoute

As you can see above we start off by creating the routees and put them in a collection.

Make sure that you don't miss to implement the line below as it is *really* important.
It registers the routees internally and failing to call this method will
cause a ``ActorInitializationException`` to be thrown when the router is used.
Therefore always make sure to do the following in your custom router:

.. includecode:: ../../../akka-actor-tests/src/test/scala/akka/routing/RoutingSpec.scala#crRegisterRoutees

The routing logic is where your magic sauce is applied. In our example it inspects the message types
and forwards to the correct routee based on this:

.. includecode:: ../../../akka-actor-tests/src/test/scala/akka/routing/RoutingSpec.scala#crRoutingLogic

As you can see above what's returned in the partial function is a ``List`` of ``Destination(sender, routee)``.
The sender is what "parent" the routee should see - changing this could be useful if you for example want
another actor than the original sender to intermediate the result of the routee (if there is a result).
For more information about how to alter the original sender we refer to the source code of
`ScatterGatherFirstCompletedRouter <https://github.com/akka/akka/blob/master/akka-actor/src/main/scala/akka/routing/Routing.scala#L375>`_

All in all the custom router looks like this:

.. includecode:: ../../../akka-actor-tests/src/test/scala/akka/routing/RoutingSpec.scala#CustomRouter

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
``akka.routing.RouterConfig`` and have constructor with one ``com.typesafe.config.Config`` parameter.
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
