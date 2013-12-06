.. _migration-2.3:

################################
 Migration Guide 2.2.x to 2.3.x
################################

The 2.3 release contains some structural changes that require some
simple, mechanical source-level changes in client code.

When migrating from earlier versions you should first follow the instructions for
migrating :ref:`1.3.x to 2.0.x <migration-2.0>` and then :ref:`2.0.x to 2.1.x <migration-2.1>`
and then :ref:`2.1.x to 2.2.x <migration-2.2>`.

Removed hand over data in cluster singleton
===========================================

The support for passing data from previous singleton instance to new instance
in a graceful leaving scenario has been removed. Valuable state should be persisted
in durable storage instead, e.g. using akka-persistence. The constructor/props parameters
of ``ClusterSingletonManager`` has been changed to ordinary ``Props`` parameter for the
singleton actor instead of the factory parameter.

Changed cluster auto-down configuration
=======================================

``akka.cluster.auto-down`` setting has been replaced by ``akka.cluster.auto-down-unreachable-after``,
which instructs the cluster to automatically mark unreachable nodes as DOWN after this
configured time of unreachability. This feature is disabled by default, as it also was in 2.2.x.

During the deprecation phase ``akka.cluster.auto-down=on`` is interpreted at as instant auto-down.

=======
Routers
=======

The routers have been cleaned up and enhanced. The routing logic has been extracted to be usable within
normal actors as well. Some usability problems have been have been solved, such as properly reject invalid
configuration combinations. Routees can be dynamically added and removed by sending special management messages
to the router.

The two types of routers have been named ``Pool`` and ``Group`` to make them more distinguishable and reduce confusion
of their subtle differences:

* Pool - The router creates routees as child actors and removes them from the router if they
  terminate.
  
* Group - The routee actors are created externally to the router and the router sends
  messages to the specified path using actor selection, without watching for termination.

Configuration of routers is compatible with 2.2.x, but the ``router`` type should preferably be specified
with ``-pool`` or ``-group`` suffix.

Some classes used for programmatic definition of routers have been renamed, but the old classes remain as
deprecated. The compiler will guide you with deprecation warning. For example ``RoundRobinRouter`` has
been renamed to ``RoundRobinPool`` or ``RoundRobinGroup`` depending on which type you are actually using.

There is no replacement for ``SmallestMailboxRouter`` combined with routee paths, i.e. a group, because that
combination is not useful.

An optional API enhancement that makes the code read better is to use the ``props`` method instead of ``withRouter``.
``withRouter`` has not been deprecated and you can continue to use that if you prefer that way of defining a router. 

Example in Scala::

    context.actorOf(FromConfig.props(Props[Worker]), "router1")
    context.actorOf(RoundRobinPool(5).props(Props[Worker]), "router2") 

Example in Java::

    getContext().actorOf(FromConfig.getInstance().props(Props.create(Worker.class)), 
      "router1");
      
    getContext().actorOf(new RoundRobinPool(5).props(Props.create(Worker.class)), 
        "router2");

To support multiple routee paths for a cluster aware router sending to paths the deployment configuration
property ``cluster.routees-path`` has been changed to string list ``routees.paths`` property.
The old ``cluster.routees-path`` is deprecated, but still working during the deprecation phase.

Example::

    /router4 {
      router = round-robin
      nr-of-instances = 10
      routees.paths = ["/user/myserviceA", "/user/myserviceB"]
      cluster.enabled = on
    }

The API for creating custom routers and resizers have changed without keeping the old API as deprecated. 
That should be a an API used by only a few users and they should be able to migrate to the new API
without much trouble.

Read more about the new routers in the :ref:`documentation for Scala <routing-scala>` and 
:ref:`documentation for Java <routing-java>`.

Changed cluster expected-response-after configuration
=====================================================

Configuration property ``akka.cluster.failure-detector.heartbeat-request.expected-response-after`` 
has been renamed to ``akka.cluster.failure-detector.expected-response-after``.
 