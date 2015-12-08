.. _migration-2.0-java:

############################
 Migration Guide 1.0 to 2.x
############################

The 2.0 release contains some structural changes that require some
simple, mechanical source-level changes in client code.


Introduced proper named constructor methods insted of ``wrap()``
================================================================

There were several, unrelated uses of ``wrap()`` which made it hard to find and hard to understand the intention of
the call. Therefore these use-cases now have methods with different names, helping Java 8 type inference (by reducing
the number of overloads) and finding relevant methods in the documentation.

Creating a Flow from other stages
---------------------------------

It was possible to create a ``Flow`` from a graph with the correct shape (``FlowShape``) using ``wrap()``. Now this
must be done with the more descriptive method ``Flow.fromGraph()``.

It was possible to create a ``Flow`` from a ``Source`` and a ``Sink`` using ``wrap()``. Now this functionality can
be accessed trough the more descriptive methods ``Flow.fromSinkAndSource`` and ``Flow.fromSinkAndSourceMat``.


Creating a BidiFlow from other stages
-------------------------------------

It was possible to create a ``BidiFlow`` from a graph with the correct shape (``BidiShape``) using ``wrap()``. Now this
must be done with the more descriptive method ``BidiFlow.fromGraph()``.

It was possible to create a ``BidiFlow`` from two ``Flow`` s using ``wrap()``. Now this functionality can
be accessed trough the more descriptive methods ``BidiFlow.fromFlows`` and ``BidiFlow.fromFlowsMat``.

Update procedure
----------------

1. Replace all uses of ``Flow.wrap`` when it converts a ``Graph`` to a ``Flow`` with ``Flow.fromGraph``
2. Replace all uses of ``Flow.wrap`` when it converts a ``Source`` and ``Sink`` to a ``Flow`` with
   ``Flow.fromSinkAndSource`` or ``Flow.fromSinkAndSourceMat``
3. Replace all uses of ``BidiFlow.wrap`` when it converts a ``Graph`` to a ``BidiFlow`` with ``BidiFlow.fromGraph``
4. Replace all uses of ``BidiFlow.wrap`` when it converts two ``Flow`` s to a ``BidiFlow`` with
   ``BidiFlow.fromFlows`` or ``BidiFlow.fromFlowsMat``
5. Replace all uses of ``BidiFlow.apply()`` (Scala DSL) or ``BidiFlow.create()`` (Java DSL) when it converts two
   functions to a ``BidiFlow`` with ``BidiFlow.fromFunctions``
Example
^^^^^^^

::

      Graph<SourceShape<Integer>, BoxedUnit> graphSource = ...;
      // This no longer works!
      Source<Integer, BoxedUnit> source = Source.wrap(graphSource);

      Graph<SinkShape<Integer>, BoxedUnit> graphSink = ...;
      // This no longer works!
      Sink<Integer, BoxedUnit> sink = Sink.wrap(graphSink);

      Graph<FlowShape<Integer, Integer>, BoxedUnit> graphFlow = ...;
      // This no longer works!
      Flow<Integer, Integer, BoxedUnit> flow = Flow.wrap(graphFlow);

      // This no longer works!
      Flow.wrap(Sink.<Integer>head(), Source.single(0), Keep.left());

should be replaced by

.. includecode:: code/docs/MigrationsJava.java#flow-wrap

and

::

      Graph<BidiShape<Integer, Integer, Integer, Integer>, BoxedUnit> bidiGraph = ...;
      // This no longer works!
      BidiFlow<Integer, Integer, Integer, Integer, BoxedUnit> bidiFlow = BidiFlow.wrap(bidiGraph);

      // This no longer works!
      BidiFlow.wrap(flow1, flow2, Keep.both());


Should be replaced by

.. includecode:: code/docs/MigrationsJava.java#bidi-wrap


Renamed ``inlet()`` and ``outlet()`` to ``in()`` and ``out()`` in ``SourceShape``, ``SinkShape`` and ``FlowShape``
==========================================================================================================

The input and output ports of these shapes where called ``inlet()`` and ``outlet()`` compared to other shapes that
consistently used ``in()`` and ``out()``. Now all :class:`Shape` s use ``in()`` and ``out()``.

