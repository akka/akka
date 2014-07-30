
.. _routing-scala:

Routing
=======

Messages can be sent via a router to efficiently route them to destination actors, known as
its *routees*. A ``Router`` can be used inside or outside of an actor, and you can manage the
routees yourselves or use a self contained router actor with configuration capabilities.

Different routing strategies can be used, according to your application's needs. Akka comes with
several useful routing strategies right out of the box. But, as you will see in this chapter, it is
also possible to :ref:`create your own <custom-router-scala>`.

.. _simple-router-scala:

A Simple Router
^^^^^^^^^^^^^^^

The following example illustrates how to use a ``Router`` and manage the routees from within an actor.

.. includecode:: code/docs/routing/RouterDocSpec.scala#router-in-actor

We create a ``Router`` and specify that it should use ``RoundRobinRoutingLogic`` when routing the
messages to the routees.

The routing logic shipped with Akka are:

* ``akka.routing.RoundRobinRoutingLogic``
* ``akka.routing.RandomRoutingLogic``
* ``akka.routing.SmallestMailboxRoutingLogic``
* ``akka.routing.BroadcastRoutingLogic``
* ``akka.routing.ScatterGatherFirstCompletedRoutingLogic``
* ``akka.routing.TailChoppingRoutingLogic``
* ``akka.routing.ConsistentHashingRoutingLogic``

We create the routees as ordinary child actors wrapped in ``ActorRefRoutee``. We watch
the routees to be able to replace them if they are terminated.

Sending messages via the router is done with the ``route`` method, as is done for the ``Work`` messages
in the example above.

The ``Router`` is immutable and the ``RoutingLogic`` is thread safe; meaning that they can also be used
outside of actors.  

.. note::

    In general, any message sent to a router will be sent onwards to its routees, but there is one exception.
    The special :ref:`broadcast-messages-scala` will send to *all* of a router's routees 

A Router Actor
^^^^^^^^^^^^^^

A router can also be created as a self contained actor that manages the routees itself and
loads routing logic and other settings from configuration.

This type of router actor comes in two distinct flavors:

* Pool - The router creates routees as child actors and removes them from the router if they
  terminate.
  
* Group - The routee actors are created externally to the router and the router sends
  messages to the specified path using actor selection, without watching for termination.  

The settings for a router actor can be defined in configuration or programmatically. 
Although router actors can be defined in the configuration file, they must still be created
programmatically, i.e. you cannot make a router through external configuration alone.
If you define the router actor in the configuration file then these settings will be used
instead of any programmatically provided parameters.

You send messages to the routees via the router actor in the same way as for ordinary actors,
i.e. via its ``ActorRef``. The router actor forwards messages onto its routees without changing 
the original sender. When a routee replies to a routed message, the reply will be sent to the 
original sender, not to the router actor.

.. note::

    In general, any message sent to a router will be sent onwards to its routees, but there are a
    few exceptions. These are documented in the :ref:`router-special-messages-scala` section below.

Pool
----

The following code and configuration snippets show how to create a :ref:`round-robin
<round-robin-router-scala>` router that forwards messages to five ``Worker`` routees. The
routees will be created as the router's children.

.. includecode:: code/docs/routing/RouterDocSpec.scala#config-round-robin-pool

.. includecode:: code/docs/routing/RouterDocSpec.scala#round-robin-pool-1

Here is the same example, but with the router configuration provided programmatically instead of
from configuration.

.. includecode:: code/docs/routing/RouterDocSpec.scala#round-robin-pool-2

Remote Deployed Routees
***********************

In addition to being able to create local actors as routees, you can instruct the router to
deploy its created children on a set of remote hosts. Routees will be deployed in round-robin
fashion. In order to deploy routees remotely, wrap the router configuration in a
``RemoteRouterConfig``, attaching the remote addresses of the nodes to deploy to. Remote
deployment requires the ``akka-remote`` module to be included in the classpath.

