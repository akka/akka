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

Quick Start: Reactive Tweets
============================

A typical use case for stream processing is consuming a live stream of data that we want to extract or aggregate some
other data from. In this example we'll consider consuming a stream of tweets and extracting information concerning Akka from them.

We will also consider the problem inherent to all non-blocking streaming solutions – "*What if the subscriber is slower
to consume the live stream of data?*" i.e. it is unable to keep up with processing the live data. Traditionally the solution
is often to buffer the elements, but this can (and usually *will*) cause eventual buffer overflows and instability of such systems.
Instead Akka Streams depend on internal backpressure signals that allow to control what should happen in such scenarios.

Here's the data model we'll be working with throughout the quickstart examples:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#model

Transforming and consuming simple streams
-----------------------------------------
In order to prepare our environment by creating an :class:`ActorSystem` and :class:`FlowMaterializer`,
which will be responsible for materializing and running the streams we are about to create:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#materializer-setup

The :class:`FlowMaterializer` can optionally take :class:`MaterializerSettings` which can be used to define
materialization properties, such as default buffer sizes (see also :ref:`stream-buffering-explained-scala`), the dispatcher to
be used by the pipeline etc. These can be overridden on an element-by-element basis or for an entire section, but this
will be discussed in depth in :ref:`stream-section-configuration`.

Let's assume we have a stream of tweets readily available, in Akka this is expressed as a :class:`Source[Out]`:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#tweet-source

Streams always start flowing from a :class:`Source[Out]` then can continue through :class:`Flow[In,Out]` elements or
more advanced graph elements to finally be consumed by a :class:`Sink[In]`. Both Sources and Flows provide stream operations
that can be used to transform the flowing data, a :class:`Sink` however does not since its the "end of stream" and its
behavior depends on the type of :class:`Sink` used.

In our case let's say we want to find all twitter handles of users which tweet about ``#akka``, the operations should look
familiar to anyone who has used the Scala Collections library, however they operate on streams and not collections of data:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#authors-filter-map

Finally in order to :ref:`materialize <stream-materialization-scala>` and run the stream computation we need to attach
the Flow to a :class:`Sink[T]` that will get the flow running. The simplest way to do this is to call
``runWith(sink)`` on a ``Source[Out]``. For convenience a number of common Sinks are predefined and collected as methods on
the :class:``Sink`` `companion object <http://doc.akka.io/api/akka-stream-and-http-experimental/1.0-M2-SNAPSHOT/#akka.stream.scaladsl.Sink$>`_.
For now let's simply print each author:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#authors-foreachsink-println

or by using the shorthand version (which are defined only for the most popular sinks such as :class:`FoldSink` and :class:`ForeachSink`):

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#authors-foreach-println

Materializing and running a stream always requires a :class:`FlowMaterializer` to be in implicit scope (or passed in explicitly,
like this: ``.run(mat)``).

Flattening sequences in streams
-------------------------------
In the previous section we were working on 1:1 relationships of elements which is the most common case, but sometimes
we might want to map from one element to a number of elements and receive a "flattened" stream, similarly like ``flatMap``
works on Scala Collections. In order to get a flattened stream of hashtags from our stream of tweets we can use the ``mapConcat``
combinator:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#hashtags-mapConcat

.. note::
  The name ``flatMap`` was consciously avoided due to its proximity with for-comprehensions and monadic composition.
  It is problematic for two reasons: firstly, flattening by concatenation is often undesirable in bounded stream processing
  due to the risk of deadlock (with merge being the preferred strategy), and secondly, the monad laws would not hold for
  our implementation of flatMap (due to the liveness issues).

  Please note that the mapConcat requires the supplied function to return a strict collection (``f:Out⇒immutable.Seq[T]``),
  whereas ``flatMap`` would have to operate on streams all the way through.


Broadcasting a stream
---------------------
Now let's say we want to persist all hashtags, as well as all author names from this one live stream.
For example we'd like to write all author handles into one file, and all hashtags into another file on disk.
This means we have to split the source stream into 2 streams which will handle the writing to these different files.

Elements that can be used to form such "fan-out" (or "fan-in") structures are referred to as "junctions" in Akka Streams.
One of these that we'll be using in this example is called :class:`Broadcast`, and it simply emits elements from its
input port to all of its output ports.

Akka Streams intentionally separate the linear stream structures (Flows) from the non-linear, branching ones (FlowGraphs)
in order to offer the most convenient API for both of these cases. Graphs can express arbitrarily complex stream setups
at the expense of not reading as familiarly as collection transformations. It is also possible to wrap complex computation
graphs as Flows, Sinks or Sources, which will be explained in detail in :ref:`constructing-sources-sinks-flows-from-partial-graphs-scala`.
FlowGraphs are constructed like this:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#flow-graph-broadcast

.. note::
  The ``~>`` (read as "edge", "via" or "to") operator is only available if ``FlowGraphImplicits._`` are imported.
  Without this import you can still construct graphs using the ``builder.addEdge(from,[through,]to)`` method.

