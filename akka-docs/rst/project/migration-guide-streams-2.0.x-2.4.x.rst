.. _migration-streams-2.0.x-2.4.x:

###########################################
Migration Guide Akka Streams 2.0.x to 2.4.x
###########################################

General notes
=============



akka.Done and akka.NotUsed replacing Unit and BoxedUnit
-------------------------------------------------------
To provide more clear signatures and have a unified API for both
Java and Scala two new types have been introduced:

``akka.NotUsed`` is meant to be used instead of ``Unit`` in Scala
and ``BoxedUnit`` in Java to signify that the type parameter is required
but not actually used. This is commonly the case with ``Source``s, ``Flow``s and ``Sink``s
that do not materialize into any value.

``akka.Done`` is added for the use case where it is boxed inside another object to signify
completion but there is no actual value attached to the completion. It is used to replace
occurrences of ``Future<BoxedUnit>`` with ``Future<Done>`` in Java and ``Future[Unit]`` with
 ``Future[Done]`` in Scala.

All previous usage of ``Unit`` and ``BoxedUnit`` for these two cases in the akka streams APIs
has been updated.

This means that Java code like this::

    Source<String, BoxedUnit> source = Source.from(Arrays.asList("1", "2", "3"));
    Sink<String, Future<BoxedUnit>> sink = Sink.ignore()

needs to be changed into::

    Source<String, NotUsed> source = Source.from(Arrays.asList("1", "2", "3"));
    Sink<String, Future<Done>> sink = Sink.ignore()

And Scala code like this::

    Source[Int, Unit] source = Source.from(1 to 5)
    Sink[Int, Future[Unit]] sink = Sink.ignore()


needs to be changed into::

    Source[Int, NotUsed] source = Source.from(1 to 5)
    Sink[Int, Future[Done]] sink = Sink.ignore()

These changes apply to all the places where streams are used, which means that signatures
in the persistent query APIs also are affected.