.. includecode:: code/docs/routing/RouterDocSpec.scala#remoteRoutees

Senders
*******

By default, when a routee sends a message, it will :ref:`implicitly set itself as the sender
<actors-tell-sender-scala>`.

.. includecode:: code/docs/actor/ActorDocSpec.scala#reply-without-sender

However, it is often useful for routees to set the *router* as a sender. For example, you might want
to set the router as the sender if you want to hide the details of the routees behind the router.
The following code snippet shows how to set the parent router as sender.

.. includecode:: code/docs/actor/ActorDocSpec.scala#reply-with-sender


Supervision
***********

Routees that are created by a pool router will be created as the router's children. The router is 
therefore also the children's supervisor.

The supervision strategy of the router actor can be configured with the
``supervisorStrategy`` property of the Pool. If no configuration is provided, routers default
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

.. _note-router-terminated-children-scala:

.. note::

  If the child of a pool router terminates, the pool router will not automatically spawn
  a new child. In the event that all children of a pool router have terminated the
  router will terminate itself unless it is a dynamic router, e.g. using
  a resizer.

Group
-----

Sometimes, rather than having the router actor create its routees, it is desirable to create routees
separately and provide them to the router for its use. You can do this by passing an
paths of the routees to the router's configuration. Messages will be sent with ``ActorSelection`` 
to these paths.  

The example below shows how to create a router by providing it with the path strings of three
routee actors. 

.. includecode:: code/docs/routing/RouterDocSpec.scala#config-round-robin-group

.. includecode:: code/docs/routing/RouterDocSpec.scala#round-robin-group-1

Here is the same example, but with the router configuration provided programmatically instead of
from configuration.

.. includecode:: code/docs/routing/RouterDocSpec.scala#round-robin-group-2

The routee actors are created externally from the router:

.. includecode:: code/docs/routing/RouterDocSpec.scala#create-workers

.. includecode:: code/docs/routing/RouterDocSpec.scala#create-worker-actors

The paths may contain protocol and address information for actors running on remote hosts.
Remoting requires the ``akka-remote`` module to be included in the classpath.

.. includecode:: code/docs/routing/RouterDocSpec.scala#config-remote-round-robin-group

Router usage
^^^^^^^^^^^^

In this section we will describe how to create the different types of router actors.

The router actors in this section are created from within a top level actor named ``parent``. 
Note that deployment paths in the configuration starts with ``/parent/`` followed by the name
of the router actor. 

.. includecode:: code/docs/routing/RouterDocSpec.scala#create-parent

.. _round-robin-router-scala:

RoundRobinPool and RoundRobinGroup
----------------------------------

Routes in a `round-robin <http://en.wikipedia.org/wiki/Round-robin>`_ fashion to its routees.

RoundRobinPool defined in configuration:

.. includecode:: code/docs/routing/RouterDocSpec.scala#config-round-robin-pool

.. includecode:: code/docs/routing/RouterDocSpec.scala#round-robin-pool-1

RoundRobinPool defined in code:

.. includecode:: code/docs/routing/RouterDocSpec.scala#round-robin-pool-2

RoundRobinGroup defined in configuration:

.. includecode:: code/docs/routing/RouterDocSpec.scala#config-round-robin-group

.. includecode:: code/docs/routing/RouterDocSpec.scala#round-robin-group-1

RoundRobinGroup defined in code:

.. includecode:: code/docs/routing/RouterDocSpec.scala
   :include: paths,round-robin-group-2

RandomPool and RandomGroup
--------------------------

This router type selects one of its routees randomly for each message.

RandomPool defined in configuration:

.. includecode:: code/docs/routing/RouterDocSpec.scala#config-random-pool

.. includecode:: code/docs/routing/RouterDocSpec.scala#random-pool-1

RandomPool defined in code:

.. includecode:: code/docs/routing/RouterDocSpec.scala#random-pool-2

