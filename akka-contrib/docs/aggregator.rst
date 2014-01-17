.. _aggregator:

Aggregator Pattern
==================
The aggregator pattern supports writing actors that aggregate data from multiple
other actors and updates its state based on those responses. It is even harder to
optionally aggregate more data based on the runtime state of the actor or take
certain actions (sending another message and get another response) based on two or
more previous responses.

A common thought is to use the ask pattern to request information from other
actors. However, ask creates another actor specifically for the ask. We cannot
use a callback from the future to update the state as the thread executing the
callback is not defined. This will likely close-over the current actor.

The aggregator pattern solves such scenarios. It makes sure we're
acting from the same actor in the scope of the actor receive.

Introduction
------------
The aggregator pattern allows match patterns to be dynamically added to and removed
from an actor from inside the message handling logic. All match patterns are called
from the receive loop and run in the thread handling the incoming message. These
dynamically added patterns and logic can safely read and/or modify this actor's
mutable state without risking integrity or concurrency issues.

Usage
-----
To use the aggregator pattern, you need to extend the :class:`Aggregator` trait.
The trait takes care of :class:`receive` and actors extending this trait should
not override :class:`receive`. The trait provides the :class:`expect`,
:class:`expectOnce`, and :class:`unexpect` calls. The :class:`expect` and
:class:`expectOnce` calls return a handle that can be used for later de-registration
by passing the handle to :class:`unexpect`.

:class:`expect` is often used for standing matches such as catching error messages or timeouts.

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/pattern/AggregatorSpec.scala#expect-timeout

:class:`expectOnce` is used for matching the initial message as well as other requested messages

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/pattern/AggregatorSpec.scala#initial-expect
.. includecode:: @contribSrc@/src/test/scala/akka/contrib/pattern/AggregatorSpec.scala#expect-balance

:class:`unexpect` can be used for expecting multiple responses until a timeout or when the logic
dictates such an :class:`expect` no longer applies.

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/pattern/AggregatorSpec.scala#unexpect-sample

As the name eludes, :class:`expect` keeps the partial function matching any
received messages until :class:`unexpect` is called or the actor terminates,
whichever comes first. On the other hand, :class:`expectOnce` removes the partial
function once a match has been established.

It is a common pattern to register the initial expectOnce from the construction
of the actor to accept the initial message. Once that message is received, the
actor starts doing all aggregations and sends the response back to the original
requester. The aggregator should terminate after the response is sent (or timed
out). A different original request should use a different actor instance.

As you can see, aggregator actors are generally stateful, short lived actors.

Sample Use Case - AccountBalanceRetriever
-----------------------------------------
This example below shows a typical and intended use of the aggregator pattern.

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/pattern/AggregatorSpec.scala#demo-code

Sample Use Case - Multiple Response Aggregation and Chaining
------------------------------------------------------------
A shorter example showing aggregating responses and chaining further requests.

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/pattern/AggregatorSpec.scala#chain-sample

Pitfalls
--------
* The current implementation does not match the sender of the message. This is
  designed to work with :class:`ActorSelection` as well as :class:`ActorRef`.
  Without the sender(), there is a chance a received message can be matched by
  more than one partial function. The partial function that was registered via
  :class:`expect` or :class:`expectOnce` first (chronologically) and is not yet
  de-registered by :class:`unexpect` takes precedence in this case. Developers
  should make sure the messages can be uniquely matched or the wrong logic can
  be executed for a certain message.

* The :class:`sender` referenced in any :class:`expect` or :class:`expectOnce`
  logic refers to the sender() of that particular message and not the sender() of
  the original message. The original sender() still needs to be saved so a final
  response can be sent back.

* :class:`context.become` is not supported when extending the :class:`Aggregator`
  trait.

* We strongly recommend against overriding :class:`receive`. If your use case
  really dictates, you may do so with extreme caution. Always provide a pattern
  match handling aggregator messages among your :class:`receive` pattern matches,
  as follows::

    case msg if handleMessage(msg) ⇒ // noop
    // side effects of handleMessage does the actual match


Sorry, there is not yet a Java implementation of the aggregator pattern available.