As you can see, inside the :class:`FlowGraph` we use an implicit graph builder to mutably construct the graph
using the ``~>`` "edge operator" (also read as "connect" or "via" or "to"). Once we have the FlowGraph in the value ``g``
*it is immutable, thread-safe, and freely shareable*. A graph can can be ``run()`` directly - assuming all
ports (sinks/sources) within a flow have been connected properly. It is possible to construct :class:`PartialFlowGraph` s
where this is not required but this will be covered in detail in :ref:`partial-flow-graph-scala`.

As all Akka streams elements, :class:`Broadcast` will properly propagate back-pressure to its upstream element.

Back-pressure in action
-----------------------

One of the main advantages of Akka streams is that they *always* propagate back-pressure information from stream Sinks
(Subscribers) to their Sources (Publishers). It is not an optional feature, and is enabled at all times. To learn more
about the back-pressure protocol used by Akka Streams and all other Reactive Streams compatible implementations read
:ref:`back-pressure-explained-scala`.

A typical problem applications (not using Akka streams) like this often face is that they are unable to process the incoming data fast enough,
either temporarily or by design, and will start buffering incoming data until there's no more space to buffer, resulting
in either ``OutOfMemoryError`` s or other severe degradations of service responsiveness. With Akka streams buffering can
and must be handled explicitly. For example, if we are only interested in the "*most recent tweets, with a buffer of 10
elements*" this can be expressed using the ``buffer`` element:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#tweets-slow-consumption-dropHead

The ``buffer`` element takes an explicit and required ``OverflowStrategy``, which defines how the buffer should react
when it receives another element element while it is full. Strategies provided include dropping the oldest element (``dropHead``),
dropping the entire buffer, signalling errors etc. Be sure to pick and choose the strategy that fits your use case best.

Materialized values
-------------------
So far we've been only processing data using Flows and consuming it into some kind of external Sink - be it by printing
values or storing them in some external system. However sometimes we may be interested in some value that can be
obtained from the materialized processing pipeline. For example, we want to know how many tweets we have processed.
While this question is not as obvious to give an answer to in case of an infinite stream of tweets (one way to answer
this question in a streaming setting would to create a stream of counts described as "*up until now*, we've processed N tweets"),
but in general it is possible to deal with finite streams and come up with a nice result such as a total count of elements.

First, let's write such an element counter using :class:`FoldSink` and then we'll see how it is possible to obtain materialized
values from a :class:`MaterializedMap` which is returned by materializing an Akka stream. We'll split execution into multiple
lines for the sake of explaining the concepts of ``Materializable`` elements and ``MaterializedType``

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#tweets-fold-count

First, we prepare the :class:`FoldSink` which will be used to sum all ``Int`` elements of the stream.
Next we connect the ``tweets`` stream though a ``map`` step which converts each tweet into the number ``1``,
finally we connect the flow ``to`` the previously prepared Sink. Notice that this step does *not* yet materialize the
processing pipeline, it merely prepares the description of the Flow, which is now connected to a Sink, and therefore can
be ``run()``, as indicated by its type: :class:`RunnableFlow`. Next we call ``run()`` which uses the implicit :class:`FlowMaterializer`
to materialize and run the flow. The value returned by calling ``run()`` on a ``RunnableFlow`` or ``FlowGraph`` is ``MaterializedMap``,
which can be used to retrieve materialized values from the running stream.

In order to extract an materialized value from a running stream it is possible to call ``get(Materializable)`` on a materialized map
obtained from materializing a flow or graph. Since ``FoldSink`` implements ``Materializable`` and implements the ``MaterializedType``
as ``Future[Int]`` we can use it to obtain the :class:`Future` which when completed will contain the total length of our tweets stream.
In case of the stream failing, this future would complete with a Failure.

The reason we have to ``get`` the value out from the materialized map, is because a :class:`RunnableFlow` may be reused
and materialized multiple times, because it is just the "blueprint" of the stream. This means that if we materialize a stream,
for example one that consumes a live stream of tweets within a minute, the materialized values for those two materializations
will be different, as illustrated by this example:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#tweets-runnable-flow-materialized-twice

Many elements in Akka streams provide materialized values which can be used for obtaining either results of computation or
steering these elements which will be discussed in detail in :ref:`stream-materialization-scala`. Summing up this section, now we know
what happens behind the scenes when we run this one-liner, which is equivalent to the multi line version above:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#tweets-fold-count-oneline


Core concepts
=============

// TODO REWORD? This section explains the core types and concepts used in Akka Streams, from a more day-to-day use angle.
If we would like to get the big picture overview you may be interested in reading :ref:`stream-design`.

Sources, Flows and Sinks
------------------------

// TODO: runnable flow, types - runWith

// TODO: talk about how creating and sharing a ``Flow.of[String]`` is useful etc.

.. note::
  By default Akka streams elements support **exactly one** down-stream element.
  Making fan-out (supporting multiple downstream elements) an explicit opt-in feature allows default stream elements to
  be less complex and more efficient. Also it allows for greater flexibility on *how exactly* to handle the multicast scenarios,
  by providing named fan-out elements such as broadcast (signalls all down-stream elements) or balance (signals one of available down-stream elements).