RandomGroup defined in configuration:

.. includecode:: code/docs/routing/RouterDocSpec.scala#config-random-group

.. includecode:: code/docs/routing/RouterDocSpec.scala#random-group-1

RandomGroup defined in code:

.. includecode:: code/docs/routing/RouterDocSpec.scala
   :include: paths,random-group-2

.. _balancing-pool-scala:

BalancingPool
-------------

A Router that will try to redistribute work from busy routees to idle routees.
All routees share the same mailbox.

BalancingPool defined in configuration:

.. includecode:: code/docs/routing/RouterDocSpec.scala#config-balancing-pool

.. includecode:: code/docs/routing/RouterDocSpec.scala#balancing-pool-1

BalancingPool defined in code:

.. includecode:: code/docs/routing/RouterDocSpec.scala#balancing-pool-2

Addition configuration for the balancing dispatcher, which is used by the pool,
can be configured in the ``pool-dispatcher`` section of the router deployment
configuration.

.. includecode:: code/docs/routing/RouterDocSpec.scala#config-balancing-pool2

There is no Group variant of the BalancingPool.

SmallestMailboxPool
-------------------

A Router that tries to send to the non-suspended child routee with fewest messages in mailbox.
The selection is done in this order:

 * pick any idle routee (not processing message) with empty mailbox
 * pick any routee with empty mailbox
 * pick routee with fewest pending messages in mailbox
 * pick any remote routee, remote actors are consider lowest priority,
   since their mailbox size is unknown

SmallestMailboxPool defined in configuration:

.. includecode:: code/docs/routing/RouterDocSpec.scala#config-smallest-mailbox-pool

.. includecode:: code/docs/routing/RouterDocSpec.scala#smallest-mailbox-pool-1

SmallestMailboxPool defined in code:

.. includecode:: code/docs/routing/RouterDocSpec.scala#smallest-mailbox-pool-2

There is no Group variant of the SmallestMailboxPool because the size of the mailbox
and the internal dispatching state of the actor is not practically available from the paths
of the routees.

BroadcastPool and BroadcastGroup 
--------------------------------

A broadcast router forwards the message it receives to *all* its routees.

BroadcastPool defined in configuration:

.. includecode:: code/docs/routing/RouterDocSpec.scala#config-broadcast-pool

.. includecode:: code/docs/routing/RouterDocSpec.scala#broadcast-pool-1

BroadcastPool defined in code:

.. includecode:: code/docs/routing/RouterDocSpec.scala#broadcast-pool-2

BroadcastGroup defined in configuration:

.. includecode:: code/docs/routing/RouterDocSpec.scala#config-broadcast-group

.. includecode:: code/docs/routing/RouterDocSpec.scala#broadcast-group-1

BroadcastGroup defined in code:

.. includecode:: code/docs/routing/RouterDocSpec.scala
   :include: paths,broadcast-group-2

.. note::

  Broadcast routers always broadcast *every* message to their routees. If you do not want to
  broadcast every message, then you can use a non-broadcasting router and use
  :ref:`broadcast-messages-scala` as needed.


ScatterGatherFirstCompletedPool and ScatterGatherFirstCompletedGroup
--------------------------------------------------------------------

The ScatterGatherFirstCompletedRouter will send the message on to all its routees.
It then waits for first reply it gets back. This result will be sent back to original sender.
Other replies are discarded.

It is expecting at least one reply within a configured duration, otherwise it will reply with
``akka.pattern.AskTimeoutException`` in a ``akka.actor.Status.Failure``.

ScatterGatherFirstCompletedPool defined in configuration:

.. includecode:: code/docs/routing/RouterDocSpec.scala#config-scatter-gather-pool

.. includecode:: code/docs/routing/RouterDocSpec.scala#scatter-gather-pool-1

ScatterGatherFirstCompletedPool defined in code:

.. includecode:: code/docs/routing/RouterDocSpec.scala#scatter-gather-pool-2

