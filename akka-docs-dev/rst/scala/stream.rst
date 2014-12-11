.. _stream-scala:

#######
Streams
#######

How to read these docs
======================

**TODO**

Add section: "How to read these docs" (or something similar)
It should be roughly:

* read the quickstart to get a feel
* (optional) read the design statement
* (optional) look at the cookbook probably in parallel while reading the main docs as supplementary material
* the other sections can be read sequentially, each digging deeper into advanced topics

**TODO - write me**

.. toctree::
   :maxdepth: 1

   stream-integration-external
   stream-integration-reactive-streams

Motivation
==========

**TODO - write me**

Core concepts
=============

Everything in Akka Streams revolves around a number of core concepts which we introduce in detail in this section.

Akka Streams provide a way for executing bounded processing pipelines, where bounds are expressed as the number of stream
elements in flight and in buffers at any given time. Please note that while this allows to estimate an limit memory use
it is not strictly bound to the size in memory of these elements.

We define a few key words which will be used though out the entire documentation:

Stream
  An active process that involves moving and transforming data.
Element
  An element is the unit which is passed through the stream. All operations as well as back-pressure are expressed in
  terms of elements.
Back-pressure
  A means of flow-control, and most notably adjusting the speed of upstream sources to the consumption speeds of their sinks.
  In the context of Akka Streams back-pressure is always understood as *non-blocking* and *asynchronous*
Processing Stage
  The common name for all building blocks that build up a Flow or FlowGraph.
  Examples of a processing stage would be Stage (:class:`PushStage`, :class:`PushPullStage`, :class:`StatefulStage`,
  :class:`DetachedStage`), in terms of which operations like ``map()``, ``filter()`` and others are implemented.

Sources, Flows and Sinks
------------------------
Linear processing pipelines can be expressed in Akka Streams using the following three core abstractions:

Source
  A processing stage with *exactly one output*, emitting data elements in response to it's down-stream demand.
Sink
  A processing stage with *exactly one input*, generating demand based on it's internal demand management strategy.
Flow
  A processing stage which has *exactly one input and output*, which connects it's up and downstreams by (usually)
  transforming the data elements flowing through it.
RunnableFlow
  A Flow with has both ends "attached" to a Source and Sink respectively, and is ready to be ``run()``.

It is important to remember that while constructing these processing pipelines by connecting their different processing
stages no data will flow through it until it is materialized. Materialization is the process of allocating all resources
needed to run the computation described by a Flow (in Akka Streams this will often involve starting up Actors).
Thanks to Flows being simply a description of the processing pipeline they are *immutable, thread-safe, and freely shareable*,
which means that it is for example safe to share send between actors–to have one actor prepare the work, and then have it
be materialized at some completely different place in the code.

In order to be able to run a ``Flow[In,Out]`` it must be connected to a ``Sink[In]`` *and* ``Source[Out]`` of matching types.
It is also possible to directly connect a :class:`Sink` to a :class:`Source`.

.. includecode:: code/docs/stream/FlowDocSpec.scala#materialization-in-steps

The :class:`MaterializedMap` can be used to get materialized values of both sinks and sources out from the running
stream. In general, a stream can expose multiple materialized values, however the very common case of only wanting to
get back a Sinks (in order to read a result) or Sources (in order to cancel or influence it in some way) materialized
values has a small convenience method called ``runWith()``. It is available for ``Sink`` or ``Source`` and ``Flow``, with respectively,
requiring the user to supply a ``Source`` (in order to run a ``Sink``), a ``Sink`` (in order to run a ``Source``) and
both a ``Source`` and a ``Sink`` (in order to run a ``Flow``, since it has neither attached yet).

.. includecode:: code/docs/stream/FlowDocSpec.scala#materialization-runWith

It is worth pointing out that since processing stages are *immutable*, connecting them returns a new processing stage,
instead of modifying the existing instance, so while construction long flows, remember to assign the new value to a variable or run it:

.. includecode:: code/docs/stream/FlowDocSpec.scala#source-immutable

.. note::
  By default Akka Streams elements support **exactly one** downstream processing stage.
  Making fan-out (supporting multiple downstream processing stages) an explicit opt-in feature allows default stream elements to
  be less complex and more efficient. Also it allows for greater flexibility on *how exactly* to handle the multicast scenarios,
  by providing named fan-out elements such as broadcast (signals all down-stream elements) or balance (signals one of available down-stream elements).

