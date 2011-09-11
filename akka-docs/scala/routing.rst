Routing (Scala)
===============

.. sidebar:: Contents

   .. contents:: :local:

Akka-core includes some building blocks to build more complex message flow handlers, they are listed and explained below:

Router
----------

A Router is an actor that routes incoming messages to outbound actors.

To use it you can either create a Router through the ``routerActor()`` factory method

.. code-block:: scala

  import akka.actor.Actor._
  import akka.actor.Actor
  import akka.routing.Routing._

  //Our message types
  case object Ping
  case object Pong

  //Two actors, one named Pinger and one named Ponger
  //The actor(pf) method creates an anonymous actor and starts it
  val pinger = actorOf(new Actor { def receive = { case x => println("Pinger: " + x) } })
  val ponger = actorOf(new Actor { def receive = { case x => println("Ponger: " + x) } })

  //A router that dispatches Ping messages to the pinger
  //and Pong messages to the ponger
  val d = routerActor {
    case Ping => pinger
    case Pong => ponger
  }

  d ! Ping //Prints "Pinger: Ping"
  d ! Pong //Prints "Ponger: Pong"

Or by mixing in akka.routing.Router:

.. code-block:: scala

  import akka.actor.Actor
  import akka.actor.Actor._
  import akka.routing.Router

  //Our message types
  case object Ping
  case object Pong

  class MyRouter extends Actor with Router {
    //Our pinger and ponger actors
    val pinger = actorOf(new Actor { def receive = { case x => println("Pinger: " + x) } })
    val ponger = actorOf(new Actor { def receive = { case x => println("Ponger: " + x) } })
    //When we get a ping, we dispatch to the pinger
    //When we get a pong, we dispatch to the ponger
    def routes = {
      case Ping => pinger
      case Pong => ponger
    }
  }

  //Create an instance of our router, and start it
  val d = actorOf[MyRouter]

  d ! Ping //Prints "Pinger: Ping"
  d ! Pong //Prints "Ponger: Pong"

LoadBalancer
------------

A LoadBalancer is an actor that forwards messages it receives to a boundless sequence of destination actors.

Example using the ``loadBalancerActor()`` factory method:

.. code-block:: scala

  import akka.actor.Actor._
  import akka.actor.Actor
  import akka.routing.Routing._
  import akka.routing.CyclicIterator

  //Our message types
  case object Ping
  case object Pong

  //Two actors, one named Pinger and one named Ponger
  //The actor(pf) method creates an anonymous actor and starts it

  val pinger = actorOf(new Actor { def receive = { case x => println("Pinger: " + x) } })
  val ponger = actorOf(new Actor { def receive = { case x => println("Ponger: " + x) } })

  //A load balancer that given a sequence of actors dispatches them accordingly
  //a CyclicIterator works in a round-robin-fashion

  val d = loadBalancerActor( new CyclicIterator( List(pinger,ponger) ) )

  d ! Pong //Prints "Pinger: Pong"
  d ! Pong //Prints "Ponger: Pong"
  d ! Ping //Prints "Pinger: Ping"
  d ! Ping //Prints "Ponger: Ping"

Or by mixing in akka.routing.LoadBalancer

.. code-block:: scala

  import akka.actor._
  import akka.actor.Actor._
  import akka.routing.{ LoadBalancer, CyclicIterator }

  //Our message types
  case object Ping
  case object Pong

  //A load balancer that balances between a pinger and a ponger
  class MyLoadBalancer extends Actor with LoadBalancer {
    val pinger = actorOf(new Actor { def receive = { case x => println("Pinger: " + x) } })
    val ponger = actorOf(new Actor { def receive = { case x => println("Ponger: " + x) } })

    val seq = new CyclicIterator[ActorRef](List(pinger,ponger))
  }

  //Create an instance of our loadbalancer, and start it
  val d = actorOf[MyLoadBalancer]

  d ! Pong //Prints "Pinger: Pong"
  d ! Pong //Prints "Ponger: Pong"
  d ! Ping //Prints "Pinger: Ping"
  d ! Ping //Prints "Ponger: Ping"

Also, instead of using the CyclicIterator, you can create your own message distribution algorithms, there’s already `one <@http://github.com/jboner/akka/blob/master/akka-core/src/main/scala/routing/Iterators.scala#L31>`_ that dispatches depending on target mailbox size, effectively dispatching to the one that’s got fewest messages to process right now.

Example `<http://pastie.org/984889>`_

You can also send a 'Routing.Broadcast(msg)' message to the router to have it be broadcasted out to all the actors it represents.

.. code-block:: scala

  router ! Routing.Broadcast(PoisonPill)

Actor Pool
----------

An actor pool is similar to the load balancer is that it routes incoming messages to other actors. It has different semantics however when it comes to how those actors are managed and selected for dispatch. Therein lies the difference. The pool manages, from start to shutdown, the lifecycle of all delegated actors. The number of actors in a pool can be fixed or grow and shrink over time. Also, messages can be routed to more than one actor in the pool if so desired. This is a useful little feature for accounting for expected failure - especially with remoting - where you can invoke the same request of multiple actors and just take the first, best response.

The actor pool is built around three concepts: capacity, filtering and selection.

Selection
^^^^^^^^^

All pools require a *Selector* to be mixed-in. This trait controls how and how many actors in the pool will receive the incoming message. Define *selectionCount* to some positive number greater than one to route to multiple actors. Currently two are provided:

