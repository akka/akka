.. _io-layer:

#######################
Design of the I/O Layer
#######################

The ``akka.io`` package has been developed in collaboration between the Akka
team and Mathias Doenitz & Johannes Rudolph from the `Spray framework`_. It has
been influenced by the experiences with the ``spray-io`` module and adapted for
more general consumption as an actor-based service.

The Underlying Requirements
===========================

In order to be suitable as the basic IO layer for Spray’s HTTP handling as well
as for Akka remoting, the following requirements were driving the design:

* scalability to millions of concurrent connections

* lowest possible latency in getting data from the input channel into the
  target actor’s mailbox

* maximize throughput at the same time

* optional back-pressure in both directions (i.e. throttling local senders as
  well as allowing local readers to throttle remote senders where the protocol
  allows this)

* a purely actor-based API with immutable representation of data

* extensibility for integrating new transports by way of a very lean SPI; the
  goal is to not force I/O mechanisms into a lowest common denominator but
  instead allow completely protocol-specific user-level APIs.

The Basic Principle
===================

Each transport implementation will be a separate Akka extension, offering an
:class:`ActorRef` representing the main point of entry for client code: this
manager accepts requests for establishing a communications channel (e.g.
connect or listen on a TCP socket). Each communications channel is represented
as one actor which is exposed to the client code for all interaction with this
channel.

The core piece of the implementation is the transport-specific “selector” actor;
in the example of TCP this would wrap a :class:`java.nio.channels.Selector`.
The channel actors register their interest in readability or writability of the
underlying channel by sending corresponding messages to their assigned selector
actor. An important point for achieving low latency is to hand off the actual
reading and writing to the channel actor, so that the selector actor’s only
responsibility is the management of the underlying selector’s key set and the
actual select operation (which is typically blocking).

The assignment of channels to selectors is done for the lifetime of a channel
by the manager actor; the natural choice is to have the manager supervise the
selectors, which in turn supervise their channels. In order to allow the
manager to make informed decisions, the selectors keep the manager updated
about their fill level by sending a message every time a channel is terminated.

Back-pressure for output is enabled by allowing the writer to specify within
the :class:`Write` messages whether it wants to receive an acknowledgement for
enqueuing that write to the O/S kernel.  Back-pressure for input is propagated
by back sending a message to the channel actor which will take the underlying
channel out of the selector until a corresponding resume command is received.
In the case of transports with flow control—like TCP—the act of not consuming
data from the stream at the receiving end is propagated back to the sender,
linking these two mechanisms across the network.

Benefits Resulting from this Design
===================================

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
appropriate messages to user-level client actors. DeathWatch allow the channel
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
on the basic working principle and then design an implementation which is
similar in spirit, but adapted to the new protocol in question. There are vast
differences between I/O mechanisms (e.g. compare file I/O to a message broker)
and the goal of this I/O layer is explicitly **not** to shoehorn all of them
into a uniform API, which is why only the basic working principle is documented
here.


.. _Spray framework: http://spray.io

