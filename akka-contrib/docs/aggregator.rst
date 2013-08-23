.. _aggregator:

Aggregator Pattern
==================
It is currently not straightforward to write an actor that aggregates data from
multiple other actors and updates its state based on those responses. It is even
harder to optionally aggregate more data based on the runtime state of the actor
or take certain actions (sending another message and get another response) based
on two or more previous responses.

A common thought is to use the ask pattern to request information from other
actors. However, ask creates another actor specifically for the ask. We cannot
use a callback from the future to update the state as the thread executing the
callback is not defined. This will likely close-over the current actor.

The aggregator pattern solves such scenarios. It makes sure we're
acting from the same actor in the scope of the actor receive.

Introducing the Aggregator Pattern
----------------------------------
The aggregator pattern allows match patterns to be dynamically added to and removed
from an actor from inside the message handling logic. All match patterns are called
from the receive loop and run in the thread handling the incoming message. By
itself, the dynamically added receive logic will by itself never cause close-overs.

To use the Aggregator pattern, you need to extend the :class:`Aggregator` trait.
The trait takes care of receive. You must not override receive without delegating to
the trait. The trait provides the :class:`expect`, :class:`expectOnce`, and
:class:`unexpect` calls. The :class:`expect` and :class:`expectOnce` calls return
a handle that can be used for later de-registration of the expect by passing the
handle to :class:`unexpect`.

As the name says eludes, :class:`expect` keeps the partial function matching any
received messages until :class:`unexpect` is called or the actor terminates,
whichever comes first. On the other hand, :class:`expectOnce` removes the partial
function once a match has been established.

It is a common pattern to register the initial expectOnce from the construction
of the actor to accept the initial message. Once that message is received, the
actor starts doing all aggregations and sends the response back to the original
requester. The aggregator should terminate after the response is sent (or timed
out). A different original request should use a different actor instance.

As you can see, Aggregator actors are generally stateful, short lived actors.

Usage
-----
This example below shows a typical and intended use of the Aggregator pattern.
.. includecode:: @contribSrc@/src/test/scala/akka/contrib/pattern/AggregatorSpec.scala#demo-code

Sorry, there is not yet a Java implementation of the Aggregator pattern available.

Pitfalls
--------
The current implementation does not match the sender of the message. This is designed
to work with :class:`ActorSelection` as well as :class:`ActorRef`. Without the sender,
there is a chance a received message can be matched by more than one partial function.
The partial function that was registered first (chronologically) and is not yet
removed takes precedence in this case. Developers should make sure the messages can
be uniquely matched or the wrong logic can be executed for a certain message.