In the above example we used the ``runWith`` method, which both materializes the stream and returns the materialized value
of the given sink or source.

.. _back-pressure-explained-scala:

Back-pressure explained
-----------------------
Back-pressure in Akka Streams is always enabled and all stream processing stages adhere the same back-pressure protocol.
While these back-pressure signals are in fact explicit in terms of protocol, they are hidden from users of the library,
such that in normal usage one does *not* have to explicitly think about handling back-pressure, unless working with rate detached stages.

Back-pressure is defined in terms of element count which referred to as ``demand``.

Akka Streams implement the Reactive Streams back-pressure protocol, which can be described as a dynamic push/pull model.

In depth
========
// TODO: working with flows
// TODO: creating an empty flow
// TODO: materialization Flow -> RunnableFlow

// TODO: flattening, prefer static fanin/out, deadlocks

.. _stream-buffering-explained-scala:
Stream buffering explained
--------------------------
**TODO - write me (feel free to move around as well)**

Streams of Streams
------------------
**TODO - write me (feel free to move around as well)**

groupBy
^^^^^^^
**TODO - write me (feel free to move around as well)**
// TODO: deserves its own section? and explain the dangers? (dangling sub-stream problem, subscription timeouts)

// TODO: Talk about ``flatten`` and ``FlattenStrategy``


.. _stream-materialization-scala:
Stream Materialization
----------------------
**TODO - write me (feel free to move around as well)**

When constructing flows and graphs in Akka Streams think of them as preparing a blueprint, an execution plan.
Stream materialization is the process of taking a stream description (the graph) and allocating all the necessary resources
it needs in order to run. In the case of Akka Streams this often means starting up Actors which power the processing,
but is not restricted to that - it could also mean opening files or socket connections etc. – depending on what the stream needs.