ScatterGatherFirstCompletedGroup defined in configuration:

.. includecode:: code/docs/routing/RouterDocSpec.scala#config-scatter-gather-group

.. includecode:: code/docs/routing/RouterDocSpec.scala#scatter-gather-group-1

ScatterGatherFirstCompletedGroup defined in code:

.. includecode:: code/docs/routing/RouterDocSpec.scala
   :include: paths,scatter-gather-group-2

TailChoppingPool and TailChoppingGroup
--------------------------------------

The TailChoppingRouter will first send the message to one, randomly picked, routee
and then after a small delay to to a second routee (picked randomly from the remaining routees) and so on.
It waits for first reply it gets back and forwards it back to original sender. Other replies are discarded.

The goal of this router is to decrease latency by performing redundant queries to multiple routees, assuming that
one of the other actors may still be faster to respond than the initial one.

This optimisation was described nicely in a blog post by Peter Bailis:
`Doing redundant work to speed up distributed queries <http://www.bailis.org/blog/doing-redundant-work-to-speed-up-distributed-queries/>`_.

TailChoppingPool defined in configuration:

.. includecode:: code/docs/routing/RouterDocSpec.scala#config-tail-chopping-pool

.. includecode:: code/docs/routing/RouterDocSpec.scala#tail-chopping-pool-1

TailChoppingPool defined in code:

.. includecode:: code/docs/routing/RouterDocSpec.scala#tail-chopping-pool-2

TailChoppingGroup defined in configuration:

.. includecode:: code/docs/routing/RouterDocSpec.scala#config-tail-chopping-group

.. includecode:: code/docs/routing/RouterDocSpec.scala#tail-chopping-group-1

TailChoppingGroup defined in code:

.. includecode:: code/docs/routing/RouterDocSpec.scala
   :include: paths,tail-chopping-group-2

ConsistentHashingPool and ConsistentHashingGroup
------------------------------------------------

The ConsistentHashingPool uses `consistent hashing <http://en.wikipedia.org/wiki/Consistent_hashing>`_
to select a routee based on the sent message. This 
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

ConsistentHashingPool defined in configuration:

.. includecode:: code/docs/routing/RouterDocSpec.scala#config-consistent-hashing-pool

.. includecode:: code/docs/routing/RouterDocSpec.scala#consistent-hashing-pool-1

ConsistentHashingPool defined in code:

.. includecode:: code/docs/routing/RouterDocSpec.scala#consistent-hashing-pool-2

ConsistentHashingGroup defined in configuration:

.. includecode:: code/docs/routing/RouterDocSpec.scala#config-consistent-hashing-group

.. includecode:: code/docs/routing/RouterDocSpec.scala#consistent-hashing-group-1

ConsistentHashingGroup defined in code:

.. includecode:: code/docs/routing/RouterDocSpec.scala
   :include: paths,consistent-hashing-group-2


``virtual-nodes-factor`` is the number of virtual nodes per routee that is used in the 
consistent hash node ring to make the distribution more uniform.

.. _router-special-messages-scala:

Specially Handled Messages
^^^^^^^^^^^^^^^^^^^^^^^^^^

Most messages sent to router actors will be forwarded according to the routers' routing logic.
However there are a few types of messages that have special behavior.

Note that these special messages, except for the ``Broadcast`` message, are only handled by 
self contained router actors and not by the ``akka.routing.Router`` component described 
in :ref:`simple-router-scala`.

.. _broadcast-messages-scala:

Broadcast Messages
------------------

A ``Broadcast`` message can be used to send a message to *all* of a router's routees. When a router
receives a ``Broadcast`` message, it will broadcast that message's *payload* to all routees, no
matter how that router would normally route its messages.

The example below shows how you would use a ``Broadcast`` message to send a very important message
to every routee of a router.

.. includecode:: code/docs/routing/RouterDocSpec.scala#broadcastDavyJonesWarning

