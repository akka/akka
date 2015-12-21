.. _migration-2.0-scala:

############################
 Migration Guide 1.0 to 2.x
############################

The 2.0 release contains some structural changes that require some
simple, mechanical source-level changes in client code. While these are detailed below,
there is another change that may have an impact on the runtime behavior of your streams
and which therefore is listed first.

Operator Fusion is on by default
================================

Akka Streams 2.0 contains an initial version of stream operator fusion support. This means that
the processing steps of a flow or stream graph can be executed within the same Actor and has three
consequences:

  * starting up a stream may take longer than before due to executing the fusion algorithm
  * passing elements from one processing stage to the next is a lot faster between fused
    stages due to avoiding the asynchronous messaging overhead
  * fused stream processing stages do no longer run in parallel to each other, meaning that
    only up to one CPU core is used for each fused part

The first point can be countered by pre-fusing and then reusing a stream blueprint, see ``akka.stream.Fusing``.
In order to balance the effects of the second and third bullet points you will have to insert asynchronous
boundaries manually into your flows and graphs by way of adding ``Attributes.asyncBoundary`` to pieces that
shall communicate with the rest of the graph in an asynchronous fashion.

.. warning::

  Without fusing (i.e. up to version 2.0-M2) each stream processing stage had an implicit input buffer
  that holds a few elements for efficiency reasons. If your flow graphs contain cycles then these buffers
  may have been crucial in order to avoid deadlocks. With fusing these implicit buffers are no longer
  there, data elements are passed without buffering between fused stages. In those cases where buffering
  is needed in order to allow the stream to run at all, you will have to insert explicit buffers with the
  ``.buffer()`` combinator—typically a buffer of size 2 is enough to allow a feedback loop to function.

The new fusing behavior can be disabled by setting the configuration parameter ``akka.stream.materializer.auto-fusing=off``.
In that case you can still manually fuse those graphs which shall run on less Actors. Fusable elements are

  * all GraphStages (this includes all built-in junctions apart from ``groupBy``)
  * all Stages (this includes all built-in linear operators)
  * TCP connections

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

It was possible to create a ``BidiFlow`` from two functions using ``apply()`` (Scala DSL) or ``create()`` (Java DSL).
Now this functionality can be accessed trough the more descriptive method ``BidiFlow.fromFunctions``.

Update procedure
----------------

1. Replace all uses of ``Flow.wrap`` when it converts a ``Graph`` to a ``Flow`` with ``Flow.fromGraph``
2. Replace all uses of ``Flow.wrap`` when it converts a ``Source`` and ``Sink`` to a ``Flow`` with
   ``Flow.fromSinkAndSource`` or ``Flow.fromSinkAndSourceMat``
3. Replace all uses of ``BidiFlow.wrap`` when it converts a ``Graph`` to a ``BidiFlow`` with ``BidiFlow.fromGraph``
4. Replace all uses of ``BidiFlow.wrap`` when it converts two ``Flow`` s to a ``BidiFlow`` with
   ``BidiFlow.fromFlows`` or ``BidiFlow.fromFlowsMat``
5. Replace all uses of ``BidiFlow.apply()`` when it converts two
   functions to a ``BidiFlow`` with ``BidiFlow.fromFunctions``

Example
^^^^^^^

::

      val graphSource: Graph[SourceShape[Int], Unit] = ???
      // This no longer works!
      val source: Source[Int, Unit] = Source.wrap(graphSource)

      val graphSink: Graph[SinkShape[Int], Unit] = ???
      // This no longer works!
      val sink: Sink[Int, Unit] = Sink.wrap(graphSink)

      val graphFlow: Graph[FlowShape[Int, Int], Unit] = ???
      // This no longer works!
      val flow: Flow[Int, Int, Unit] = Flow.wrap(graphFlow)

      // This no longer works
      Flow.wrap(Sink.head[Int], Source.single(0))(Keep.left)

should be replaced by

.. includecode:: code/docs/MigrationsScala.scala#flow-wrap

and