Update procedure
----------------

Change all references to ``inlet()`` to ``in()`` and all references to ``outlet()`` to ``out()`` when referring to the ports
of :class:`FlowShape`, :class:`SourceShape` and :class:`SinkShape`.


FlowGraph class and builder methods have been renamed
=====================================================

Due to incorrect overlap with the :class:`Flow` concept we renamed the :class:`FlowGraph` class to :class:`GraphDSL`.
There is now only one graph creation method called ``create`` which is analogous to the old ``partial`` method. For
closed graphs now it is explicitly required to return ``ClosedShape`` at the end of the builder block.

Update procedure
----------------

1. Search and replace all occurrences of ``FlowGraph`` with ``GraphDSL``.
2. Replace all occurrences of ``GraphDSL.partial()`` or ``GraphDSL.closed()`` with ``GraphDSL.create()``.
3. Add ``ClosedShape`` as a return value of the builder block if it was ``FlowGraph.closed()`` before.
4. Wrap the closed graph with ``RunnableGraph.fromGraph`` if it was ``FlowGraph.closed()`` before.

Example
^^^^^^^

::

      // This no longer works!
      FlowGraph.factory().closed(builder -> {
        //...
      });

      // This no longer works!
      FlowGraph.factory().partial(builder -> {
        //...
        return new FlowShape<>(inlet, outlet);
      });

should be replaced by

.. includecode:: code/docs/MigrationsJava.java#graph-create

Methods that create Source, Sink, Flow from Graphs have been removed
====================================================================

Previously there were convenience methods available on ``Sink``, ``Source``, ``Flow`` an ``BidiFlow`` to create
these DSL elements from a graph builder directly. Now this requires two explicit steps to reduce the number of overloaded
methods (helps Java 8 type inference) and also reduces the ways how these elements can be created. There is only one
graph creation method to learn (``GraphDSL.create``) and then there is only one conversion method to use ``fromGraph()``.

This means that the following methods have been removed:
 - ``adapt()`` method on ``Source``, ``Sink``, ``Flow`` and ``BidiFlow`` (both DSLs)
 - ``apply()`` overloads providing a graph ``Builder`` on ``Source``, ``Sink``, ``Flow`` and ``BidiFlow`` (Scala DSL)
 - ``create()`` overloads providing a graph ``Builder`` on ``Source``, ``Sink``, ``Flow`` and ``BidiFlow`` (Java DSL)

Update procedure
----------------

Everywhere where ``Source``, ``Sink``, ``Flow`` and ``BidiFlow`` is created from a graph using a builder have to
be replaced with two steps

1. Create a ``Graph`` with the correct ``Shape`` using ``GraphDSL.create`` (e.g.. for  ``Source`` it means first
   creating a ``Graph`` with ``SourceShape``)
2. Create the required DSL element by calling ``fromGraph()`` on the required DSL element (e.g. ``Source.fromGraph``)
   passing the graph created in the previous step

Example
^^^^^^^

::

      // This no longer works!
      Source.factory().create(builder -> {
        //...
        return outlet;
      });

      // This no longer works!
      Sink.factory().create(builder -> {
        //...
        return inlet;
      });

      // This no longer works!
      Flow.factory().create(builder -> {
        //...
        return new Pair<>(inlet, outlet);
      });

      // This no longer works!
      BidiFlow.factory().create(builder -> {
        //...
        return new BidiShape<>(inlet1, outlet1, inlet2, outlet2);
      });

should be replaced by

.. includecode:: code/docs/MigrationsJava.java#graph-create-2

Some graph Builder methods have been removed
============================================

Due to the high number of overloads Java 8 type inference suffered, and it was also hard to figure out which time
to use which method. Therefore various redundant methods have been removed. As a consequence, every ``Sink``, ``Source``
and ``Flow`` needs to be explicitly added via ``builder.add()``.

Update procedure
----------------

1. All uses of ``builder.edge(outlet,inlet)`` should be replaced by the alternative ``builder.from(outlet).toInlet(inlet)``
3. All uses of ``builder.source`` should be replaced by ``builder.from(builder.add(source))``
4. All uses of ``builder.flow`` should be replaced by ``builder.….via(builder.add(flow))``
5. All uses of ``builder.sink`` should be replaced by ``builder.….to(builder.add(sink)))``

