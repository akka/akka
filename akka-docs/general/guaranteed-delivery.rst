
.. _guaranteed-delivery:

#####################
 Guaranteed Delivery
#####################


Guaranteed Delivery
===================

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

Bottom line; you as a developer knows what guarantees you need in your
application and can solve it fastest and most reliable by explicit ``ACK`` and
``RETRY`` (if you really need it, most often you don't). Using Akka's Durable
Mailboxes could help with this.

.. _Erlang documentation: http://www.erlang.org/faq/academic.html