::

      val bidiGraph: Graph[BidiShape[Int, Int, Int, Int], Unit = ???
      // This no longer works!
      val bidi: BidiFlow[Int, Int, Int, Int, Unit] = BidiFlow.wrap(bidiGraph)

      // This no longer works!
      BidiFlow.wrap(flow1, flow2)(Keep.both)

      // This no longer works!
      BidiFlow((x: Int) => x + 1, (y: Int) => y * 3)


Should be replaced by

.. includecode:: code/docs/MigrationsScala.scala#bidiflow-wrap

FlowGraph class and builder methods have been renamed
===========================================

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
      FlowGraph.closed() { builder =>
        //...
      }

      // This no longer works!
      FlowGraph.partial() { builder =>
        //...
        FlowShape(inlet, outlet)
      }

should be replaced by

.. includecode:: code/docs/MigrationsScala.scala#graph-create

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
      Source() { builder =>
        //...
        outlet
      }

      // This no longer works!
      Sink() { builder =>
        //...
        inlet
      }

      // This no longer works!
      Flow() { builder =>
        //...
        (inlet, outlet)
      }

      // This no longer works!
      BidiFlow() { builder =>
        //...
        BidiShape(inlet1, outlet1, inlet2, outlet2)
      }

should be replaced by

.. includecode:: code/docs/MigrationsScala.scala#graph-create-2

Several Graph builder methods have been removed
===============================================

The ``addEdge`` methods have been removed from the DSL to reduce the ways connections can be made and to reduce the
number of overloads. Now only the ``~>`` notation is available which requires the import of the implicits
``GraphDSL.Implicits._``.

Update procedure
----------------

1. Replace all uses of ``scaladsl.Builder.addEdge(Outlet, Inlet)`` by the graphical DSL ``~>``.
2. Replace all uses of ``scaladsl.Builder.addEdge(Outlet, FlowShape, Inlet)`` by the graphical DSL ``~>``.
   methods, or the graphical DSL ``~>``.
3. Import ``FlowGraph.Implicits._`` in the builder block or an enclosing scope.

Example
^^^^^^^

::

      FlowGraph.closed() { builder =>
        //...
        // This no longer works!
        builder.addEdge(outlet, inlet)
        // This no longer works!
        builder.addEdge(outlet, flow1, inlet)
        //...
      }

should be replaced by

.. includecode:: code/docs/MigrationsScala.scala#graph-edges

Source constructor name changes
===============================

``Source.lazyEmpty`` has been replaced by ``Source.maybe`` which returns a ``Promise`` that can be completed by one or
zero elements by providing an ``Option``. This is different from ``lazyEmpty`` which only allowed completion to be
sent, but no elements.

The ``apply()`` overload on ``Source`` has been refactored to separate methods to reduce the number of overloads and
make source creation more discoverable.

``Source.subscriber`` has been renamed to ``Source.asSubscriber``.

Update procedure
----------------

1. All uses of ``Source.lazyEmpty`` should be replaced by ``Source.maybe`` and the returned ``Promise`` completed with
   a ``None`` (an empty ``Option``)
2. Replace all uses of ``Source(delay,interval,tick)`` with the method ``Source.tick(delay,interval,tick)``
3. Replace all uses of ``Source(publisher)`` with the method ``Source.fromPublisher(publisher)``
4. Replace all uses of ``Source(() => iterator)`` with the method ``Source.fromIterator(() => iterator))``
5. Replace all uses of ``Source(future)`` with the method ``Source.fromFuture(future))``
6. Replace all uses of ``Source.subscriber`` with the method ``Source.asSubscriber``

Example
^^^^^^^

::

      // This no longer works!
      val src: Source[Int, Promise[Unit]] = Source.lazyEmpty[Int]
      //...
      promise.trySuccess(())

      // This no longer works!
      val ticks = Source(1.second, 3.seconds, "tick")

      // This no longer works!
      val pubSource = Source(TestPublisher.manualProbe[Int]())

      // This no longer works!
      val itSource = Source(() => Iterator.continually(Random.nextGaussian))

      // This no longer works!
      val futSource = Source(Future.successful(42))

      // This no longer works!
      val subSource = Source.subscriber

should be replaced by

.. includecode:: code/docs/MigrationsScala.scala#source-creators

Sink constructor name changes
=============================

``Sink.apply(subscriber)`` has been renamed to ``Sink.fromSubscriber(subscriber)`` to reduce the number of overloads and
make sink creation more discoverable.

Update procedure
----------------

1. Replace all uses of ``Sink(subscriber)`` with the method ``Sink.fromSubscriber(subscriber)``

Example
^^^^^^^

::

      // This no longer works!
      val subSink = Sink(TestSubscriber.manualProbe[Int]())

should be replaced by

.. includecode:: code/docs/MigrationsScala.scala#sink-creators

``flatten(FlattenStrategy)`` has been replaced by named counterparts
====================================================================

To simplify type inference in Java 8 and to make the method more discoverable, ``flatten(FlattenStrategy.concat)``
has been removed and replaced with the alternative method ``flatten(FlattenStrategy.concat)``.

Update procedure
----------------

