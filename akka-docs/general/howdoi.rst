.. _howdoi:

How do I …
================================

This section of the Akka Documentation tries to answer common usage questions.

… deal with blocking third-party code?
--------------------------------------

Some times you cannot avoid doing blocking, and in that case you might want to explore the following:

    1. Isolate the blocking to a dedicated ``ExecutionContext``
    2. Configure the actor to have a bounded-mailbox as to prevent from excessive mailbox sizes

.. note::

    Before you do anything at all, measure!


… use persistence with Akka?
----------------------------

You just use it?
You might want to have a look at the answer to the question about blocking though.

… use a pool of connections/whatnots
------------------------------------

You most probably want to wrap that pooling service as an Akka Extension,
see the docs for documentation on Java / Scala Extensions.