Materialization is triggered at so called "terminal operations". Most notably this includes the various forms of the ``run()``
and ``runWith()`` methods defined on flow elements as well as a small number of special syntactic sugars for running with
well-known sinks, such as ``foreach(el => )`` (being an alias to ``runWith(Sink.foreach(el => ))``.

Materialization is currently performed synchronously on the materializing thread.
Tha actual stream processing is handled by :ref:`Actors actor-scala` started up during the streams materialization,
which will be running on the thread pools they have been configured to run on - which defaults to the dispatcher set in
:class:`MaterializationSettings` while constructing the :class:`FlowMaterializer`.

.. note::
  Reusing *instances* of linear computation stages (Source, Sink, Flow) inside FlowGraphs is legal,
  yet will materialize that stage multiple times.


MaterializedMap
^^^^^^^^^^^^^^^
**TODO - write me (feel free to move around as well)**

Optimizations
^^^^^^^^^^^^^
// TODO: not really to be covered right now, right?

Subscription timeouts
---------------------
// TODO: esp in groupBy etc, if you dont subscribe to a stream son enough it may be dead once you get to it


.. _stream-section-configuration:

Section configuration
---------------------
// TODO: it is possible to configure sections of a graph

.. _working-with-graphs-scala:
Working with Graphs
===================
Akka Streams are unique in the way they handle and expose computation graphs - instead of hiding the fact that the
processing pipeline is in fact a graph in a purely "fluent" DSL, graph operations are written in a DSL that graphically
resembles and embraces the fact that the built pipeline is in fact a Graph. In this section we'll dive into the multiple
ways of constructing and re-using graphs, as well as explain common pitfalls and how to avoid them.

Graphs are needed whenever you want to perform any kind of fan-in ("multiple inputs") or fan-out ("multiple outputs") operations.
Considering linear Flows to be like roads, we can picture graph operations as junctions: multiple flows being connected at a single point.
Some graph operations which are common enough and fit the linear style of Flows, such as ``concat`` (which concatenates two
streams, such that the second one is consumed after the first one has completed), may have shorthand methods defined on
:class:`Flow` or :class:`Source` themselves, however you should keep in mind that those are also implemented as graph junctions.

.. _flow-graph-scala:

Constructing Flow Graphs
------------------------
Flow graphs are built from simple Flows which serve as the linear connections within the graphs as well as Junctions
which serve as fan-in and fan-out points for flows. Thanks to the junctions having meaningful types based on their behaviour
and making them explicit elements these elements should be rather straight forward to use.

Akka Streams currently provides these junctions:

* **Fan-out**
 - ``Broadcast[T]`` – (1 input, n outputs) signals each output given an input signal,
 - ``Balance[T]`` – (1 input => n outputs), signals one of its output ports given an input signal,
 - ``UnZip[A,B]`` – (1 input => 2 outputs), which is a specialized element which is able to split a stream of ``(A,B)`` into two streams one type ``A`` and one of type ``B``,
 - ``FlexiRoute[In]`` – (1 input, n outputs), which enables writing custom fan out elements using a simple DSL,
* **Fan-in**
 - ``Merge[In]`` – (n inputs , 1 output), picks signals randomly from inputs pushing them one by one to its output,
 - ``MergePreferred[In]`` – like :class:`Merge` but if elements are available on ``preferred`` port, it picks from it, otherwise randomly from ``others``,
 - ``ZipWith[A,B,...,Out]`` – (n inputs (defined upfront), 1 output), which takes a function of n inputs that, given all inputs are signalled, transforms and emits 1 output,
  + ``Zip[A,B,Out]`` – (2 inputs, 1 output), which is a :class:`ZipWith` specialised to zipping input streams of ``A`` and ``B`` into an ``(A,B)`` stream,
 - ``Concat[T]`` – (2 inputs, 1 output), which enables to concatenate streams (first consume one, then the second one), thus the order of which stream is ``first`` and which ``second`` matters,
 - ``FlexiMerge[Out]`` – (n inputs, 1 output), which enables writing custom fan out elements using a simple DSL.

One of the goals of the FlowGraph DSL is to look similar to how one would draw a graph on a whiteboard, so that it is
simple to translate a design from whiteboard to code and be able to relate those two. Let's illustrate this by translating
the below hand drawn graph into Akka Streams:

.. image:: ../images/simple-graph-example.png

Such graph is simple to translate to the Graph DSL since each linear element corresponds to a :class:`Flow`,
and each circle corresponds to either a :class:`Junction` or a :class:`Source` or :class:`Sink` if it is beginning
or ending a :class:`Flow`. Junctions must always be created with defined type parameters, as otherwise the ``Nothing`` type
will be inferred and

.. includecode:: code/docs/stream/FlowGraphDocSpec.scala#simple-flow-graph

.. note::
  Junction *reference equality* defines *graph node equality* (i.e. the same merge *instance* used in a FlowGraph
  refers to the same location in the resulting graph).

Notice the ``import FlowGraphImplicits._`` which brings into scope the ``~>`` operator (read as "edge", "via" or "to").
It is also possible to construct graphs without the ``~>`` operator in case you prefer to use the graph builder explicitly:

.. includecode:: code/docs/stream/FlowGraphDocSpec.scala#simple-flow-graph-no-implicits

By looking at the snippets above, it should be apparent that **the** :class:`b:FlowGraphBuilder` **object is mutable**.
It is also used (implicitly) by the ``~>`` operator, also making it a mutable operation as well.
The reason for this design choice is to enable simpler creation of complex graphs, which may even contain cycles.
Once the FlowGraph has been constructed though, the :class:`FlowGraph` instance *is immutable, thread-safe, and freely shareable*.
Linear Flows however are always immutable and appending an operation to a Flow always returns a new Flow instance.
This means that you can safely re-use one given Flow in multiple places in a processing graph. In the example below
we prepare a graph that consists of two parallel streams, in which we re use the same instance of :class:`Flow`,
yet it will properly be materialized as two connections between the corresponding Sources and Sinks:

.. includecode:: code/docs/stream/FlowGraphDocSpec.scala#flow-graph-reusing-a-flow

.. _partial-flow-graph-scala:

Constructing and combining Partial Flow Graphs
----------------------------------------------
Sometimes it is not possible (or needed) to construct the entire computation graph in one place, but instead construct
all of its different phases in different places and in the end connect them all into a complete graph and run it.

This can be achieved using :class:`PartialFlowGraph`. The reason of representing it as a different type is that a
:class:`FlowGraph` requires all ports to be connected, and if they are not it will throw an exception at construction
time, which helps to avoid simple wiring errors while working with graphs. A partial flow graph however does not perform
this validation, and allows graphs that are not yet fully connected.

A :class:`PartialFlowGraph` is defined as a :class:`FlowGraph` which contains so called "undefined elements",
such as ``UndefinedSink[T]`` or ``UndefinedSource[T]``, which can be reused and plugged into by consumers of that
partial flow graph. Let's imagine we want to provide users with a specialized element that given 3 inputs will pick
the greatest int value of each zipped triple. We'll want to expose 3 input ports (undefined sources) and one output port
(undefined sink).

.. includecode:: code/docs/stream/StreamPartialFlowGraphDocSpec.scala#simple-partial-flow-graph

As you can see, first we construct the partial graph that contains all the zipping and comparing of stream
elements, then we import it (all of its nodes and connections) explicitly to the :class:`FlowGraph` instance in which all
the undefined elements are rewired to real sources and sinks. The graph can then be run and yields the expected result.

.. warning::
  Please note that a :class:`FlowGraph` is not able to provide compile time type-safety about whether or not all
  elements have been properly connected - this validation is performed as a runtime check during the graph's instantiation.

.. _constructing-sources-sinks-flows-from-partial-graphs-scala:

Constructing Sources, Sinks and Flows from a Partial Graphs
-----------------------------------------------------------
Instead of treating a :class:`PartialFlowGraph` as simply a collection of flows and junctions which may not yet all be
connected it is sometimes useful to expose such complex graph as a simpler structure,
such as a :class:`Source`, :class:`Sink` or :class:`Flow`.

In fact, these concepts can be easily expressed as special cases of a partially connected graph:

* :class:`Source` is a partial flow graph with *exactly one* :class:`UndefinedSink`,
* :class:`Sink` is a partial flow graph with *exactly one* :class:`UndefinedSource`,
* :class:`Flow` is a partial flow graph with *exactly one* :class:`UndefinedSource` and *exactly one* :class:`UndefinedSource`.

Being able hide complex graphs inside of simple elements such as Sink / Source / Flow enables you to easily create one
complex element and from there on treat it as simple compound stage for linear computations.

In order to create a Source from a partial flow graph ``Source[T]`` provides a special apply method that takes a function
that must return an ``UndefinedSink[T]``. This undefined sink will become "the sink that must be attached before this Source
can run". Refer to the example below, in which we create a Source that zips together two numbers, to see this graph
construction in action:

.. includecode:: code/docs/stream/StreamPartialFlowGraphDocSpec.scala#source-from-partial-flow-graph

Similarly the same can be done for a ``Sink[T]``, in which case the returned value must be an ``UndefinedSource[T]``.
For defining a ``Flow[T]`` we need to expose both an undefined source and sink:

.. includecode:: code/docs/stream/StreamPartialFlowGraphDocSpec.scala#flow-from-partial-flow-graph

Stream ordering
===============
In Akka Streams almost all computation stages *preserve input order* of elements, this means that each output element
``O`` is the result of some sequence of incoming ``I1,I2,I3`` elements. This property is even adhered by async operations
such as ``mapAsync``, however an unordered version exists called ``mapAsyncUnordered`` which does not preserve this ordering.

However, in the case of Junctions which handle multiple input streams (e.g. :class:`Merge`) the output order is **not defined**,
as different junctions may choose to implement consuming their upstreams in a multitude of ways, each being valid under
certain circumstances. If you find yourself in need of fine grained control over order of emitted elements in fan-in
scenarios consider using :class:`MergePreferred` or :class:`FlexiMerge` - which gives you full control over how the
merge is performed. One notable exception from that rule is :class:`Zip` as is only ever emits an element once all of
its upstreams have one available, thus no reordering can occur.

Streaming IO
============

// TODO: TCP here I guess

// TODO: Files if we get any, but not this week

Custom elements
===============
**TODO - write me (feel free to move around as well)**
// TODO: So far we've been mostly using predefined elements, but sometimes that's not enough


.. _flexi-merge:
Flexi Merge
-----------

// TODO: "May sometimes be exactly what you need..."

.. _flexi-route:
Flexi Route
-----------
**TODO - write me (feel free to move around as well)**

Integrating with Actors
=======================

// TODO: Source.subscriber

// TODO: Sink.publisher

// TODO: Use the ImplicitFlowMaterializer if you have streams starting from inside actors.

// TODO: how do I create my own sources / sinks?

ActorPublisher
^^^^^^^^^^^^^^
ActorSubscriber
^^^^^^^^^^^^^^^

// TODO: Implementing Reactive Streams interfaces directly vs. extending ActorPublisher / ActoSubscriber???

