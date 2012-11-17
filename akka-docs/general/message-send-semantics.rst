
.. _message-send-semantics:

#######################
 Message send semantics
#######################



Guaranteed Delivery?
====================

Akka does *not* support guaranteed delivery.

First it is close to impossible to actually give guarantees like that,
second it is extremely costly trying to do so.
The network is inherently unreliable and there is no such thing as 100%
guarantee delivery, so it can never be guaranteed.

The question is what to guarantee. That:

1. The message is sent out on the network?
2. The message is received by the other host?
3. The message is put on the target actor's mailbox?
4. The message is applied to the target actor?
5. The message is starting to be executed by the target actor?
6. The message is finished executing by the target actor?

Each one of this have different challenges and costs.

Akka embraces distributed computing and the network and makes it explicit
through message passing, therefore it does not try to lie and emulate a
leaky abstraction. This is a model that have been used with great success
in Erlang and requires the user to model his application around. You can
read more about this approach in the `Erlang documentation`_ (section
10.9 and 10.10), Akka follows it closely.

Bottom line: you as a developer know what guarantees you need in your
application and can solve it fastest and most reliable by explicit ``ACK`` and
``RETRY`` (if you really need it, most often you don't). Using Akka's Durable
Mailboxes could help with this.

Delivery semantics
==================

At-most-once
------------

Actual transports may provide stronger semantics,
but at-most-once is the semantics you should expect.
The alternatives would be once-and-only-once, which is extremely costly, 
or at-least-once which essentially requires idempotency of message processing,
which is a user-level concern.

Ordering is preserved on a per-sender basis
-------------------------------------------

Actor ``A1`` sends messages ``M1``, ``M2``, ``M3`` to ``A2``
Actor ``A3`` sends messages ``M4``, ``M5``, ``M6`` to ``A2``

This means that:
    1) If ``M1`` is delivered it must be delivered before ``M2`` and ``M3``
    2) If ``M2`` is delivered it must be delivered before ``M3``
    3) If ``M4`` is delivered it must be delivered before ``M5`` and ``M6``
    4) If ``M5`` is delivered it must be delivered before ``M6``
    5) ``A2`` can see messages from ``A1`` interleaved with messages from ``A3``
    6) Since there is no guaranteed delivery, none, some or all of the messages may arrive to ``A2``

.. _Erlang documentation: http://www.erlang.org/faq/academic.html
