.. _cluster-client-scala:

Cluster Client
==============

An actor system that is not part of the cluster can communicate with actors
somewhere in the cluster via this ``ClusterClient``. The client can of course be part of
another cluster. It only needs to know the location of one (or more) nodes to use as initial
contact points. It will establish a connection to a ``ClusterReceptionist`` somewhere in
the cluster. It will monitor the connection to the receptionist and establish a new
connection if the link goes down. When looking for a new receptionist it uses fresh
contact points retrieved from previous establishment, or periodically refreshed contacts,
i.e. not necessarily the initial contact points. 

.. note::

  ``ClusterClient`` should not be used when sending messages to actors that run
  within the same cluster. Similar functionality as the ``ClusterClient`` is
  provided in a more efficient way by :ref:`distributed-pub-sub-scala` for actors that 
  belong to the same cluster. 

Also, note it's necessary to change ``akka.actor.provider`` from ``akka.actor.LocalActorRefProvider`` 
to ``akka.remote.RemoteActorRefProvider`` or ``akka.cluster.ClusterActorRefProvider`` when using
the cluster client. 

The receptionist is supposed to be started on all nodes, or all nodes with specified role,
in the cluster. The receptionist can be started with the ``ClusterClientReceptionist`` extension
or as an ordinary actor.

You can send messages via the ``ClusterClient`` to any actor in the cluster that is registered
in the ``DistributedPubSubMediator`` used by the ``ClusterReceptionist``.
The ``ClusterClientReceptionist`` provides methods for registration of actors that
should be reachable from the client. Messages are wrapped in ``ClusterClient.Send``,
``ClusterClient.SendToAll`` or ``ClusterClient.Publish``.

**1. ClusterClient.Send**

The message will be delivered to one recipient with a matching path, if any such
exists. If several entries match the path the message will be delivered
to one random destination. The sender() of the message can specify that local
affinity is preferred, i.e. the message is sent to an actor in the same local actor
system as the used receptionist actor, if any such exists, otherwise random to any other
matching entry.

**2. ClusterClient.SendToAll**

The message will be delivered to all recipients with a matching path.

**3. ClusterClient.Publish**

The message will be delivered to all recipients Actors that have been registered as subscribers
to the named topic.

Response messages from the destination actor are tunneled via the receptionist
to avoid inbound connections from other cluster nodes to the client, i.e.
the ``sender()``, as seen by the destination actor, is not the client itself.
The ``sender()`` of the response messages, as seen by the client, is ``deadLetters``
since the client should normally send subsequent messages via the ``ClusterClient``.
It is possible to pass the original sender inside the reply messages if
the client is supposed to communicate directly to the actor in the cluster.

While establishing a connection to a receptionist the ``ClusterClient`` will buffer
messages and send them when the connection is established. If the buffer is full
the ``ClusterClient`` will drop old messages when new messages are sent via the client.
The size of the buffer is configurable and it can be disabled by using a buffer size of 0.

It's worth noting that messages can always be lost because of the distributed nature
of these actors. As always, additional logic should be implemented in the destination
(acknowledgement) and in the client (retry) actors to ensure at-least-once message delivery.
 
An Example
----------

On the cluster nodes first start the receptionist. Note, it is recommended to load the extension 
when the actor system is started by defining it in the ``akka.extensions`` configuration property::

   akka.extensions = ["akka.cluster.client.ClusterClientReceptionist"]

Next, register the actors that should be available for the client.

.. includecode:: ../../../akka-cluster-tools/src/multi-jvm/scala/akka/cluster/client/ClusterClientSpec.scala#server

On the client you create the ``ClusterClient`` actor and use it as a gateway for sending
messages to the actors identified by their path (without address information) somewhere
in the cluster.

.. includecode:: ../../../akka-cluster-tools/src/multi-jvm/scala/akka/cluster/client/ClusterClientSpec.scala#client

The ``initialContacts`` parameter is a ``Set[ActorPath]``, which can be created like this:

.. includecode:: ../../../akka-cluster-tools/src/multi-jvm/scala/akka/cluster/client/ClusterClientSpec.scala#initialContacts

You will probably define the address information of the initial contact points in configuration or system property.
See also :ref:`cluster-client-config-scala`.

A more comprehensive sample is available in the `Lightbend Activator <http://www.lightbend.com/platform/getstarted>`_
tutorial named `Distributed workers with Akka and Scala! <http://www.lightbend.com/activator/template/akka-distributed-workers>`_.

ClusterClientReceptionist Extension
-----------------------------------

In the example above the receptionist is started and accessed with the ``akka.cluster.client.ClusterClientReceptionist`` extension.
That is convenient and perfectly fine in most cases, but it can be good to know that it is possible to
start the ``akka.cluster.client.ClusterReceptionist`` actor as an ordinary actor and you can have several
different receptionists at the same time, serving different types of clients.

Note that the ``ClusterClientReceptionist`` uses the ``DistributedPubSub`` extension, which is described
in :ref:`distributed-pub-sub-scala`.

It is recommended to load the extension when the actor system is started by defining it in the
``akka.extensions`` configuration property::

   akka.extensions = ["akka.cluster.client.ClusterClientReceptionist"]

Dependencies
------------

To use the Cluster Client you must add the following dependency in your project.

sbt::

    "com.typesafe.akka" %% "akka-cluster-tools" % "@version@" @crossString@

maven::

  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-cluster-tools_@binVersion@</artifactId>
    <version>@version@</version>
  </dependency>

.. _cluster-client-config-scala:
  
Configuration
-------------

The ``ClusterClientReceptionist`` extension (or ``ClusterReceptionistSettings``) can be configured 
with the following properties:

.. includecode:: ../../../akka-cluster-tools/src/main/resources/reference.conf#receptionist-ext-config

The following configuration properties are read by the ``ClusterClientSettings`` 
when created with a ``ActorSystem`` parameter. It is also possible to amend the ``ClusterClientSettings`` 
or create it from another config section with the same layout as below. ``ClusterClientSettings`` is 
a parameter to the ``ClusterClient.props`` factory method, i.e. each client can be configured 
with different settings if needed.
  
.. includecode:: ../../../akka-cluster-tools/src/main/resources/reference.conf#cluster-client-config

Failure handling
----------------
When the cluster client is started it must be provided with a list of initial contacts which are cluster
nodes where receptionists are running. It will then repeatedly (with an interval configurable
by ``establishing-get-contacts-interval``) try to contact those until it gets in contact with one of them.
While running, the list of contacts are continuously updated with data from the receptionists (again, with an
interval configurable with ``refresh-contacts-interval``), so that if there are more receptionists in the cluster
than the initial contacts provided to the client the client will learn about them.

While the client is running it will detect failures in its connection to the receptionist by heartbeats
if more than a configurable amount of heartbeats are missed the client will try to reconnect to its known
set of contacts to find a receptionist it can access.

When the cluster cannot be reached at all
-----------------------------------------
It is possible to make the cluster client stop entirely if it cannot find a receptionist it can talk to
within a configurable interval. This is configured with the ``reconnect-timeout``, which defaults to ``off``.
This can be useful when initial contacts are provided from some kind of service registry, cluster node addresses
are entirely dynamic and the entire cluster might shut down or crash, be restarted on new addresses. Since the
client will be stopped in that case a monitoring actor can watch it and upon ``Terminate`` a new set of initial
contacts can be fetched and a new cluster client started.