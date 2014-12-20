.. _stream-scala:

#######
Streams
#######

How to read these docs
======================

Stream processing is a different paradigm to the Actor Model or to Future
composition, therefore it may take some careful study of this subject until you
feel familiar with the tools and techniques. The documentation is here to help
and for best results we recommend the following approach:

* Read the :ref:`quickstart Quick Start Guide` to get a feel for how streams
  look like and what they can do.
* The top-down learners may want to peruse the :ref:`stream-design` at this
  point.
* The bottom-up learners may feel more at home rummaging through the
  :ref:`stream-scala-cookbook`.
* The other sections can be read sequentially or as needed during the previous
  steps, each digging deeper into specific topics.

.. toctree::
   :maxdepth: 1

   stream-integration-external
   stream-integration-reactive-streams

Motivation
==========

The way we consume services from the internet today includes many instances of
streaming data, both downloading from a service as well as uploading to it or
peer-to-peer data transfers. Regarding data as a stream of elements instead of
in its entirety is very useful because it matches the way computers send and
receive them (for example via TCP), but it is often also a necessity because
data sets frequently become too large to be handled as a whole. We spread
computations or analyses over large clusters and call it “big data”, where the
whole principle of processing them is by feeding those data sequentially—as a
stream—through some CPUs.

Actors can be seen as dealing with streams as well: they send and receive
series of messages in order to transfer knowledge (or data) from one place to
another. We have found it tedious and error-prone to implement all the proper
measures in order to achieve stable streaming between actors, since in addition
to sending and receiving we also need to take care to not overflow any buffers
or mailboxes in the process. Another pitfall is that Actor messages can be lost
and must be retransmitted in that case lest the stream have holes on the
receiving side. When dealing with streams of elements of a fixed given type,
Actors also do not currently offer good static guarantees that no wiring errors
are made: type-safety could be improved in this case.