::

      FlowGraph.factory().closed(builder -> {
        // These no longer work
        builder.edge(outlet, inlet);
        builder.flow(outlet, flow, inlet);
        builder.source(Source.single(0));
        builder.sink(Sink.<Integer>head());
        //...
      });

should be replaced by

.. includecode:: code/docs/MigrationsJava.java#graph-builder

Source constructor name changes
===============================

``Source.lazyEmpty`` has been replaced by ``Source.maybe`` which returns a ``Promise`` that can be completed by one or
zero elements by providing an ``Option``. This is different from ``lazyEmpty`` which only allowed completion to be
sent, but no elements.

The ``from()`` overload on ``Source`` has been refactored to separate methods to reduce the number of overloads and
make source creation more discoverable.

``Source.subscriber`` has been renamed to ``Source.asSubscriber``.

Update procedure
----------------

1. All uses of ``Source.lazyEmpty`` should be replaced by ``Source.maybe`` and the returned ``Promise`` completed with
   a ``None`` (an empty ``Option``)
2. Replace all uses of ``Source.from(delay,interval,tick)`` with the method ``Source.tick(delay,interval,tick)``
3. Replace all uses of ``Source.from(publisher)`` with the method ``Source.fromPublisher(publisher)``
4. Replace all uses of ``Source.from(future)`` with the method ``Source.fromFuture(future))``
5. Replace all uses of ``Source.subscriber`` with the method ``Source.asSubscriber``

Example
^^^^^^^

::

      // This no longer works!
      Source<Integer, Promise<BoxedUnit>> src = Source.lazyEmpty();
      //...
      promise.trySuccess(BoxedUnit.UNIT);

      // This no longer works!
      final Source<String, Cancellable> ticks = Source.from(
        FiniteDuration.create(0, TimeUnit.MILLISECONDS),
        FiniteDuration.create(200, TimeUnit.MILLISECONDS),
        "tick");

      // This no longer works!
      final Source<Integer, BoxedUnit> pubSource =
        Source.from(TestPublisher.<Integer>manualProbe(true, sys));

      // This no longer works!
      final Source<Integer, BoxedUnit> futSource =
        Source.from(Futures.successful(42));

      // This no longer works!
      final Source<Integer, Subscriber<Integer>> subSource =
        Source.<Integer>subscriber();

should be replaced by

.. includecode:: code/docs/MigrationsJava.java#source-creators

Sink constructor name changes
=============================

``Sink.create(subscriber)`` has been renamed to ``Sink.fromSubscriber(subscriber)`` to reduce the number of overloads and
make sink creation more discoverable.

Update procedure
----------------

1. Replace all uses of ``Sink.create(subscriber)`` with the method ``Sink.fromSubscriber(subscriber)``

Example
^^^^^^^

::

      // This no longer works!
      final Sink<Integer, BoxedUnit> subSink =
        Sink.create(TestSubscriber.<Integer>manualProbe(sys));

should be replaced by

.. includecode:: code/docs/MigrationsJava.java#sink-creators

``Flow.empty()`` have been removed
==================================

The ``empty()`` method has been removed since it behaves exactly the same as ``create()``, creating a ``Flow`` with no
transformations added yet.

Update procedure
----------------

1. Replace all uses of ``Flow.empty()`` with ``Flow.create``.

::

      // This no longer works!
      Flow<Integer, Integer, BoxedUnit> emptyFlow = Flow.<Integer>empty();

should be replaced by

.. includecode:: code/docs/MigrationsJava.java#empty-flow

``flatten(FlattenStrategy)`` has been replaced by named counterparts
====================================================================

To simplify type inference in Java 8 and to make the method more discoverable, ``flatten(FlattenStrategy.concat)``
has been removed and replaced with the alternative method ``flatMapConcat(f)``.

Update procedure
----------------

1. Replace all occurrences of ``flatten(FlattenStrategy.concat)`` with ``flatMapConcat(identity)``
2. Consider replacing ``map(f).flatMapConcat(identity)`` with ``flatMapConcat(f)``

