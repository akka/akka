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

``conflate`` has been renamed to ``conflateWithSeed()``
-------------------------------------------------------

The new ``conflate`` operator is a special case of the original behavior (renamed to ``conflateWithSeed``) that does not
change the type of the stream. The usage of the new operator is as simple as::

   Flow.of(Integer.class).conflate((a, b) -> a + b) // Add numbers while downstream is not ready

Which is the same as using ``conflateWithSeed`` with an identity function::

   Flow.of(Integer.class).conflateWithSeed(x -> x, (a, b) -> a + b) // Add numbers while downstream is not ready


``viaAsync`` and ``viaAsyncMat`` has been replaced with ``async()``
-------------------------------------------------------------------
``async()`` is available from ``Sink``, ``Source``, ``Flow`` and the sub flows. It provides a shortcut for
setting the attribute ``Attributes.asyncBoundary`` on a flow. The existing methods ``Flow.viaAsync`` and
``Flow.viaAsyncMat`` has been removed to make marking out asynchronous boundaries more consistent::

    // This no longer works
    source.viaAsync(flow)

In Akka 2.4.x this will instead look lile this:

.. includecode:: ../code/docs/stream/MigrationsJava.java#async


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

Client / server behaviour on cancelled entity
---------------------------------------------

Previously if request or response were cancelled or consumed only partially
(e.g. by using ``take`` combinator) the remaining data was silently drained to prevent stalling
the connection, since there could still be more requests / responses incoming. Now the default
behaviour is to close the connection in order to prevent using excessive resource usage in case
of huge entities.

The old behaviour can be achieved by explicitly draining the entity:

   response.entity().getDataBytes().runWith(Sink.ignore())

SslTls has been renamed to TLS and moved
----------------------------------------

The DSL to access a TLS (or SSL) :class:`BidiFlow` have now split between the ``javadsl`` and ``scaladsl`` packages and
have been renamed to :class:`TLS`. Common option types (closing modes, authentication modes, etc.) have been moved to
the top level ``stream`` package, and the common message types are accessible in the class :class:`akka.stream.TLSProtocol`

Websocket now consistently named WebSocket
------------------------------------------

Previously we had a mix of methods and classes called ``websocket`` or ``Websocket``, which was in contradiction with
how the word is spelled in the spec and some other places of Akka HTTP.

Methods and classes using the word WebSocket now consistently use it as ``WebSocket``, so updating is as simple as
find-and-replacing the lower-case ``s`` to an upper-case ``S`` wherever the word WebSocket appeared.

Java DSL for Http binding and connections changed
-------------------------------------------------

In order to minimise the number of needed overloads for each method defined on the ``Http`` extension
a new mini-DSL has been introduced for connecting to hosts given a hostname, port and optional ``ConnectionContext``.

The availability of the connection context (if it's set to ``HttpsConnectionContext``) makes the server be bound
as an HTTPS server, and for outgoing connections those settings are used instead of the default ones if provided.

Was::

    http.cachedHostConnectionPool(toHost("akka.io"), materializer());
    http.cachedHostConnectionPool("akka.io", 80, httpsConnectionContext, materializer()); // does not work anymore

Replace with::

    http.cachedHostConnectionPool(toHostHttps("akka.io", 8081), materializer());
    http.cachedHostConnectionPool(toHostHttps("akka.io", 8081).withCustomHttpsContext(httpsContext), materializer());


Framing moved to akka.stream.[javadsl/scaladsl]
-----------------------------------------------

The ``Framing`` object which can be used to chunk up ``ByteString`` streams into
framing dependent chunks (such as lines) has moved to ``akka.stream.scaladsl.Framing``,
and has gotten a Java DSL equivalent type in ``akka.stream.javadsl.Framing``.
