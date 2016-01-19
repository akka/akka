.. _migration-streams-2.0-2.4-scala:

##############################
Migration Guide 2.0.x to 2.4.x
##############################

General notes
=============

akka.Done and akka.NotUsed replacing Unit and BoxedUnit
-------------------------------------------------------

To provide more clear signatures and have a unified API for both
Java and Scala two new types have been introduced:

``akka.NotUsed`` is meant to be used instead of ``Unit`` in Scala
and ``BoxedUnit`` in Java to signify that the type parameter is required
but not actually used. This is commonly the case with ``Source``, ``Flow`` and ``Sink``
that do not materialize into any value.

``akka.Done`` is added for the use case where it is boxed inside another object to signify
completion but there is no actual value attached to the completion. It is used to replace
occurrences of ``Future<BoxedUnit>`` with ``Future<Done>`` in Java and ``Future[Unit]`` with
``Future[Done]`` in Scala.

All previous usage of ``Unit`` and ``BoxedUnit`` for these two cases in the akka streams APIs
has been updated.

This means that Scala code like this::

    Source[Int, Unit] source = Source.from(1 to 5)
    Sink[Int, Future[Unit]] sink = Sink.ignore()


needs to be changed into::

    Source[Int, NotUsed] source = Source.from(1 to 5)
    Sink[Int, Future[Done]] sink = Sink.ignore()

These changes apply to all the places where streams are used, which means that signatures
in the persistent query APIs also are affected.

Changed Operators
=================

``expand()`` is now based on an Iterator
----------------------------------------

Previously the ``expand`` combinator required two functions as input: the first
one lifted incoming values into an extrapolation state and the second one
extracted values from that, possibly evolving that state. This has been
simplified into a single function that turns the incoming element into an
Iterator.

The most prominent use-case previously was to just repeat the previously received value::

    Flow[Int].expand(identity)(s => (s, s)) // This no longer works!

In Akka 2.4.x this is simplified to:

.. includecode:: ../code/docs/stream/MigrationsScala.scala#expand-continually

If state needs to be be kept during the expansion process then this state will
need to be managed by the Iterator. The example of counting the number of
expansions might previously have looked like::

    // This no longer works!
    Flow[Int].expand((_, 0)){ case (in, count) => (in, count) -> (in, count + 1) }

In Akka 2.4.x this is formulated like so:

.. includecode:: ../code/docs/stream/MigrationsScala.scala#expand-state

