
.. _routing-scala:

Routing (Scala)
===============

.. sidebar:: Contents

   .. contents:: :local:

Akka-core includes some building blocks to build more complex message flow handlers, they are listed and explained below:

Router
------

A Router is an actor that routes incoming messages to outbound actors.
The router routes the messages sent to it to its underlying actors called 'routees'.

Akka comes with four defined routers out of the box, but as you will see in this chapter it
is really easy to create your own. The four routers shipped with Akka are:

* `RoundRobinRouter <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Routing.scala#L173>`_
* `RandomRouter <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Routing.scala#L226>`_
* `BroadcastRouter <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Routing.scala#L284>`_
* `ScatterGatherFirstCompletedRouter <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Routing.scala#L330>`_

To illustrate how to use the routers we will create a couple of simple actors and then use them in the
different router types.

Router usage
^^^^^^^^^^^^

In this section we will describe how to use the different router types.
First we need to create some actors that will be used in the examples:

.. includecode:: code/akka/docs/routing/RouterTypeExample.scala#printlnActor

and

.. includecode:: code/akka/docs/routing/RouterTypeExample.scala#fibonacciActor

Here is the configuration file to instruct the routers how many instances of routees to create::

  akka.actor.deployment {
    /router {
      nr-of-instances = 5
    }
  }

RoundRobinRouter
****************
Routes in a `round-robin <http://en.wikipedia.org/wiki/Round-robin>`_ fashion to its routees.
Code example:

.. includecode:: code/akka/docs/routing/RouterTypeExample.scala#roundRobinRouter

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

RandomRouter
************
As the name implies this router type selects one of its routees randomly and forwards
the message it receives to this routee.
This procedure will happen each time it receives a message.
Code example:

.. includecode:: code/akka/docs/routing/RouterTypeExample.scala#randomRouter

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

BroadcastRouter
***************
A broadcast router forwards the message it receives to *all* its routees.
Code example:

.. includecode:: code/akka/docs/routing/RouterTypeExample.scala#broadcastRouter

When run you should see a similar output to this:

.. code-block:: scala

  Received message 'this is a broadcast message' in actor $f
  Received message 'this is a broadcast message' in actor $d
  Received message 'this is a broadcast message' in actor $e
  Received message 'this is a broadcast message' in actor $c
  Received message 'this is a broadcast message' in actor $b

As you can see here above each of the routees, five in total, received the broadcast message.

ScatterGatherFirstCompletedRouter
*********************************
The ScatterGatherFirstCompletedRouter will send the message on to all its routees as a future.
It then waits for first result it gets back. This result will be sent back to original sender.
Code example:

.. includecode:: code/akka/docs/routing/RouterTypeExample.scala#scatterGatherFirstCompletedRouter

When run you should see this:

.. code-block:: scala

  The result of calculating Fibonacci for 10 is 55

From the output above you can't really see that all the routees performed the calculation, but they did!
The result you see is from the first routee that returned its calculation to the router.

Routers Explained
^^^^^^^^^^^^^^^^^

In the example usage above we showed you how to use routers configured with a configuration file but routers
can also be configured programatically.

This is an example of how to create a router and set the number of routees it should create:

.. includecode:: code/akka/docs/routing/RouterViaProgramExample.scala#programmaticRoutingNrOfInstances

You can also give the router already created routees as in:

.. includecode:: code/akka/docs/routing/RouterViaProgramExample.scala#programmaticRoutingRoutees

When you create a router programatically you define the number of routees *or* you pass already created routees to it.
If you send both parameters to the router *only* the latter will be used, i.e. ``nrOfInstances`` is disregarded.

*It is also worth pointing out that if you define the number of routees in the configuration file then this
value will be used instead of any programmatically sent parameters.*

Once you have the router actor it is just to send messages to it as you would to any actor:

.. code-block:: scala

  router ! MyMsg

The router will apply its behavior to the message it receives and forward it to the routees.

Broadcast Messages
^^^^^^^^^^^^^^^^^^

There is a special type of message that will be sent to all routees regardless of the router.
This message is called ``Broadcast`` and is used in the following manner:

.. code-block:: scala

  router ! Broadcast("Watch out for Davy Jones' locker")

Only the actual message is forwarded to the routees, i.e. "Watch out for Davy Jones' locker" in the example above.
It is up to the routee implementation whether to handle the broadcast message or not.

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

The next step is to implement the 'createRoute' method in the class just defined:

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
`ScatterGatherFirstCompletedRouter <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Routing.scala#L330>`_

All in all the custom router looks like this:

.. includecode:: ../../akka-actor-tests/src/test/scala/akka/routing/RoutingSpec.scala#CustomRouter