.. _back-pressure-explained-scala:

Back-pressure explained
-----------------------

// TODO: explain the protocol and how it performs in slow-pub/fast-sub and fast-pub/slow-sub scenarios

Backpressure when Fast Publisher and Slow Subscriber
----------------------------------------------------

// TODO: Write me

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

When constructing flows and graphs in Akka streams think of them as preparing a blueprint, an execution plan.
Stream materialization is the process of taking a stream description (the graph) and allocating all the necessary resources
it needs in order to run. In the case of Akka streams this often means starting up Actors which power the processing,
but is not restricted to that - it could also mean opening files or socket connections etc. – depending on what the stream needs.

Materialization is triggered at so called "terminal operations". Most notably this includes the various forms of the ``run()``
and ``runWith()`` methods defined on flow elements as well as a small number of special syntactic sugars for running with
well-known sinks, such as ``foreach(el => )`` (being an alias to ``runWith(Sink.foreach(el => ))``.

MaterializedMap
^^^^^^^^^^^^^^^
**TODO - write me (feel free to move around as well)**

Working with rates
------------------

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


Working with Graphs
===================
Akka streams are unique in the way they handle and expose computation graphs - instead of hiding the fact that the
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

Akka streams currently provides these junctions:

* **Fan-out**
 - :class:`Broadcast` – (1 input, n outputs) signals each output given an input signal,
 - :class:`Balance` – (1 input => n outputs), signals one of its output ports given an input signal,
 - :class:`UnZip` – (1 input => 2 outputs), which is a specialized element which is able to split a stream of ``(A,B)`` into two streams one type ``A`` and one of type ``B``,
 - :class:`FlexiRoute` – (1 input, n outputs), which enables writing custom fan out elements using a simple DSL,
* **Fan-in**
 - :class:`Merge` – (n inputs , 1 output), picks signals randomly from inputs pushing them one by one to its output,
 - :class:`MergePreferred` – like :class:`Merge` but if elements are available on ``preferred`` port, it picks from it, otherwise randomly from ``others``,
 - :class:`ZipWith` – (n inputs (defined upfront), 1 output), which takes a function of n inputs that, given all inputs are signalled, transforms and emits 1 output,
  + :class:`Zip` – (2 inputs, 1 output), which is a :class:`ZipWith` specialised to zipping input streams of ``A`` and ``B`` into an ``(A,B)`` stream,
 - :class:`Concat` – (2 inputs, 1 output), which enables to concatenate streams (first consume one, then the second one), thus the order of which stream is ``first`` and which ``second`` matters,
 - :class:`FlexiMerge` – (n inputs, 1 output), which enables writing custom fan out elements using a simple DSL.

One of the goals of the FlowGraph DSL is to look similar to how one would draw a graph on a whiteboard, so that it is
simple to translate a design from whiteboard to code and be able to relate those two. Let's illustrate this by translating
the below hand drawn graph into Akka streams:

.. image:: ../images/simple-graph-example.png

Such graph is simple to translate to the Graph DSL since each linear element corresponds to a :class:`Flow`,
and each circle corresponds to either a :class:`Junction` or a :class:`Source` or :class:`Sink` if it is beginning
or ending a :class:`Flow`.

.. includecode:: code/docs/stream/FlowGraphDocSpec.scala#simple-flow-graph

Notice the ``import FlowGraphImplicits._`` which brings into scope the ``~>`` operator (read as "edge", "via" or "to").
It is also possible to construct graphs without the ``~>`` operator in case you prefer to use the graph builder explicitly:

.. includecode:: code/docs/stream/FlowGraphDocSpec.scala#simple-flow-graph-no-implicits

.. _partial-flow-graph-scala:

Constructing and combining Partial Flow Graphs
----------------------------------------------
Sometimes it is not possible (or needed) to construct the entire computation graph in one place, but instead construct
all of it is different phases in different places and in the end connect them all into a complete graph and run it.

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

Dealing with cycles, deadlocks
------------------------------
// TODO: why to avoid cycles, how to enable if you really need to

// TODO: problem cases, expand-conflate, expand-filter

// TODO: working with rate

// TODO: custom processing

// TODO: stages and flexi stuff

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

Actor based custom elements
---------------------------

ActorPublisher
^^^^^^^^^^^^^^

ActorSubscriber
^^^^^^^^^^^^^^^


// TODO: Implementing Reactive Streams interfaces directly vs. extending ActorPublisher / ActorSubscriber???

Integrating with Actors
=======================

// TODO: Source.subscriber

// TODO: Sink.publisher

// TODO: Use the ImplicitFlowMaterializer if you have streams starting from inside actors.

// TODO: how do I create my own sources / sinks?

Integration with Reactive Streams enabled libraries
===================================================

// TODO: some info about reactive streams in general

// TODO: Simply runWith(Sink.publisher) and runWith(Source.subscriber) to get the corresponding reactive streams types.

