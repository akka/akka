.. _cluster-singleton-java:

Cluster Singleton
=================

For some use cases it is convenient and sometimes also mandatory to ensure that
you have exactly one actor of a certain type running somewhere in the cluster.

Some examples:

* single point of responsibility for certain cluster-wide consistent decisions, or
  coordination of actions across the cluster system
* single entry point to an external system
* single master, many workers
* centralized naming service, or routing logic

Using a singleton should not be the first design choice. It has several drawbacks,
such as single-point of bottleneck. Single-point of failure is also a relevant concern,
but for some cases this feature takes care of that by making sure that another singleton
instance will eventually be started.

The cluster singleton pattern is implemented by ``akka.cluster.singleton.ClusterSingletonManager``.
It manages one singleton actor instance among all cluster nodes or a group of nodes tagged with
a specific role. ``ClusterSingletonManager`` is an actor that is supposed to be started on
all nodes, or all nodes with specified role, in the cluster. The actual singleton actor is
started by the ``ClusterSingletonManager`` on the oldest node by creating a child actor from
supplied ``Props``. ``ClusterSingletonManager`` makes sure that at most one singleton instance
is running at any point in time.

The singleton actor is always running on the oldest member with specified role.
The oldest member is determined by ``akka.cluster.Member#isOlderThan``.
This can change when removing that member from the cluster. Be aware that there is a short time
period when there is no active singleton during the hand-over process.

The cluster failure detector will notice when oldest node becomes unreachable due to
things like JVM crash, hard shut down, or network failure. Then a new oldest node will
take over and a new singleton actor is created. For these failure scenarios there will
not be a graceful hand-over, but more than one active singletons is prevented by all
reasonable means. Some corner cases are eventually resolved by configurable timeouts.

You can access the singleton actor by using the provided ``akka.cluster.singleton.ClusterSingletonProxy``,
which will route all messages to the current instance of the singleton. The proxy will keep track of
the oldest node in the cluster and resolve the singleton's ``ActorRef`` by explicitly sending the
singleton's ``actorSelection`` the ``akka.actor.Identify`` message and waiting for it to reply.
This is performed periodically if the singleton doesn't reply within a certain (configurable) time.
Given the implementation, there might be periods of time during which the ``ActorRef`` is unavailable,
e.g., when a node leaves the cluster. In these cases, the proxy will buffer the messages sent to the 
singleton and then deliver them when the singleton is finally available. If the buffer is full
the ``ClusterSingletonProxy`` will drop old messages when new messages are sent via the proxy.
The size of the buffer is configurable and it can be disabled by using a buffer size of 0.

It's worth noting that messages can always be lost because of the distributed nature of these actors.
As always, additional logic should be implemented in the singleton (acknowledgement) and in the
client (retry) actors to ensure at-least-once message delivery.

The singleton instance will not run on members with status :ref:`WeaklyUp <weakly_up_java>` if that feature
is enabled.

Potential problems to be aware of
---------------------------------

This pattern may seem to be very tempting to use at first, but it has several drawbacks, some of them are listed below:

* the cluster singleton may quickly become a *performance bottleneck*,
* you can not rely on the cluster singleton to be *non-stop* available — e.g. when the node on which the singleton has
  been running dies, it will take a few seconds for this to be noticed and the singleton be migrated to another node,
* in the case of a *network partition* appearing in a Cluster that is using Automatic Downing  (see docs for 
  :ref:`automatic-vs-manual-downing-java`),
  it may happen that the isolated clusters each decide to spin up their own singleton, meaning that there might be multiple
  singletons running in the system, yet the Clusters have no way of finding out about them (because of the partition).

Especially the last point is something you should be aware of — in general when using the Cluster Singleton pattern
you should take care of downing nodes yourself and not rely on the timing based auto-down feature.

.. warning::
   **Be very careful when using Cluster Singleton together with Automatic Downing**,
   since it allows the cluster to split up into two separate clusters, which in turn will result
   in *multiple Singletons* being started, one in each separate cluster!

An Example
----------

Assume that we need one single entry point to an external system. An actor that
receives messages from a JMS queue with the strict requirement that only one
JMS consumer must exist to be make sure that the messages are processed in order.
That is perhaps not how one would like to design things, but a typical real-world
scenario when integrating with external systems.

On each node in the cluster you need to start the ``ClusterSingletonManager`` and
supply the ``Props`` of the singleton actor, in this case the JMS queue consumer.

.. includecode:: ../../../akka-cluster-tools/src/test/java/akka/cluster/singleton/ClusterSingletonManagerTest.java#create-singleton-manager

Here we limit the singleton to nodes tagged with the ``"worker"`` role, but all nodes, independent of
role, can be used by not specifying ``withRole``.

Here we use an application specific ``terminationMessage`` to be able to close the
resources before actually stopping the singleton actor. Note that ``PoisonPill`` is a
perfectly fine ``terminationMessage`` if you only need to stop the actor.

With the names given above, access to the singleton can be obtained from any cluster node using a properly
configured proxy.

.. includecode:: ../../../akka-cluster-tools/src/test/java/akka/cluster/singleton/ClusterSingletonManagerTest.java#create-singleton-proxy

A more comprehensive sample is available in the `Lightbend Activator <http://www.lightbend.com/platform/getstarted>`_
tutorial named `Distributed workers with Akka and Java! <http://www.lightbend.com/activator/template/akka-distributed-workers-java>`_.

Dependencies
------------

To use the Cluster Singleton you must add the following dependency in your project.

sbt::

    "com.typesafe.akka" %% "akka-cluster-tools" % "@version@" @crossString@

maven::

  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-cluster-tools_@binVersion@</artifactId>
    <version>@version@</version>
  </dependency>


Configuration
-------------

The following configuration properties are read by the ``ClusterSingletonManagerSettings`` 
when created with a ``ActorSystem`` parameter. It is also possible to amend the ``ClusterSingletonManagerSettings`` 
or create it from another config section with the same layout as below. ``ClusterSingletonManagerSettings`` is 
a parameter to the ``ClusterSingletonManager.props`` factory method, i.e. each singleton can be configured 
with different settings if needed.
  
.. includecode:: ../../../akka-cluster-tools/src/main/resources/reference.conf#singleton-config

The following configuration properties are read by the ``ClusterSingletonProxySettings`` 
when created with a ``ActorSystem`` parameter. It is also possible to amend the ``ClusterSingletonProxySettings`` 
or create it from another config section with the same layout as below. ``ClusterSingletonProxySettings`` is 
a parameter to the ``ClusterSingletonProxy.props`` factory method, i.e. each singleton proxy can be configured 
with different settings if needed.

.. includecode:: ../../../akka-cluster-tools/src/main/resources/reference.conf#singleton-proxy-config