1. Replace all occurrences of ``flatten(FlattenStrategy.concat)`` with ``flatMapConcat(identity)``
2. Consider replacing all occurrences of ``map(f).flatMapConcat(identity)`` with ``flatMapConcat(f)``

Example
^^^^^^^

::

   // This no longer works!
   Flow[Source[Int, Any]].flatten(FlattenStrategy.concat)

should be replaced by

.. includecode:: code/docs/MigrationsScala.scala#flatMapConcat

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
   val subSink = Sink.publisher

   // This no longer works!
   val subSink = Sink.fanoutPublisher(2, 8)

should be replaced by

.. includecode:: code/docs/MigrationsScala.scala#sink-as-publisher

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

GroupBy, SplitWhen and SplitAfter now return SubFlow
====================================================

Previously the ``groupBy``, ``splitWhen``, and ``splitAfter`` combinators
returned a type that included a :class:`Source` within its elements.
Transforming these substreams was only possible by nesting the respective
combinators inside a ``map`` of the outer stream. This has been made more
convenient and also safer by dropping down into transforming the substreams
instead: the return type is now a :class:`SubFlow` that does not implement the
:class:`Graph` interface and therefore only represents an unfinished
intermediate builder step. The substream mode can be ended by closing the
substreams (i.e. attaching a :class:`Sink`) or merging them back together.

Update Procedure
----------------

The transformations that were done on the substreams need to be lifted up one
level. This only works for cases where the processing topology is homogenous
for all substreams.

Example
^^^^^^^

::

  Flow[Int]
    // This no longer works!
    .groupBy(_ % 2)
    // This no longer works!
    .map {
      case (key, source) => source.map(_ + 3)
    }
    // This no longer works!
    .flatten(FlattenStrategy.concat)

This is implemented now as

.. includecode:: code/docs/MigrationsScala.scala#group-flatten

Example 2
^^^^^^^^^

::

  Flow[String]
    // This no longer works!
    .groupBy(identity)
    // This no longer works!
    .map {
      case (key, source) => source.runFold((key, 0))((pair, word) => (key, pair._2 + 1))
    }
    // This no longer works!
    .mapAsyncUnordered(4, identity)

This is implemented now as

.. includecode:: code/docs/MigrationsScala.scala#group-fold

Variance of Inlet and Outlet
============================

Scala uses *declaration site variance* which was cumbersome in the cases of ``Inlet`` and ``Outlet`` as they are
purely symbolic object containing no fields or methods and which are used both in input and output locations (wiring
an ``Outlet`` into an ``Inlet``; reading in a stage from an ``Inlet``). Because of this reasons all users of these
port abstractions now use *use-site variance* (just like Java variance works). This in general does not affect user
code expect the case of custom shapes, which now require ``@uncheckedVariance`` annotations on their ``Inlet`` and
``Outlet`` members (since these are now invariant, but the Scala compiler does not know that they have no fields or
methods that would violate variance constraints)

This change does not affect Java DSL users.

Update procedure
----------------

1. All custom shapes must use ``@uncheckedVariance`` on their ``Inlet`` and ``Outlet`` members.

Renamed ``inlet()`` and ``outlet()`` to ``in()`` and ``out()`` in ``SourceShape``, ``SinkShape`` and ``FlowShape``
==========================================================================================================

The input and output ports of these shapes where called ``inlet()`` and ``outlet()`` compared to other shapes that
consistently used ``in()`` and ``out()``. Now all :class:`Shape` s use ``in()`` and ``out()``.

Update procedure
----------------

Change all references to ``inlet()`` to ``in()`` and all references to ``outlet()`` to ``out()`` when referring to the ports
of :class:`FlowShape`, :class:`SourceShape` and :class:`SinkShape`.

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

::

      class MapAsyncOne[In, Out](f: In ⇒ Future[Out])(implicit ec: ExecutionContext)
        extends AsyncStage[In, Out, Try[Out]] {

        private var elemInFlight: Out = _

        override def onPush(elem: In, ctx: AsyncContext[Out, Try[Out]]) = {
          val future = f(elem)
          val cb = ctx.getAsyncCallback
          future.onComplete(cb.invoke)
          ctx.holdUpstream()
        }

        override def onPull(ctx: AsyncContext[Out, Try[Out]]) =
          if (elemInFlight != null) {
            val e = elemInFlight
            elemInFlight = null.asInstanceOf[Out]
            pushIt(e, ctx)
          } else ctx.holdDownstream()

        override def onAsyncInput(input: Try[Out], ctx: AsyncContext[Out, Try[Out]]) =
          input match {
            case Failure(ex)                           ⇒ ctx.fail(ex)
            case Success(e) if ctx.isHoldingDownstream ⇒ pushIt(e, ctx)
            case Success(e) ⇒
              elemInFlight = e
              ctx.ignore()
          }

        override def onUpstreamFinish(ctx: AsyncContext[Out, Try[Out]]) =
          if (ctx.isHoldingUpstream) ctx.absorbTermination()
          else ctx.finish()

        private def pushIt(elem: Out, ctx: AsyncContext[Out, Try[Out]]) =
          if (ctx.isFinishing) ctx.pushAndFinish(elem)
          else ctx.pushAndPull(elem)
      }

