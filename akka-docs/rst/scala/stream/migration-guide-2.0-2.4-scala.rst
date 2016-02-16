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

Removed ImplicitMaterializer
============================

The helper trait :class:`ImplicitMaterializer` has been removed as it was hard to find and the feature was not worth
the extra trait. Defining an implicit materializer inside an enclosing actor can be done this way::

    final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))

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

``conflate`` has been renamed to ``conflateWithSeed()``
-------------------------------------------------------

The new ``conflate`` operator is a special case of the original behavior (renamed to ``conflateWithSeed``) that does not
change the type of the stream. The usage of the new operator is as simple as::

   Flow[Int].conflate(_ + _) // Add numbers while downstream is not ready

Which is the same as using ``conflateWithSeed`` with an identity function

   Flow[Int].conflateWithSeed(identity)(_ + _) // Add numbers while downstream is not ready


``viaAsync`` and ``viaAsyncMat`` has been replaced with ``async``
-----------------------------------------------------------------
``async`` is available from ``Sink``, ``Source``, ``Flow`` and the sub flows. It provides a shortcut for
setting the attribute ``Attributes.asyncBoundary`` on a flow. The existing methods ``Flow.viaAsync`` and
``Flow.viaAsyncMat`` has been removed to make marking out asynchronous boundaries more consistent::

    // This no longer works
    source.viaAsync(flow)

In Akka 2.4.x this will instead look lile this:

.. includecode:: ../code/docs/stream/MigrationsScala.scala#async


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

   response.entity.dataBytes.runWith(Sink.ignore)

Changed Sources / Sinks
=======================

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
should be replaced by :class:`GraphStage` (:ref:`graphstage-scala`) which is now a single powerful API
for custom stream processing.

Update procedure
----------------

Please consult the :class:`GraphStage` documentation (:ref:`graphstage-scala`) and the `previous migration guide`_
on migrating from :class:`AsyncStage` to :class:`GraphStage`.

.. _`previous migration guide`: http://doc.akka.io/docs/akka-stream-and-http-experimental/2.0.2/scala/migration-guide-1.0-2.x-scala.html#AsyncStage_has_been_replaced_by_GraphStage

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

SslTls has been renamed to TLS and moved
----------------------------------------

The DSL to access a TLS (or SSL) :class:`BidiFlow` have now split between the ``javadsl`` and ``scaladsl`` packages and
have been renamed to :class:`TLS`. Common option types (closing modes, authentication modes, etc.) have been moved to
the top level ``stream`` package, and the common message types are accessible in the class :class:`akka.stream.TLSProtocol`

Framing moved to akka.stream.[javadsl/scaladsl]
-----------------------------------------------

The ``Framing`` object which can be used to chunk up ``ByteString`` streams into
framing dependent chunks (such as lines) has moved to ``akka.stream.scaladsl.Framing``,
and has gotten a Java DSL equivalent type in ``akka.stream.javadsl.Framing``.
