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






Looking at :ref:`message-delivery-guarantees` one might come to the conclusion that
Akka actors are made for blue-sky scenarios: sending messages is the only way
for actors to communicate, and then that is not even guaranteed to work. Is the
whole paradigm built on sand? Of course the answer is an emphatic “No!”.

A local message send—within the same JVM instance—is not likely to fail, and if
it does the reason was one of

* it was meant to fail (due to consciously choosing a bounded mailbox, which
  upon overflow will have to drop messages)
* or it failed due to a catastrophic VM error, e.g. an
  :class:`OutOfMemoryError`, a memory access violation (“segmentation fault”,
  GPF, etc.), JVM bug—or someone calling ``System.exit()``.

In all of these cases, the actor was very likely not in a position to process
the message anyway, so this part of the non-guarantee is not problematic.

It is a lot more likely for an unintended message delivery failure to occur
when a message send crosses JVM boundaries, i.e. an intermediate unreliable
network is involved. If someone unplugs an ethernet cable, or a power failure
shuts down a router, messages will be lost while the actors would be able to
process them just fine.

.. note::

   This does not mean that message send semantics are different between local
   and remote operations, it just means that in practice there is a difference
   between how good the “best effort” is.

Introducing the Reliable Proxy
------------------------------

.. image:: ReliableProxy.png

To bridge the disparity between “local” and “remote” sends is the goal of this
pattern. When sending from A to B must be as reliable as in-JVM, regardless of
the deployment, then you can interject a reliable tunnel and send through that
instead. The tunnel consists of two end-points, where the ingress point P (the
“proxy”) is a child of A and the egress point E is a child of P, deployed onto
the same network node where B lives. Messages sent to P will be wrapped in an
envelope, tagged with a sequence number and sent to E, who verifies that the
received envelope has the right sequence number (the next expected one) and
forwards the contained message to B. When B receives this message, the
``sender`` will be a reference to the sender of the original message to P.
Reliability is added by E replying to orderly received messages with an ACK, so
that P can tick those messages off its resend list. If ACKs do not come in a
timely fashion, P will try to resend until successful.

Exactly what does it guarantee?
-------------------------------

Sending via a :class:`ReliableProxy` makes the message send exactly as reliable
as if the represented target were to live within the same JVM, provided that
the remote actor system does not terminate. In effect, both ends (i.e. JVM and
actor system) must be considered as one when evaluating the reliability of this
communication channel. The benefit is that the network in-between is taken out
of that equation.

When the target actor terminates, the proxy will terminate as well (on the
terms of :ref:`deathwatch-java` / :ref:`deathwatch-scala`).

How to use it
-------------

Since this implementation does not offer much in the way of configuration,
simply instantiate a proxy wrapping some target reference. From Java it looks
like this:

.. includecode:: @contribSrc@/src/test/java/akka/contrib/pattern/ReliableProxyTest.java#import
.. includecode:: @contribSrc@/src/test/java/akka/contrib/pattern/ReliableProxyTest.java#demo-proxy

And from Scala like this:

.. includecode:: @contribSrc@/src/multi-jvm/scala/akka/contrib/pattern/ReliableProxySpec.scala#demo

Since the :class:`ReliableProxy` actor is an :ref:`fsm-scala`, it also offers
the capability to subscribe to state transitions. If you need to know when all
enqueued messages have been received by the remote end-point (and consequently
been forwarded to the target), you can subscribe to the FSM notifications and
observe a transition from state :class:`ReliableProxy.Active` to state
:class:`ReliableProxy.Idle`.

.. includecode:: @contribSrc@/src/test/java/akka/contrib/pattern/ReliableProxyTest.java#demo-transition

From Scala it would look like so:

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/pattern/ReliableProxyDocSpec.scala#demo-transition


The Actor Contract
------------------

Message it Processes
^^^^^^^^^^^^^^^^^^^^

* :class:`FSM.SubscribeTransitionCallBack` and :class:`FSM.UnsubscribeTransitionCallBack`, see :ref:`fsm-scala`
* internal messages declared within :obj:`ReliableProxy`, *not for external use*
* any other message is transferred through the reliable tunnel and forwarded to the designated target actor

Messages it Sends
^^^^^^^^^^^^^^^^^

* :class:`FSM.CurrentState` and :class:`FSM.Transition`, see :ref:`fsm-scala`
 
Exceptions it Escalates
^^^^^^^^^^^^^^^^^^^^^^^

* no specific exception types
* any exception encountered by either the local or remote end-point are escalated (only fatal VM errors)

Arguments it Takes
^^^^^^^^^^^^^^^^^^

* *target* is the :class:`ActorRef` to which the tunnel shall reliably deliver
  messages, ``B`` in the above illustration.
* *retryAfter* is the timeout for receiving ACK messages from the remote
  end-point; once it fires, all outstanding message sends will be retried.