Example
^^^^^^^

::

   Flow.<Source<Integer, BoxedUnit>>create().flatten(FlattenStrategy.concat());

should be replaced by

.. includecode:: code/docs/MigrationsJava.java#flatMapConcat

`Sink.fanoutPublisher() and Sink.publisher() is now a single method`
====================================================================

It was a common user mistake to use ``Sink.publisher`` and get into trouble since it would only support
a single ``Subscriber``, and the discoverability of the apprpriate fix was non-obvious (Sink.fanoutPublisher).
To make the decision whether to support fanout or not an active one, the aforementioned methods have been
replaced with a single method: ``Sink.asPublisher(fanout: Boolean)``.

Update procedure
----------------

1. Replace all occurences of ``Sink.publisher`` with ``Sink.asPublisher(false)``
2. Replace all occurences of ``Sink.fanoutPublisher`` with ``Sink.asPublisher(true)``

Example
^^^^^^^

::

   // This no longer works!
   final Sink<Integer, Publisher<Integer>> pubSink =
     Sink.<Integer>publisher();

   // This no longer works!
   final Sink<Integer, Publisher<Integer>> pubSink =
     Sink.<Integer>fanoutPublisher(2, 8);

should be replaced by

.. includecode:: code/docs/MigrationsJava.java#sink-as-publisher

FlexiMerge an FlexiRoute has been replaced by GraphStage
========================================================

The ``FlexiMerge`` and ``FlexiRoute`` DSLs have been removed since they provided an abstraction that was too limiting
and a better abstraction have been created which is called ``GraphStage``. ``GraphStage`` can express fan-in and
fan-out stages, but many other constructs as well with possibly multiple input and output ports (e.g. a ``BidiStage``).

This new abstraction provides a more uniform way to crate custom stream processing stages of arbitrary ``Shape``. In
fact, all of the built-in fan-in and fan-out stages are now implemented in terms of ``GraphStage``.

Update procedure
----------------

*There is no simple update procedure. The affected stages must be ported to the new ``GraphStage`` DSL manually. Please
read the* ``GraphStage`` *documentation (TODO) for details.*

GroupBy, SplitWhen and SplitAfter now return SubFlow or SubSource
=================================================================

Previously the ``groupBy``, ``splitWhen``, and ``splitAfter`` combinators
returned a type that included a :class:`Source` within its elements.
Transforming these substreams was only possible by nesting the respective
combinators inside a ``map`` of the outer stream. This has been made more
convenient and also safer by dropping down into transforming the substreams
instead: the return type is now a :class:`SubSource` (for sources) or a
:class:`SubFlow` (for flows) that does not implement the :class:`Graph`
interface and therefore only represents an unfinished intermediate builder
step.

Update Procedure
----------------

The transformations that were done on the substreams need to be lifted up one
level. This only works for cases where the processing topology is homogenous
for all substreams.

Example
^^^^^^^

::

  Flow.<Integer> create()
    // This no longer works!
    .groupBy(i -> i % 2)
    // This no longer works!
    .map(pair -> pair.second().map(i -> i + 3))
    // This no longer works!
    .flatten(FlattenStrategy.concat())

This is implemented now as

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/MigrationsJava.java#group-flatten

Example 2
^^^^^^^^^

::

  Flow.<String> create()
    // This no longer works!
    .groupBy(i -> i)
    // This no longer works!
    .map(pair ->
      pair.second().runFold(new Pair<>(pair.first(), 0),
                            (pair, word) -> new Pair<>(word, pair.second() + 1)))
    // This no longer works!
    .mapAsyncUnordered(4, i -> i)

This is implemented now as

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/MigrationsJava.java#group-fold

Semantic change in ``isHoldingUpstream`` in the DetachedStage DSL
=================================================================

The ``isHoldingUpstream`` method used to return true if the upstream port was in holding state and a completion arrived
(inside the ``onUpstreamFinished`` callback). Now it returns ``false`` when the upstream is completed.

Update procedure
----------------

1. Those stages that relied on the previous behavior need to introduce an extra ``Boolean`` field with initial value
   ``false``