* `SmallestMailboxSelector <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Pool.scala#L133>`_ - Using the exact same logic as the iterator of the same name, the pooled actor with the fewest number of pending messages will be chosen.
* `RoundRobinSelector <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Pool.scala#L158>`_ - Performs a very simple index-based selection, wrapping around the end of the list, very much like the CyclicIterator does.

Partial Fills
*************

When selecting more than one pooled actor, its possible that in order to fulfill the requested amount, the selection set must contain duplicates. By setting *partialFill* to **true**, you instruct the selector to return only unique actors from the pool.

Capacity
^^^^^^^^

As you'd expect, capacity traits determine how the pool is funded with actors. There are two types of strategies that can be employed:

* `FixedCapacityStrategy <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Pool.scala#L268>`_ - When you mix this into your actor pool, you define a pool size and when the pool is started, it will have that number of actors within to which messages will be delegated.
* `BoundedCapacityStrategy <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Pool.scala#L269>`_ - When you mix this into your actor pool, you define upper and lower bounds, and when the pool is started, it will have the minimum number of actors in place to handle messages. You must also mix-in a Capacitor and a Filter when using this strategy (see below).

The *BoundedCapacityStrategy* requires additional logic to function. Specifically it requires a *Capacitor* and a *Filter*. Capacitors are used to determine the pressure that the pool is under and provide a (usually) raw reading of this information. Currently we provide for the use of either mailbox backlog or active futures count as a means of evaluating pool pressure. Each expresses itself as a simple number - a reading of the number of actors either with mailbox sizes over a certain threshold or blocking a thread waiting on a future to complete or expire.

Filtering
^^^^^^^^^

A *Filter* is a trait that modifies the raw pressure reading returned from a Capacitor such that it drives the adjustment of the pool capacity to a desired end. More simply, if we just used the pressure reading alone, we might only ever increase the size of the pool (to respond to overload) or we might only have a single mechanism for reducing the pool size when/if it became necessary. This behavior is fully under your control through the use of *Filters*. Let's take a look at some code to see how this works:

.. code-block:: scala

  trait BoundedCapacitor
  {
  	def lowerBound:Int
  	def upperBound:Int

  	def capacity(delegates:Seq[ActorRef]):Int =
  	{
  		val current = delegates length
  		var delta = _eval(delegates)
  		val proposed = current + delta

  		if (proposed < lowerBound) delta += (lowerBound - proposed)
  		else if (proposed > upperBound) delta -= (proposed - upperBound)

  		delta
  	}

  	protected def _eval(delegates:Seq[ActorRef]):Int
  }

  trait CapacityStrategy
  {
  	import ActorPool._

  	def pressure(delegates:Seq[ActorRef]):Int
  	def filter(pressure:Int, capacity:Int):Int

  	protected def _eval(delegates:Seq[ActorRef]):Int = filter(pressure(delegates), delegates.size)
  }

Here we see how the filter function will have the chance to modify the pressure reading to influence the capacity change. You are free to implement filter() however you like. We provide a `Filter <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Pool.scala#L279>`_ trait that evaluates both a rampup and a backoff subfilter to determine how to use the pressure reading to alter the pool capacity. There are several subfilters available to use, though again you may create whatever makes the most sense for you pool:

* `BasicRampup <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Pool.scala#L308>`_ - When pressure exceeds current capacity, increase the number of actors in the pool by some factor (*rampupRate*) of the current pool size.
* `BasicBackoff <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Pool.scala#L322>`_ - When the pressure ratio falls under some predefined amount (*backoffThreshold*), decrease the number of actors in the pool by some factor of the current pool size.
* `RunningMeanBackoff <https://github.com/jboner/akka/blob/master/akka-actor/src/main/scala/akka/routing/Pool.scala#L341>`_ - This filter tracks the average pressure-to-capacity over the lifetime of the pool (or since the last time the filter was reset) and will begin to reduce capacity once this mean falls below some predefined amount. The number of actors that will be stopped is determined by some factor of the difference between the current capacity and pressure. The idea behind this filter is to reduce the likelihood of "thrashing" (removing then immediately creating...) pool actors by delaying the backoff until some quiescent stage of the pool. Put another way, use this subfilter to allow quick rampup to handle load and more subtle backoff as that decreases over time.

Examples
^^^^^^^^

.. code-block:: scala

  class TestPool extends Actor with DefaultActorPool
                                 with BoundedCapacityStrategy
                                 with ActiveFuturesPressureCapacitor
                                 with SmallestMailboxSelector
                                 with BasicNoBackoffFilter
  {
     def receive = _route
     def lowerBound = 2
     def upperBound = 4
     def rampupRate = 0.1
     def partialFill = true
     def selectionCount = 1
     def instance = actorOf(new Actor {def receive = {case n:Int =>
                                                     Thread.sleep(n)
                                                     counter.incrementAndGet
                                                     latch.countDown()}})
  }

.. code-block:: scala

  class TestPool extends Actor with DefaultActorPool
                                 with BoundedCapacityStrategy
                                 with MailboxPressureCapacitor
                                 with SmallestMailboxSelector
                                 with Filter
                                   with RunningMeanBackoff
                                   with BasicRampup
  {
    def receive = _route
    def lowerBound = 1
    def upperBound = 5
    def pressureThreshold = 1
    def partialFill = true
    def selectionCount = 1
    def rampupRate = 0.1
    def backoffRate = 0.50
    def backoffThreshold = 0.50
    def instance = actorOf(new Actor {def receive = {case n:Int =>
                                                    Thread.sleep(n)
                                                    latch.countDown()}})
  }

Taken from the unit test `spec <https://github.com/jboner/akka/blob/master/akka-actor/src/test/scala/akka/routing/RoutingSpec.scala>`_.
