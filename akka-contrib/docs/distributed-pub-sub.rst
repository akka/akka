.. _distributed-pub-sub:

Distributed Publish Subscribe in Cluster
========================================

How do I send a message to an actor without knowing which node it is running on?

How do I send messages to all actors in the cluster that have registered interest
in a named topic?

This pattern provides a mediator actor, ``akka.contrib.pattern.DistributedPubSubMediator``,
that manages a registry of actor references and replicates the entries to peer
actors among all cluster nodes or a group of nodes tagged with a specific role.

The `DistributedPubSubMediator` is supposed to be started on all nodes,
or all nodes with specified role, in the cluster. The mediator can be
started with the ``DistributedPubSubExtension`` or as an ordinary actor.

Changes are only performed in the own part of the registry and those changes
are versioned. Deltas are disseminated in a scalable way to other nodes with
a gossip protocol. The registry is eventually consistent, i.e. changes are not
immediately visible at other nodes, but typically they will be fully replicated
to all other nodes after a few seconds.

You can send messages via the mediator on any node to registered actors on
any other node. There is three modes of message delivery.

**1. DistributedPubSubMediator.Send**

The message will be delivered to one recipient with a matching path, if any such
exists in the registry. If several entries match the path the message will be sent
via the supplied ``RoutingLogic`` (default random) to one destination. The sender() of the
message can specify that local affinity is preferred, i.e. the message is sent to an actor
in the same local actor system as the used mediator actor, if any such exists, otherwise
route to any other matching entry. A typical usage of this mode is private chat to one
other user in an instant messaging application. It can also be used for distributing
tasks to registered workers, like a cluster aware router where the routees dynamically
can register themselves.

**2. DistributedPubSubMediator.SendToAll**

The message will be delivered to all recipients with a matching path. Actors with
the same path, without address information, can be registered on different nodes.
On each node there can only be one such actor, since the path is unique within one
local actor system. Typical usage of this mode is to broadcast messages to all replicas
with the same path, e.g. 3 actors on different nodes that all perform the same actions,
for redundancy. You can also optionally specify a property (``allButSelf``) deciding
if the message should be sent to a matching path on the self node or not.

**3. DistributedPubSubMediator.Publish**

Actors may be registered to a named topic instead of path. This enables many subscribers
on each node. The message will be delivered to all subscribers of the topic. For
efficiency the message is sent over the wire only once per node (that has a matching topic),
and then delivered to all subscribers of the local topic representation. This is the
true pub/sub mode. A typical usage of this mode is a chat room in an instant messaging
application.

You register actors to the local mediator with ``DistributedPubSubMediator.Put`` or
``DistributedPubSubMediator.Subscribe``. ``Put`` is used together with ``Send`` and
``SendToAll`` message delivery modes. The ``ActorRef`` in ``Put`` must belong to the same
local actor system as the mediator. ``Subscribe`` is used together with ``Publish``.
Actors are automatically removed from the registry when they are terminated, or you
can explicitly remove entries with ``DistributedPubSubMediator.Remove`` or
``DistributedPubSubMediator.Unsubscribe``.

Successful ``Subscribe`` and ``Unsubscribe`` is acknowledged with
``DistributedPubSubMediator.SubscribeAck`` and ``DistributedPubSubMediator.UnsubscribeAck``
replies.

A Small Example in Java
-----------------------

A subscriber actor:

.. includecode:: @contribSrc@/src/test/java/akka/contrib/pattern/DistributedPubSubMediatorTest.java#subscriber

Subscriber actors can be started on several nodes in the cluster, and all will receive
messages published to the "content" topic.

.. includecode:: @contribSrc@/src/test/java/akka/contrib/pattern/DistributedPubSubMediatorTest.java#start-subscribers

A simple actor that publishes to this "content" topic:

.. includecode:: @contribSrc@/src/test/java/akka/contrib/pattern/DistributedPubSubMediatorTest.java#publisher

It can publish messages to the topic from anywhere in the cluster:

.. includecode:: @contribSrc@/src/test/java/akka/contrib/pattern/DistributedPubSubMediatorTest.java#publish-message

A Small Example in Scala
------------------------

A subscriber actor:

.. includecode:: @contribSrc@/src/multi-jvm/scala/akka/contrib/pattern/DistributedPubSubMediatorSpec.scala#subscriber

Subscriber actors can be started on several nodes in the cluster, and all will receive
messages published to the "content" topic.

.. includecode:: @contribSrc@/src/multi-jvm/scala/akka/contrib/pattern/DistributedPubSubMediatorSpec.scala#start-subscribers

A simple actor that publishes to this "content" topic:

.. includecode:: @contribSrc@/src/multi-jvm/scala/akka/contrib/pattern/DistributedPubSubMediatorSpec.scala#publisher

It can publish messages to the topic from anywhere in the cluster:

.. includecode:: @contribSrc@/src/multi-jvm/scala/akka/contrib/pattern/DistributedPubSubMediatorSpec.scala#publish-message

DistributedPubSubExtension
--------------------------

In the example above the mediator is started and accessed with the ``akka.contrib.pattern.DistributedPubSubExtension``.
That is convenient and perfectly fine in most cases, but it can be good to know that it is possible to
start the mediator actor as an ordinary actor and you can have several different mediators at the same
time to be able to divide a large number of actors/topics to different mediators. For example you might
want to use different cluster roles for different mediators.

The ``DistributedPubSubExtension`` can be configured with the following properties:

.. includecode:: @contribSrc@/src/main/resources/reference.conf#pub-sub-ext-config

It is recommended to load the extension when the actor system is started by defining it in
``akka.extensions`` configuration property. Otherwise it will be activated when first used
and then it takes a while for it to be populated.

::

   akka.extensions = ["akka.contrib.pattern.DistributedPubSubExtension"]