If you are interested in how to use the VoteCountRouter you can have a look at the test class
`RoutingSpec <https://github.com/jboner/akka/blob/master/akka-actor-tests/src/test/scala/akka/routing/RoutingSpec.scala>`_

Actor Pool
----------

An actor pool routes incoming messages to other actors. It has different semantics however when it comes to how those
actors are managed and selected for dispatch. Therein lies the difference. The pool manages, from start to shutdown,
the lifecycle of all delegated actors. The number of actors in a pool can be fixed or grow and shrink over time.
Also, messages can be routed to more than one actor in the pool if so desired. This is a useful little feature for
accounting for expected failure - especially with remoting - where you can invoke the same request of multiple
actors and just take the first, best response.

The actor pool is built around three concepts: capacity, filtering and selection.

Selection
^^^^^^^^^

All pools require a ``Selector`` to be mixed-in. This trait controls how and how many actors in the pool will
receive the incoming message. Define *selectionCount* to some positive number greater than one to route to
multiple actors. Currently two are provided:

* `SmallestMailboxSelector <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Pool.scala#L148>`_ - Using the exact same logic as the iterator of the same name, the pooled actor with the fewest number of pending messages will be chosen.
* `RoundRobinSelector <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Pool.scala#L184>`_ - Performs a very simple index-based selection, wrapping around the end of the list, very much like the CyclicIterator does.

Partial Fills
*************

When selecting more than one pooled actor, its possible that in order to fulfill the requested amount,
the selection set must contain duplicates. By setting ``partialFill`` to ``true``, you instruct the selector to
return only unique actors from the pool.

Capacity
^^^^^^^^

As you'd expect, capacity traits determine how the pool is funded with actors. There are two types of strategies that can be employed:

* `FixedCapacityStrategy <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Pool.scala#L346>`_ - When you mix this into your actor pool, you define a pool size and when the pool is started, it will have that number of actors within to which messages will be delegated.
* `BoundedCapacityStrategy <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Pool.scala#L355>`_ - When you mix this into your actor pool, you define upper and lower bounds, and when the pool is started, it will have the minimum number of actors in place to handle messages. You must also mix-in a Capacitor and a Filter when using this strategy (see below).

The *BoundedCapacityStrategy* requires additional logic to function. Specifically it requires a *Capacitor* and a *Filter*.
Capacitors are used to determine the pressure that the pool is under and provide a (usually) raw reading of this information.
Currently we provide for the use of either mailbox backlog or active futures count as a means of evaluating pool pressure.
Each expresses itself as a simple number - a reading of the number of actors either with mailbox sizes over a certain threshold
or blocking a thread waiting on a future to complete or expire.

Filtering
^^^^^^^^^

A *Filter* is a trait that modifies the raw pressure reading returned from a Capacitor such that it drives the
adjustment of the pool capacity to a desired end. More simply, if we just used the pressure reading alone,
we might only ever increase the size of the pool (to respond to overload) or we might only have a single
mechanism for reducing the pool size when/if it became necessary. This behavior is fully under your control
through the use of *Filters*. Let's take a look at some code to see how this works:

.. includecode:: code/akka/docs/routing/BoundedCapacitorExample.scala#boundedCapacitor

.. includecode:: code/akka/docs/routing/CapacityStrategyExample.scala#capacityStrategy

Here we see how the filter function will have the chance to modify the pressure reading to influence the capacity change.
You are free to implement filter() however you like. We provide a
`Filter <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Pool.scala#L279>`_ trait that
evaluates both a rampup and a backoff subfilter to determine how to use the pressure reading to alter the pool capacity.
There are several sub filters available to use, though again you may create whatever makes the most sense for you pool:

* `BasicRampup <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Pool.scala#L409>`_ - When pressure exceeds current capacity, increase the number of actors in the pool by some factor (*rampupRate*) of the current pool size.
* `BasicBackoff <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Pool.scala#L426>`_ - When the pressure ratio falls under some predefined amount (*backoffThreshold*), decrease the number of actors in the pool by some factor of the current pool size.
* `RunningMeanBackoff <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Pool.scala#L454>`_ - This filter tracks the average pressure-to-capacity over the lifetime of the pool (or since the last time the filter was reset) and will begin to reduce capacity once this mean falls below some predefined amount. The number of actors that will be stopped is determined by some factor of the difference between the current capacity and pressure. The idea behind this filter is to reduce the likelihood of "thrashing" (removing then immediately creating...) pool actors by delaying the backoff until some quiescent stage of the pool. Put another way, use this subfilter to allow quick rampup to handle load and more subtle backoff as that decreases over time.

Example Usage
^^^^^^^^^^^^^

.. includecode:: code/akka/docs/routing/ActorPoolExample.scala#testPool