2. This field must be set on every call to ``holdUpstream()`` (and variants).
3. In completion, instead of calling ``isHoldingUpstream`` read this variable instead.

See the example in the AsyncStage migration section for an example of this procedure.

StatefulStage has been replaced by GraphStage
=============================================

The :class:`StatefulStage` class had some flaws and limitations, most notably around completion handling which
caused subtle bugs. The new :class:`GraphStage` (:ref:`graphstage-java`) solves these issues and should be used
instead.

Update procedure
----------------

There is no mechanical update procedure available. Please consult the :class:`GraphStage` documentation
(:ref:`graphstage-java`).


AsyncStage has been replaced by GraphStage
==========================================

Due to its complexity and inflexibility ``AsyncStage`` have been removed in favor of ``GraphStage``. Existing
``AsyncStage`` implementations can be ported in a mostly mechanical way.

Update procedure
----------------

1. The subclass of ``AsyncStage`` should be replaced by ``GraphStage``
2. The new subclass must define an ``in`` and ``out`` port (``Inlet`` and ``Outlet`` instance) and override the ``shape``
   method returning a ``FlowShape``
3. An instance of ``GraphStageLogic`` must be returned by overriding ``createLogic()``. The original processing logic and
   state will be encapsulated in this ``GraphStageLogic``
4. Using ``setHandler(port, handler)`` and ``InHandler`` instance should be set on ``in`` and an ``OutHandler`` should
   be set on ``out``
5. ``onPush``, ``onUpstreamFinished`` and ``onUpstreamFailed`` are now available in the ``InHandler`` subclass created
   by the user
6. ``onPull`` and ``onDownstreamFinished`` are now available in the ``OutHandler`` subclass created by the user
7. the callbacks above no longer take an extra `ctxt` context parameter.
8. ``onPull`` only signals the stage, the actual element can be obtained by calling ``grab(in)``
9. ``ctx.push(elem)`` is now ``push(out, elem)``
10. ``ctx.pull()`` is now ``pull(in)``
11. ``ctx.finish()`` is now ``completeStage()``
12. ``ctx.pushAndFinish(elem)`` is now simply two calls: ``push(out, elem); completeStage()``
13. ``ctx.fail(cause)`` is now ``failStage(cause)``
14. ``ctx.isFinishing()`` is now ``isClosed(in)``
15. ``ctx.absorbTermination()`` can be replaced with ``if (isAvailable(shape.outlet)) <call the onPull() handler>``
16. ``ctx.pushAndPull(elem)`` can be replaced with ``push(out, elem); pull(in)``
17. ``ctx.holdUpstreamAndPush`` and ``context.holdDownstreamAndPull`` can be replaced by simply ``push(elem)`` and
    ``pull()`` respectively
18. The following calls should be removed: ``ctx.ignore()``, ``ctx.holdUpstream()`` and ``ctx.holdDownstream()``.
19. ``ctx.isHoldingUpstream()`` can be replaced with ``isAvailable(out)``
20. ``ctx.isHoldingDowntream()`` can be replaced with ``!(isClosed(in) || hasBeenPulled(in))``
21. ``ctx.getAsyncCallback()`` is now ``getAsyncCallback(callback)`` which now takes a callback as a parameter. This
    would correspond to the ``onAsyncInput()`` callback in the original ``AsyncStage``

We show the necessary steps in terms of an example ``AsyncStage``

Example
^^^^^^^

TODO

Akka HTTP: Uri parsing mode relaxed-with-raw-query replaced with rawQueryString
===============================================================================

Previously Akka HTTP allowed to configure the parsing mode of an Uri's Query part (``?a=b&c=d``) to ``relaxed-with-raw-query``
which is useful when Uris are not formatted using the usual "key/value pairs" syntax.

Instead of exposing it as an option for the parser, this is now available as the ``Option<String> rawQueryString()``
/ ``Option<String> queryString()`` methods on on ``model.Uri``.

For parsing the Query part use ``Query query(Charset charset, Uri.ParsingMode mode)``.

