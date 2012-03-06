###############
 Release Notes
###############

Release 2.0
===========

We've just released Akka 2.0 – a revolutionary step in programming for concurrency, fault-tolerance and scalability.

Building on the experiences from the Akka 1 series, we take Akka to the next level— resilience by default, scale up and out by configuration, extensibility by design and with a smaller footprint.

A lot of effort has gone into this release, and it's not just a version bump, it's truly worthy of the name "Akka 2.0".
We'd like to take the opportunity to thank all of the excellent people that have made this release possible,
to Jonas, to all committers, to all users and to the excellent Scala and Java ecosystems!

Highlights of Akka 2.0
----------------------

Stats
^^^^^

700 tickets closed!

**Code changes compared to Akka 1.3.1:**

* 1020 files changed
* 98683 insertions(+)
* 57415 deletions(-)

Also including 331 pages of reference documentation, and tons of ScalaDoc.

Use the Akka Migration package to aid in migrating 1.3 code to 2.0.

Actors
^^^^^^

* Distributable by design and asynchronous at the core
* ``ActorSystems`` lets you run multiple applications in isolation
* ``Props`` are immutable configuration for Actor instances
* ``ActorPaths`` makes it dead easy to address Actors
* Enforced parental supervision gives you Error Kernel design for free
* ``DeathWatch`` makes it possible to observe termination of Actors
* ``Router``\s unify what was ``ActorPool``\s with ``LoadBalancer``\s to form a flexible, extensible and transparent entity which quacks like an ``ActorRef``
* ``Stash``, an API made by Phillip Haller to do conditional receives
* Extensions enable you to augment ``ActorSystem``\s
* Scale up and out through configuration
* API unification and simplification
* Excellent performance, up to 20 million msg/second on a single machine, see the `blog article <http://letitcrash.com/post/17607272336/scalability-of-fork-join-pool>`_
* Slimmer footprint gives you around 2.7 million Actors per GB of memory

Dispatchers
^^^^^^^^^^^

* ``Dispatcher``\s are now configured in the configuration file and not the code, for easy tuning of deployed applications
* ``Dispatcher`` was previously known as ExecutorBasedEventDrivenDispatcher
* ``BalancingDispatcher`` was previously known as ``ExecutorBasedEventDrivenWorkStealingDispatcher``, is now work-sharing and you can configure which mailbox type should be used
* ``PinnedDispatcher`` was previously known as ``ThreadBasedDispatcher``
* Create your own ``Dispatcher``\s or ``MessageQueues`` (mailbox backing storage) and hook in through config
* Many different ``MessageQueue``\s: ``Priority``, ``Bounded``, ``Durable`` (ZooKeeper, Beanstalk, File, Redis, Mongo)

Remoting
^^^^^^^^

* Completely transparent in user code
* Pluggable transports, ships with a scalable Netty implementation
* Create actors remotely using configuration or in code

TypedActors
^^^^^^^^^^^

* Completely new implementation built on top of JDK Proxies
* 0 external dependencies, so now in akka-actor
* Built as an Akka Extension

Futures & Promises
^^^^^^^^^^^^^^^^^^

* Harmonized API with `SIP-14 <http://docs.scala-lang.org/sips/pending/futures-promises.html>`_ (big props to the EPFL team: Philipp Haller, Aleksandar Prokopec, Heather Miller and Vojin Jovanovic)
* Smaller footprint
* Completely non-blocking implementation

Akka STM
^^^^^^^^

* Now uses ScalaSTM
* ``Transactor``\s
* ``Agent``\s

EventBus
^^^^^^^^

* A simple and easy to use API for Publish/Subscribe

Config
^^^^^^

* Now using `HOCON <https://github.com/typesafehub/config>`_, extremely powerful and easy to use
* Big props to Havoc Pennington

Serialization
^^^^^^^^^^^^^

* Highly pluggable system for serializing objects
* Mappings go into configuration, no need to mix business logic and marshalling
* Built as an Akka Extension

Patterns
^^^^^^^^

* "Ask/?" is now a ``Pattern`` — for Scala add ``import akka.pattern.ask``, for Java use ``akka.pattern.Patterns.ask()``.
* ``gracefulStop``
* ``pipeTo``

ExecutionContext
^^^^^^^^^^^^^^^^

* One abstraction for asynchronous execution of logic

ømq
^^^

* An API for using Akka with `ømq <http://www.zeromq.org/>`_
* Huge thanks to Karim Osman and Ivan Porto Carrero
* Built as an Akka Extension

Brand new website, still at http://akka.io, huge thanks to Heather Miller for her outstanding work

Upcoming Releases
-----------------

Things that will be released within the coming months:

* Akka Camel 2.0, codename "Alpakka", with the excellent work of Raymond Roestenburg and Piotr Gabryanczyk
* Akka AMQP 2.0, with the excellent work of John Stanford
* Akka Spring 2.0, with the excellent help of Josh Long

Akka is released under the Apache V2 license.

**Happy hAkking!**