In this example the router receives the ``Broadcast`` message, extracts its payload
(``"Watch out for Davy Jones' locker"``), and then sends the payload on to all of the router's
routees. It is up to each each routee actor to handle the received payload message.

PoisonPill Messages
-------------------

A ``PoisonPill`` message has special handling for all actors, including for routers. When any actor
receives a ``PoisonPill`` message, that actor will be stopped. See the :ref:`poison-pill-scala`
documentation for details.

.. includecode:: code/docs/routing/RouterDocSpec.scala#poisonPill

For a router, which normally passes on messages to routees, it is important to realise that
``PoisonPill`` messages are processed by the router only. ``PoisonPill`` messages sent to a router
will *not* be sent on to routees.

However, a ``PoisonPill`` message sent to a router may still affect its routees, because it will
stop the router and when the router stops it also stops its children. Stopping children is normal
actor behavior. The router will stop routees that it has created as children. Each child will
process its current message and then stop. This may lead to some messages being unprocessed.
See the documentation on :ref:`stopping-actors-scala` for more information.

If you wish to stop a router and its routees, but you would like the routees to first process all
the messages currently in their mailboxes, then you should not send a ``PoisonPill`` message to the
router. Instead you should wrap a ``PoisonPill`` message inside a ``Broadcast`` message so that each
routee will receive the ``PoisonPill`` message. Note that this will stop all routees, even if the
routees aren't children of the router, i.e. even routees programmatically provided to the router.

.. includecode:: code/docs/routing/RouterDocSpec.scala#broadcastPoisonPill

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
-------------

``Kill`` messages are another type of message that has special handling. See
:ref:`killing-actors-scala` for general information about how actors handle ``Kill`` messages.

When a ``Kill`` message is sent to a router the router processes the message internally, and does
*not* send it on to its routees. The router will throw an ``ActorKilledException`` and fail. It
will then be either resumed, restarted or terminated, depending how it is supervised.

Routees that are children of the router will also be suspended, and will be affected by the
supervision directive that is applied to the router. Routees that are not the routers children, i.e.
those that were created externally to the router, will not be affected.

.. includecode:: code/docs/routing/RouterDocSpec.scala#kill

As with the ``PoisonPill`` message, there is a distinction between killing a router, which
indirectly kills its children (who happen to be routees), and killing routees directly (some of whom
may not be children.) To kill routees directly the router should be sent a ``Kill`` message wrapped
in a ``Broadcast`` message.

.. includecode:: code/docs/routing/RouterDocSpec.scala#broadcastKill

Managagement Messages
---------------------

* Sending ``akka.routing.GetRoutees`` to a router actor will make it send back its currently used routees
  in a ``akka.routing.Routees`` message.
* Sending ``akka.routing.AddRoutee`` to a router actor will add that routee to its collection of routees.
* Sending ``akka.routing.RemoveRoutee`` to a router actor will remove that routee to its collection of routees.
* Sending ``akka.routing.AdjustPoolSize`` to a pool router actor will add or remove that number of routees to
  its collection of routees.

These management messages may be handled after other messages, so if you send ``AddRoutee`` immediately followed
an ordinary message you are not guaranteed that the routees have been changed when the ordinary message
is routed. If you need to know when the change has been applied you can send ``AddRoutee`` followed by ``GetRoutees``
and when you receive the ``Routees`` reply you know that the preceding change has been applied.

.. _resizable-routers-scala:

Dynamically Resizable Pool
^^^^^^^^^^^^^^^^^^^^^^^^^^

Most pools can be used with a fixed number of routees or with a resize strategy to adjust the number
of routees dynamically.

Pool with resizer defined in configuration:

.. includecode:: code/docs/routing/RouterDocSpec.scala#config-resize-pool

.. includecode:: code/docs/routing/RouterDocSpec.scala#resize-pool-1