should be replaced by

.. includecode:: code/docs/MigrationsScala.scala#port-async

Akka HTTP: Uri parsing mode relaxed-with-raw-query replaced with rawQueryString
===============================================================================

Previously Akka HTTP allowed to configure the parsing mode of an Uri's Query part (``?a=b&c=d``) to ``relaxed-with-raw-query``
which is useful when Uris are not formatted using the usual "key/value pairs" syntax.

Instead of exposing it as an option for the parser, this is now available as the ``rawQueryString(): Option[String]``
/ ``queryString(): Option[String]`` methods on on ``model.Uri``.


For parsing the Query part use ``query(charset: Charset = UTF8, mode: Uri.ParsingMode = Uri.ParsingMode.Relaxed): Query``.

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

.. includecode:: code/docs/MigrationsScala.scala#raw-query

And use of query parameters from ``Uri`` that looked like this:

::

  // This no longer works!
  uri.parameter("name")

should be replaced by:

.. includecode:: code/docs/MigrationsScala.scala#query-param

SynchronousFileSource and SynchronousFileSink
=============================================


``SynchronousFileSource`` and ``SynchronousFileSink``
have been replaced by ``FileIO.read(…)`` and ``FileIO.write(…)`` due to discoverability issues
paired with names which leaked internal implementation details.

Update procedure
----------------

Replace ``SynchronousFileSource(`` and ``SynchronousFileSource.apply(`` with ``FileIO.fromFile(``

Replace ``SynchronousFileSink(`` and ``SynchronousFileSink.apply(`` with ``FileIO.toFile(``

Example
^^^^^^^

::

      // This no longer works!
      val fileSrc = SynchronousFileSource(new File("."))

      // This no longer works!
      val otherFileSrc = SynchronousFileSource(new File("."), 1024)

      // This no longer works!
      val someFileSink = SynchronousFileSink(new File("."))


should be replaced by

.. includecode:: code/docs/MigrationsScala.scala#file-source-sink

InputStreamSource and OutputStreamSink
======================================

Both have been replaced by ``StreamConverters.fromInputStream(…)`` and ``StreamConverters.fromOutputStream(…)`` due to discoverability issues.

Update procedure
----------------

Replace ``InputStreamSource(`` and ``InputStreamSource.apply(`` with ``StreamConverters.fromInputStream(``
i
Replace ``OutputStreamSink(`` and ``OutputStreamSink.apply(`` with ``StreamConverters.fromOutputStream(``

Example
^^^^^^^

::

      // This no longer works!
      val inputStreamSrc = InputStreamSource(() => new SomeInputStream())

      // This no longer works!
      val otherInputStreamSrc = InputStreamSource(() => new SomeInputStream(), 1024)

      // This no longer works!
      val someOutputStreamSink = OutputStreamSink(() => new SomeOutputStream())

should be replaced by

.. includecode:: code/docs/MigrationsScala.scala#input-output-stream-source-sink

OutputStreamSource and InputStreamSink
======================================

Both have been replaced by ``StreamConverters.asOutputStream(…)`` and ``StreamConverters.asInputStream(…)`` due to discoverability issues.

Update procedure
----------------

Replace ``OutputStreamSource(`` and ``OutputStreamSource.apply(`` with ``StreamConverters.asOutputStream(``

Replace ``InputStreamSink(`` and ``InputStreamSink.apply(`` with ``StreamConverters.asInputStream(``

Example
^^^^^^^

::

      // This no longer works!
      val outputStreamSrc = OutputStreamSource()

      // This no longer works!
      val otherOutputStreamSrc = OutputStreamSource(timeout)

      // This no longer works!
      val someInputStreamSink = InputStreamSink()

      // This no longer works!
      val someOtherInputStreamSink = InputStreamSink(timeout);

should be replaced by

.. includecode:: code/docs/MigrationsScala.scala#output-input-stream-source-sink
