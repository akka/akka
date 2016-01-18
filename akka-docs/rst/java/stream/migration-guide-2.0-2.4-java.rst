.. _migration-streams-2.0-2.4-java:

##############################
Migration Guide 2.0.x to 2.4.x
##############################

General notes
=============

Java DSL now uses Java 8 types: CompletionStage and Optional
------------------------------------------------------------

In order to provide a top-notch Java API we switched from Scala’s Future and Akka’s
``akka.japi.Option`` interim solutions to the JDK’s own types for deferred computation
and optional results. This has been done throughout Streams & HTTP, most notably changing most
materialized types, but also the signature of the ``mapAsync`` combinator and the
asynchronous route result combinators in the HTTP DSL.

The ``akka.pattern`` package has been updated with a new set of implementations within
the ``PatternCS`` class that provide the ability to interact between Actors and Futures
(or streams) for ``CompletionStage``.

Should you have the need to use Scala Futures with these new Java APIs please use
the ``scala-java8-compat`` library that comes as a dependency of Akka. For more
information see `the documentation`__.

__ https://github.com/scala/scala-java8-compat

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

This means that Java code like this::

    Source<String, BoxedUnit> source = Source.from(Arrays.asList("1", "2", "3"));
    Sink<String, Future<BoxedUnit>> sink = Sink.ignore();

needs to be changed into::

    Source<String, NotUsed> source = Source.from(Arrays.asList("1", "2", "3"));
    Sink<String, Future<Done>> sink = Sink.ignore();

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

    // This no longer works!
    Flow.of(Integer.class).expand(i -> i)(i -> new Pair<>(i, i));

In Akka 2.4.x this is simplified to:

.. includecode:: ../code/docs/stream/MigrationsJava.java#expand-continually

If state needs to be be kept during the expansion process then this state will
need to be managed by the Iterator. The example of counting the number of
expansions might previously have looked like::

    // This no longer works!
    Flow.of(Integer.class).expand(i -> new Pair<>(i, 0))(
      pair -> new Pair<>(new Pair<>(pair.first(), pair.second()),
                         new Pair<>(pair.first(), pair.second() + 1)));

In Akka 2.4.x this is formulated like so:

.. includecode:: ../code/docs/stream/MigrationsJava.java#expand-state

Changed Sources / Sinks
=======================

Sink.asPublisher is now configured using an enum
------------------------------------------------

In order to not use a meaningless boolean parameter we have changed the signature to:

.. includecode:: ../code/docs/stream/MigrationsJava.java#asPublisher-import

.. includecode:: ../code/docs/stream/MigrationsJava.java#asPublisher

IO Sources / Sinks materialize IOResult
---------------------------------------

Materialized values of the following sources and sinks:

  * ``FileIO.fromFile``
  * ``FileIO.toFile``
  * ``StreamConverters.fromInputStream``
  * ``StreamConverters.fromOutputStream``

have been changed from ``Long`` to ``akka.stream.io.IOResult``.
This allows to signal more complicated completion scenarios. For example, on failure it is now possible
to return the exception and the number of bytes written until that exception occured.

PushStage, PushPullStage and DetachedStage have been deprecated in favor of GraphStage
======================================================================================

The :class:`PushStage` :class:`PushPullStage` and :class:`DetachedStage` classes have been deprecated and
should be replaced by :class:`GraphStage` (:ref:`graphstage-java`) which is now a single powerful API
for custom stream processing.

Update procedure
----------------

Please consult the :class:`GraphStage` documentation (:ref:`graphstage-java`) and the `previous migration guide`_
on migrating from :class:`AsyncStage` to :class:`GraphStage`.

.. _`previous migration guide`: http://doc.akka.io/docs/akka-stream-and-http-experimental/2.0.2/java/migration-guide-1.0-2.x-java.html#AsyncStage_has_been_replaced_by_GraphStage


Changes in Akka HTTP
====================

Routing settings parameter name
-------------------------------

``RoutingSettings`` were previously the only setting available on ``RequestContext``,
and were accessible via ``settings``. We now made it possible to configure the parsers
settings as well, so ``RoutingSettings`` is now ``routingSettings`` and ``ParserSettings`` is
now accessible via ``parserSettings``.