Several more configuration options are available and described in ``akka.actor.deployment.default.resizer``
section of the reference :ref:`configuration`.

Pool with resizer defined in code:

.. includecode:: code/docs/routing/RouterDocSpec.scala#resize-pool-2

*It is also worth pointing out that if you define the ``router`` in the configuration file then this value
will be used instead of any programmatically sent parameters.*

.. note::

  Resizing is triggered by sending messages to the actor pool, but it is not
  completed synchronously; instead a message is sent to the “head”
  ``RouterActor`` to perform the size change. Thus you cannot rely on resizing
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
routing logic directly in their ``ActorRef`` rather than in the router actor. Messages sent to
a router's ``ActorRef`` can be immediately routed to the routee, bypassing the single-threaded
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

The router created in this example is replicating each message to a few destinations.

Start with the routing logic:

.. includecode:: code/docs/routing/CustomRouterDocSpec.scala#routing-logic

``select`` will be called for each message and in this example pick a few destinations by round-robin,
by reusing the existing ``RoundRobinRoutingLogic`` and wrap the result in a ``SeveralRoutees``
instance.  ``SeveralRoutees`` will send the message to all of the supplied routues.

The implementation of the routing logic must be thread safe, since it might be used outside of actors.

A unit test of the routing logic: 

.. includecode:: code/docs/routing/CustomRouterDocSpec.scala#unit-test-logic

You could stop here and use the ``RedundancyRoutingLogic`` with a ``akka.routing.Router``
as described in :ref:`simple-router-scala`.

Let us continue and make this into a self contained, configurable, router actor.

Create a class that extends ``Pool``, ``Group`` or ``CustomRouterConfig``. That class is a factory
for the routing logic and holds the configuration for the router. Here we make it a ``Group``.

.. includecode:: code/docs/routing/CustomRouterDocSpec.scala#group

This can be used exactly as the router actors provided by Akka.

.. includecode:: code/docs/routing/CustomRouterDocSpec.scala#usage-1

Note that we added a constructor in ``RedundancyGroup`` that takes a ``Config`` parameter.
That makes it possible to define it in configuration.

.. includecode:: code/docs/routing/CustomRouterDocSpec.scala#config

Note the fully qualified class name in the ``router`` property. The router class must extend
``akka.routing.RouterConfig`` (``Pool``, ``Group`` or ``CustomRouterConfig``) and have 
constructor with one ``com.typesafe.config.Config`` parameter.
The deployment section of the configuration is passed to the constructor.

.. includecode:: code/docs/routing/CustomRouterDocSpec.scala#usage-2
 
Configuring Dispatchers
^^^^^^^^^^^^^^^^^^^^^^^

The dispatcher for created children of the pool will be taken from
``Props`` as described in :ref:`dispatchers-scala`. 

To make it easy to define the dispatcher of the routees of the pool you can
define the dispatcher inline in the deployment section of the config.

.. includecode:: code/docs/routing/RouterDocSpec.scala#config-pool-dispatcher

That is the only thing you need to do enable a dedicated dispatcher for a
pool.

.. note::

   If you use a group of actors and route to their paths, then they will still use the same dispatcher
   that was configured for them in their ``Props``, it is not possible to change an actors dispatcher
   after it has been created.

The “head” router cannot always run on the same dispatcher, because it
does not process the same type of messages, hence this special actor does
not use the dispatcher configured in ``Props``, but takes the
``routerDispatcher`` from the :class:`RouterConfig` instead, which defaults to
the actor system’s default dispatcher. All standard routers allow setting this
property in their constructor or factory method, custom routers have to
implement the method in a suitable way.

.. includecode:: code/docs/routing/RouterDocSpec.scala#dispatchers

.. note::

   It is not allowed to configure the ``routerDispatcher`` to be a
   :class:`akka.dispatch.BalancingDispatcherConfigurator` since the messages meant
   for the special router actor cannot be processed by any other actor.