For these reasons we decided to bundle up a solution to these problems as an
Akka Streams API. The purpose is to offer an intuitive and safe way to
formulate stream processing setups such that we can then execute them
efficiently and with bounded resource usage—no more OutOfMemoryErrors. In order
to achieve this our streams need to be able to limit the buffering that they
employ, they need to be able to slow down producers if the consumers cannot
keep up. This feature is called back-pressure and is at the core of the
[Reactive Streams](http://reactive-streams.org/) initiative of which Akka is a
founding member. For you this means that the hard problem of propagating and
reacting to back-pressure has been incorporated in the design of Akka Streams
already, so you have one less thing to worry about; it also means that Akka
Streams interoperate seamlessly with all other Reactive Streams implementations
(where Reactive Streams interfaces define the interoperability SPI while
implementations like Akka Streams offer a nice user API).

.. _stream-scala-quickstart:

Core concepts
=============

Everything in Akka Streams revolves around a number of core concepts which we introduce in detail in this section.

Akka Streams provide a way for executing bounded processing pipelines, where bounds are expressed as the number of stream
elements in flight and in buffers at any given time. Please note that while this allows to estimate an limit memory use
it is not strictly bound to the size in memory of these elements.

First we define the terminology which will be used though out the entire documentation:

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
Akka Streams implements an asynchronous non-blocking back-pressure protocol standardised by the Reactive Streams
specification, which Akka is a founding member of.

As library user you do not have to write any explicit back-pressure handling code in order for it to work - it is built
and dealt with automatically by all of the provided Akka Streams processing stages. However is possible to include
explicit buffers with overflow strategies that can influence the behaviour of the stream. This is especially important
in complex processing graphs which may even sometimes even contain loops (which *must* be treated with very special
care, as explained in :ref:`cycles-scala`).

The back pressure protocol is defined in terms of the number of elements a downstream ``Subscriber`` is able to receive,
referred to as ``demand``. This demand is the *number of elements* receiver of the data, referred to as ``Subscriber``
in Reactive Streams, and implemented by ``Sink`` in Akka Streams is able to safely consume at this point in time.
The source of data referred to as ``Publisher`` in Reactive Streams terminology and implemented as ``Source`` in Akka
Streams guarantees that it will never emit more elements than the received total demand for any given ``Subscriber``.

.. note::
  The Reactive Streams specification defines its protocol in terms of **Publishers** and **Subscribers**.
  These types are *not* meant to be user facing API, instead they serve as the low level building blocks for
  different Reactive Streams implementations.

  Akka Streams implements these concepts as **Sources**, **Flows** (referred to as **Processor** in Reactive Streams)
  and **Sinks** without exposing the Reactive Streams interfaces directly.
  If you need to inter-op between different read :ref:`integration-with-Reactive-Streams-enabled-libraries`.

The mode in which Reactive Streams back-pressure works can be colloquially described as "dynamic push / pull mode",
since it will switch between push or pull based back-pressure models depending on if the downstream is able to cope
with the upstreams production rate or not.

To illustrate further let us consider both problem situations and how the back-pressure protocol handles them:

Slow Publisher, fast Subscriber
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
This is the happy case of course–we do not need to slow down the Publisher in this case. However signalling rates are
rarely constant and could change at any point in time, suddenly ending up in a situation where the Subscriber is now
slower than the Publisher. In order to safeguard from these situations, the back-pressure protocol must still be enabled
during such situations, however we do not want to pay a high penalty for this safety net being enabled.

The Reactive Streams protocol solves this by asynchronously signalling from the Subscriber to the Publisher
`Request(n:Int)` signals. The protocol guarantees that the Publisher will never signal *more* than the demand it was
signalled. Since the Subscriber however is currently faster, it will be signalling these Request messages at a higher
rate (and possibly also batching together the demand - requesting multiple elements in one Request signal). This means
that the Publisher should not ever have to wait (be back-pressured) with publishing its incoming elements.

As we can see, in this scenario we effectively operate in so called push-mode since the Publisher can continue producing
elements as fast as it can, since the pending demand will be recovered just-in-time while it is emitting elements.

Fast Publisher, slow Subscriber
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
This is the case when back-pressuring the ``Publisher`` is required, because the ``Subscriber`` is not able to cope with
the rate at which its upstream would like to emit data elements.

Since the ``Publisher`` is not allowed to signal more elements than the pending demand signalled by the ``Subscriber``,
it will have to abide to this back-pressure by applying one of the below strategies:

- not generate elements, if it is able to control their production rate,
- try buffering the elements in a *bounded* manner until more demand is signalled,
- drop elements until more demand is signalled,
- tear down the stream if unable to apply any of the above strategies.

As we can see, this scenario effectively means that the ``Subscriber`` will *pull* the elements from the Publisher–
this mode of operation is referred to as pull-based back-pressure.

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
In Akka Streams computation graphs are not expressed using a fluent DSL like linear computations are, instead they are
written in a more graph-resembling DSL which aims to make translating graph drawings (e.g. from notes taken
from design discussions, or illustrations in protocol specifications) to and from code simpler. In this section we'll
dive into the multiple ways of constructing and re-using graphs, as well as explain common pitfalls and how to avoid them.

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
 - ``UnZip[A,B]`` – (1 input => 2 outputs), which is a specialized element which is able to split a stream of ``(A,B)`` tuples into two streams one type ``A`` and one of type ``B``,
 - ``FlexiRoute[In]`` – (1 input, n outputs), which enables writing custom fan out elements using a simple DSL,
* **Fan-in**
 - ``Merge[In]`` – (n inputs , 1 output), picks signals randomly from inputs pushing them one by one to its output,
 - ``MergePreferred[In]`` – like :class:`Merge` but if elements are available on ``preferred`` port, it picks from it, otherwise randomly from ``others``,
 - ``ZipWith[A,B,...,Out]`` – (n inputs (defined upfront), 1 output), which takes a function of n inputs that, given all inputs are signalled, transforms and emits 1 output,
  + ``Zip[A,B,Out]`` – (2 inputs, 1 output), which is a :class:`ZipWith` specialised to zipping input streams of ``A`` and ``B`` into an ``(A,B)`` tuple stream,
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
In Akka Streams almost all computation stages *preserve input order* of elements, this means that if inputs ``{IA1,IA2,...,IAn}``
"cause" outputs ``{OA1,OA2,...,OAk}`` and inputs ``{IB1,IB2,...,IBm}`` "cause" outputs ``{OB1,OB2,...,OBl}`` and all of
``IAi`` happened before all ``IBi`` then ``OAi`` happens before ``OBi``.

This property is even uphold by async operations such as ``mapAsync``, however an unordered version exists
called ``mapAsyncUnordered`` which does not preserve this ordering.

However, in the case of Junctions which handle multiple input streams (e.g. :class:`Merge`) the output order is,
in general, *not defined* for elements arriving on different input ports, that is a merge-like operation may emit ``Ai``
before emitting ``Bi``, and it is up to its internal logic to decide the order of emitted elements. Specialized elements
such as ``Zip`` however *do guarantee* their outputs order, as each output element depends on all upstream elements having
been signalled already–thus the ordering in the case of zipping is defined by this property.

If you find yourself in need of fine grained control over order of emitted elements in fan-in
scenarios consider using :class:`MergePreferred` or :class:`FlexiMerge` - which gives you full control over how the
merge is performed.

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

