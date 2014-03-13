.. _cluster-client:

Cluster Client
==============

An actor system that is not part of the cluster can communicate with actors
somewhere in the cluster via this ``ClusterClient``. The client can of course be part of
another cluster. It only needs to know the location of one (or more) nodes to use as initial
contact points. It will establish a connection to a ``ClusterReceptionist`` somewhere in
the cluster. It will monitor the connection to the receptionist and establish a new
connection if the link goes down. When looking for a new receptionist it uses fresh
contact points retrieved from previous establishment, or periodically refreshed contacts,
i.e. not necessarily the initial contact points. Also, note it's necessary to change
``akka.actor.provider`` from ``akka.actor.LocalActorRefProvider`` to 
``akka.remote.RemoteActorRefProvider`` or ``akka.cluster.ClusterActorRefProvider`` when using
the cluster client. 

The receptionist is supposed to be started on all nodes, or all nodes with specified role,
in the cluster. The receptionist can be started with the ``ClusterReceptionistExtension``
or as an ordinary actor.

You can send messages via the ``ClusterClient`` to any actor in the cluster that is registered
in the ``DistributedPubSubMediator`` used by the ``ClusterReceptionist``.
The ``ClusterReceptionistExtension`` provides methods for registration of actors that
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
The ``sender()`` of the response messages, as seen by the client, is preserved
as the original sender(), so the client can choose to send subsequent messages
directly to the actor in the cluster.

While establishing a connection to a receptionist the ``ClusterClient`` will buffer
messages and send them when the connection is established. If the buffer is full
the ``ClusterClient`` will throw ``akka.actor.StashOverflowException``, which can be
handled in by the supervision strategy of the parent actor. The size of the buffer 
can be configured by the following ``stash-capacity`` setting of the mailbox that is 
used by the ``ClusterClient`` actor. 

.. includecode:: @contribSrc@/src/main/resources/reference.conf#cluster-client-mailbox-config

An Example
----------

On the cluster nodes first start the receptionist. Note, it is recommended to load the extension 
when the actor system is started by defining it in the ``akka.extensions`` configuration property::

   akka.extensions = ["akka.contrib.pattern.ClusterReceptionistExtension"]

Next, register the actors that should be available for the client.

.. includecode:: @contribSrc@/src/multi-jvm/scala/akka/contrib/pattern/ClusterClientSpec.scala#server

On the client you create the ``ClusterClient`` actor and use it as a gateway for sending
messages to the actors identified by their path (without address information) somewhere
in the cluster.

.. includecode:: @contribSrc@/src/multi-jvm/scala/akka/contrib/pattern/ClusterClientSpec.scala#client

The ``initialContacts`` parameter is a ``Set[ActorSelection]``, which can be created like this:

.. includecode:: @contribSrc@/src/multi-jvm/scala/akka/contrib/pattern/ClusterClientSpec.scala#initialContacts

You will probably define the address information of the initial contact points in configuration or system property.

A more comprehensive sample is available in the `Typesafe Activator <http://www.typesafe.com/platform/getstarted>`_
tutorial named `Distributed workers with Akka and Scala! <http://www.typesafe.com/activator/template/akka-distributed-workers>`_
and `Distributed workers with Akka and Java! <http://www.typesafe.com/activator/template/akka-distributed-workers-java>`_.

ClusterReceptionistExtension
----------------------------

In the example above the receptionist is started and accessed with the ``akka.contrib.pattern.ClusterReceptionistExtension``.
That is convenient and perfectly fine in most cases, but it can be good to know that it is possible to
start the ``akka.contrib.pattern.ClusterReceptionist`` actor as an ordinary actor and you can have several
different receptionists at the same time, serving different types of clients.

The ``ClusterReceptionistExtension`` can be configured with the following properties:

.. includecode:: @contribSrc@/src/main/resources/reference.conf#receptionist-ext-config

Note that the ``ClusterReceptionistExtension`` uses the ``DistributedPubSubExtension``, which is described
in :ref:`distributed-pub-sub`.

It is recommended to load the extension when the actor system is started by defining it in the
``akka.extensions`` configuration property::

   akka.extensions = ["akka.contrib.pattern.ClusterReceptionistExtension"]

