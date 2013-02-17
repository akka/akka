.. _io-layer:

################
I/O Layer Design
################

The ``akka.io`` package has been developed in collaboration between the Akka
and `spray.io`_ teams. Its design incorporates the experiences with the
``spray-io`` module along with improvements that were jointly developed for
more general consumption as an actor-based service.

Requirements
============

In order to form a general and extensible IO layer basis for a wide range of
applications, with Akka remoting and spray HTTP being the initial ones, the
following requirements were established as key drivers for the design:

* scalability to millions of concurrent connections

* lowest possible latency in getting data from an input channel into the
  target actor’s mailbox

* maximal throughput

* optional back-pressure in both directions (i.e. throttling local senders as
  well as allowing local readers to throttle remote senders, where allowed by
  the protocol)

* a purely actor-based API with immutable data representation

* extensibility for integrating new transports by way of a very lean SPI; the
  goal is to not force I/O mechanisms into a lowest common denominator but
  instead allow completely protocol-specific user-level APIs.

Basic Architecture
==================

Each transport implementation will be made available as a separate Akka
extension, offering an :class:`ActorRef` representing the initial point of
contact for client code. This "manager" accepts requests for establishing a
communications channel (e.g. connect or listen on a TCP socket). Each
communications channel is represented by one dedicated actor, which is exposed
to client code for all interaction with this channel over its entire lifetime.

The central element of the implementation is the transport-specific “selector”
actor; in the case of TCP this would wrap a :class:`java.nio.channels.Selector`.
The channel actors register their interest in readability or writability of
their channel by sending corresponding messages to their assigned selector
actor. However, the actual channel reading and writing is performed by the
channel actors themselves, which frees the selector actors from time-consuming
tasks and thereby ensures low latency. The selector actor's only responsibility
is the management of the underlying selector's key set and the actual select
operation, which is the only operation to typically block.

The assignment of channels to selectors is performed by the manager actor and
remains unchanged for the entire lifetime of a channel. Thereby the management
actor "stripes" new channels across one or more selector actors based on some
implementation-specific distribution logic. This logic may be delegated (in
part) to the selectors actors, which could, for example, choose to reject the
assignment of a new channel when they consider themselves to be at capacity.

The manager actor creates (and therefore supervises) the selector actors, which
in turn create and supervise their channel actors. The actor hierarchy of one
single transport implementation therefore consists of three distinct actor
levels, with the management actor at the top-, the channel actors at the leaf-
and the selector actors at the mid-level.

Back-pressure for output is enabled by allowing the user to specify within its
:class:`Write` messages whether it wants to receive an acknowledgement for
enqueuing that write to the O/S kernel. Back-pressure for input is enabled by
sending the channel actor a message which temporarily disables read interest
for the channel until reading is re-enabled with a corresponding resume command.
In the case of transports with flow control—like TCP—the act of not
consuming data at the receiving end (thereby causing them to remain in the
kernels read buffers) is propagated back to the sender, linking these two
mechanisms across the network.

Design Benefits
===============

Staying within the actor model for the whole implementation allows us to remove
the need for explicit thread handling logic, and it also means that there are
no locks involved (besides those which are part of the underlying transport
library). Writing only actor code results in a cleaner implementation,
while Akka’s efficient actor messaging does not impose a high tax for this
benefit. In fact the event-based nature of I/O maps so well to the actor model
that we expect clear performance and especially scalability benefits over
traditional solutions with explicit thread management and synchronization.

Another benefit of supervision hierarchies is that clean-up of resources comes
naturally: shutting down a selector actor will automatically clean up all
channel actors, allowing proper closing of the channels and sending the
appropriate messages to user-level client actors. DeathWatch allows the channel
actors to notice the demise of their user-level handler actors and terminate in
an orderly fashion in that case as well; this naturally reduces the chances of
leaking open channels.

The choice of using :class:`ActorRef` for exposing all functionality entails
that these references can be distributed or delegated freely and in general
handled as the user sees fit, including the use of remoting and life-cycle
monitoring (just to name two).

How to go about Adding a New Transport
======================================

The best start is to study the TCP reference implementation to get a good grip
on the basic working principle and then design an implementation, which is
similar in spirit, but adapted to the new protocol in question. There are vast
differences between I/O mechanisms (e.g. compare file I/O to a message broker)
and the goal of this I/O layer is explicitly **not** to shoehorn all of them
into a uniform API, which is why only the basic architecture ideas are
documented here.

.. _spray.io: http://spray.io

