.. _stream-dynamic-scala:

#######################
Dynamic stream handling
#######################

.. _kill-switch-scala:

Controlling graph completion with KillSwitch
--------------------------------------------

A ``KillSwitch`` allows the completion of graphs of ``FlowShape`` from the outside. It consists of a flow element that
can be linked to a graph of ``FlowShape`` needing completion control.
The ``KillSwitch`` trait allows to complete or fail the graph(s).

.. includecode:: ../../../../akka-stream/src/main/scala/akka/stream/KillSwitch.scala
   :include: kill-switch

After the first call to either ``shutdown`` or ``abort``, all subsequent calls to any of these methods will be ignored.
Graph completion is performed by both

* completing its downstream
* cancelling (in case of ``shutdown``) or failing (in case of ``abort``) its upstream.

A ``KillSwitch`` can control the completion of one or multiple streams, and therefore comes in two different flavours.

.. _unique-kill-switch-scala:

UniqueKillSwitch
^^^^^^^^^^^^^^^^

``UniqueKillSwitch`` allows to control the completion of **one** materialized ``Graph`` of ``FlowShape``. Refer to the
below for usage examples.

* **Shutdown**

.. includecode:: ../code/docs/stream/KillSwitchDocSpec.scala#unique-shutdown

* **Abort**

.. includecode:: ../code/docs/stream/KillSwitchDocSpec.scala#unique-abort

.. _shared-kill-switch-scala:

SharedKillSwitch
^^^^^^^^^^^^^^^^

A ``SharedKillSwitch`` allows to control the completion of an arbitrary number graphs of ``FlowShape``. It can be
materialized multiple times via its ``flow`` method, and all materialized graphs linked to it are controlled by the switch.
Refer to the below for usage examples.

* **Shutdown**

.. includecode:: ../code/docs/stream/KillSwitchDocSpec.scala#shared-shutdown

* **Abort**

.. includecode:: ../code/docs/stream/KillSwitchDocSpec.scala#shared-abort

.. note::
   A ``UniqueKillSwitch`` is always a result of a materialization, whilst ``SharedKillSwitch`` needs to be constructed
   before any materialization takes place.

Dynamic fan-in and fan-out with MergeHub and BroadcastHub
---------------------------------------------------------

There are many cases when consumers or producers of a certain service (represented as a Sink, Source, or possibly Flow)
are dynamic and not known in advance. The Graph DSL does not allow to represent this, all connections of the graph
must be known in advance and must be connected upfront. To allow dynamic fan-in and fan-out streaming, the Hubs
should be used. They provide means to construct :class:`Sink` and :class:`Source` pairs that are "attached" to each
other, but one of them can be materialized multiple times to implement dynamic fan-in or fan-out.

Using the MergeHub
^^^^^^^^^^^^^^^^^^

A :class:`MergeHub` allows to implement a dynamic fan-in junction point in a graph where elements coming from
different producers are emitted in a First-Comes-First-Served fashion. If the consumer cannot keep up then *all* of the
producers are backpressured. The hub itself comes as a :class:`Source` to which the single consumer can be attached.
It is not possible to attach any producers until this :class:`Source` has been materialized (started). This is ensured
by the fact that we only get the corresponding :class:`Sink` as a materialized value. Usage might look like this:

.. includecode:: ../code/docs/stream/HubsDocSpec.scala#merge-hub

This sequence, while might look odd at first, ensures proper startup order. Once we get the :class:`Sink`,
we can use it as many times as wanted. Everything that is fed to it will be delivered to the consumer we attached
previously until it cancels.

Using the BroadcastHub
^^^^^^^^^^^^^^^^^^^^^^

A :class:`BroadcastHub` can be used to consume elements from a common producer by a dynamic set of consumers. The
rate of the producer will be automatically adapted to the slowest consumer. In this case, the hub is a :class:`Sink`
to which the single producer must be attached first. Consumers can only be attached once the :class:`Sink` has
been materialized (i.e. the producer has been started). One example of using the :class:`BroadcastHub`:

.. includecode:: ../code/docs/stream/HubsDocSpec.scala#broadcast-hub

The resulting :class:`Source` can be materialized any number of times, each materialization effectively attaching
a new subscriber. If there are no subscribers attached to this hub then it will not drop any elements but instead
backpressure the upstream producer until subscribers arrive. This behavior can be tweaked by using the combinators
``.buffer`` for example with a drop strategy, or just attaching a subscriber that drops all messages. If there
are no other subscribers, this will ensure that the producer is kept drained (dropping all elements) and once a new
subscriber arrives it will adaptively slow down, ensuring no more messages are dropped.

Combining dynamic stages to build a simple Publish-Subscribe service
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The features provided by the Hub implementations are limited by default. This is by design, as various combinations
can be used to express additional features like unsubscribing producers or consumers externally. We show here
an example that builds a :class:`Flow` representing a publish-subscribe channel. The input of the :class:`Flow` is
published to all subscribers while the output streams all the elements published.

First, we connect a :class:`MergeHub` and a :class:`BroadcastHub` together to form a publish-subscribe channel. Once
we materialize this small stream, we get back a pair of :class:`Source` and :class:`Sink` that together define
the publish and subscribe sides of our channel.

.. includecode:: ../code/docs/stream/HubsDocSpec.scala#pub-sub-1

We now use a few tricks to add more features. First of all, we attach a ``Sink.ignore``
at the broadcast side of the channel to keep it drained when there are no subscribers. If this behavior is not the
desired one this line can be simply dropped.

.. includecode:: ../code/docs/stream/HubsDocSpec.scala#pub-sub-2

We now wrap the :class:`Sink` and :class:`Source` in a :class:`Flow` using ``Flow.fromSinkAndSource``. This bundles
up the two sides of the channel into one and forces users of it to always define a publisher and subscriber side
(even if the subscriber side is just dropping). It also allows us to very simply attach a :class:`KillSwitch` as
a :class:`BidiStage` which in turn makes it possible to close both the original :class:`Sink` and :class:`Source` at the
same time.
Finally, we add ``backpressureTimeout`` on the consumer side to ensure that subscribers that block the channel for more
than 3 seconds are forcefully removed (and their stream failed).

.. includecode:: ../code/docs/stream/HubsDocSpec.scala#pub-sub-3

The resulting Flow now has a type of ``Flow[String, String, UniqueKillSwitch]`` representing a publish-subscribe
channel which can be used any number of times to attach new producers or consumers. In addition, it materializes
to a :class:`UniqueKillSwitch` (see :ref:`unique-kill-switch-scala`) that can be used to deregister a single user externally:


.. includecode:: ../code/docs/stream/HubsDocSpec.scala#pub-sub-4