Update procedure
----------------
1. If the ``uri-parsing-mode`` was set to ``relaxed-with-raw-query``, remove it
2. In places where the query string was accessed in ``relaxed-with-raw-query`` mode, use the ``rawQueryString``/``queryString`` methods instead
3. In places where the parsed query parts (such as ``parameter``) were used, invoke parsing directly using ``uri.query().get("a")``

Example
^^^^^^^

::

  // config, no longer works
  akka.http.parsing.uri-parsing-mode = relaxed-with-raw-query

should be replaced by:

.. includecode:: code/docs/MigrationsJava.java#raw-query

And use of query parameters from ``Uri`` that looked like this:

::

  // This no longer works!
  uri.parameter("name");

should be replaced by:

.. includecode:: code/docs/MigrationsJava.java#query-param

SynchronousFileSource and SynchronousFileSink
=============================================

Both have been replaced by ``FileIO.toFile(…)`` and ``FileIO.fromFile(…)`` due to discoverability issues
paired with names which leaked internal implementation details.

Update procedure
----------------

Replace ``SynchronousFileSource.create(`` with ``FileIO.fromFile(``

Replace ``SynchronousFileSink.create(`` with ``FileIO.toFile(``

Replace ``SynchronousFileSink.appendTo(f)`` with ``FileIO.toFile(f, true)``

Example
^^^^^^^

::

      // This no longer works!
      final Source<ByteString, Future<java.lang.Long>> src =
        SynchronousFileSource.create(new File("."));

      // This no longer works!
      final Source<ByteString, Future<java.lang.Long>> src =
        SynchronousFileSource.create(new File("."), 1024);

      // This no longer works!
      final Sink<ByteString, Future<java.lang.Long>> sink =
              `SynchronousFileSink.appendTo(new File("."));

should be replaced by

.. includecode:: code/docs/MigrationsJava.java#file-source-sink

InputStreamSource and OutputStreamSink
======================================

Both have been replaced by ``StreamConverters.fromInputStream(…)`` and ``StreamConverters.fromOutputStream(…)`` due to discoverability issues.

Update procedure
----------------

Replace ``InputStreamSource.create(`` with ``StreamConverters.fromInputStream(``

Replace ``OutputStreamSink.create(`` with ``StreamConverters.fromOutputStream(``

Example
^^^^^^^

::

      // This no longer works!
      final Source<ByteString, Future<java.lang.Long>> inputStreamSrc =
        InputStreamSource.create(new Creator<InputStream>(){
          public InputStream create() {
            return new SomeInputStream();
          }
        });

      // This no longer works!
      final Source<ByteString, Future<java.lang.Long>> otherInputStreamSrc =
        InputStreamSource.create(new Creator<InputStream>(){
          public InputStream create() {
            return new SomeInputStream();
          }
        }, 1024);

      // This no longer works!
      final Sink<ByteString, Future<java.lang.Long>> outputStreamSink =
        OutputStreamSink.create(new Creator<OutputStream>(){
          public OutputStream create() {
            return new SomeOutputStream();
          }
        })

should be replaced by

.. includecode:: code/docs/MigrationsJava.java#input-output-stream-source-sink


OutputStreamSource and InputStreamSink
======================================

Both have been replaced by ``StreamConverters.asOutputStream(…)`` and ``StreamConverters.asInputStream(…)`` due to discoverability issues.

Update procedure
----------------

Replace ``OutputStreamSource.create(`` with ``StreamConverters.asOutputStream(``

Replace ``InputStreamSink.create(`` with ``StreamConverters.asInputStream(``

Example
^^^^^^^

::

      // This no longer works!
      final Source<ByteString, OutputStream> outputStreamSrc =
        OutputStreamSource.create();

      // This no longer works!
      final Source<ByteString, OutputStream> otherOutputStreamSrc =
        OutputStreamSource.create(timeout);

      // This no longer works!
      final Sink<ByteString, InputStream> someInputStreamSink =
        InputStreamSink.create();

      // This no longer works!
      final Sink<ByteString, InputStream> someOtherInputStreamSink =
        InputStreamSink.create(timeout);

should be replaced by

.. includecode:: code/docs/MigrationsJava.java#output-input-stream-source-sink
