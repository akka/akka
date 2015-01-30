.. _stream-graph-java:

###################
Working with Graphs
###################

In Akka Streams computation graphs are not expressed using a fluent DSL like linear computations are, instead they are
written in a more graph-resembling DSL which aims to make translating graph drawings (e.g. from notes taken
from design discussions, or illustrations in protocol specifications) to and from code simpler. In this section we'll
dive into the multiple ways of constructing and re-using graphs, as well as explain common pitfalls and how to avoid them.

Graphs are needed whenever you want to perform any kind of fan-in ("multiple inputs") or fan-out ("multiple outputs") operations.
Considering linear Flows to be like roads, we can picture graph operations as junctions: multiple flows being connected at a single point.
Some graph operations which are common enough and fit the linear style of Flows, such as ``concat`` (which concatenates two
streams, such that the second one is consumed after the first one has completed), may have shorthand methods defined on
:class:`Flow` or :class:`Source` themselves, however you should keep in mind that those are also implemented as graph junctions.

.. _flow-graph-java:

Constructing Flow Graphs
------------------------
Flow graphs are built from simple Flows which serve as the linear connections within the graphs as well as junctions
which serve as fan-in and fan-out points for Flows. Thanks to the junctions having meaningful types based on their behaviour
and making them explicit elements these elements should be rather straightforward to use.

Akka Streams currently provide these junctions:

* **Fan-out**

 - ``Broadcast<T>`` – (1 input, n outputs) signals each output given an input signal,
 - ``Balance<T>`` – (1 input => n outputs), signals one of its output ports given an input signal,
 - ``UnZip<A,B>`` – (1 input => 2 outputs), which is a specialized element which is able to split a stream of ``Pair<A,B>`` into two streams one type ``A`` and one of type ``B``,
 - ``FlexiRoute<In>`` – (1 input, n outputs), which enables writing custom fan out elements using a simple DSL,

* **Fan-in**

 - ``Merge<In>`` – (n inputs , 1 output), picks signals randomly from inputs pushing them one by one to its output,
 - ``MergePreferred<In>`` – like :class:`Merge` but if elements are available on ``preferred`` port, it picks from it, otherwise randomly from ``others``,
 - ``ZipWith<A,B,...,Out>`` – (n inputs (defined upfront), 1 output), which takes a function of n inputs that, given all inputs are signalled, transforms and emits 1 output,
 - ``Zip<A,B>`` – (2 inputs, 1 output), which is a :class:`ZipWith` specialised to zipping input streams of ``A`` and ``B`` into a ``Pair<A,B>`` stream,
 - ``Concat<A>`` – (2 inputs, 1 output), which enables to concatenate streams (first consume one, then the second one), thus the order of which stream is ``first`` and which ``second`` matters,
 - ``FlexiMerge<Out>`` – (n inputs, 1 output), which enables writing custom fan out elements using a simple DSL.

One of the goals of the FlowGraph DSL is to look similar to how one would draw a graph on a whiteboard, so that it is
simple to translate a design from whiteboard to code and be able to relate those two. Let's illustrate this by translating
the below hand drawn graph into Akka Streams:

.. image:: ../images/simple-graph-example.png

Such graph is simple to translate to the Graph DSL since each linear element corresponds to a :class:`Flow`,
and each circle corresponds to either a :class:`Junction` or a :class:`Source` or :class:`Sink` if it is beginning
or ending a :class:`Flow`. Those are connected with the ``addEdge`` method of the :class:`FlowGraphBuilder`.

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/FlowGraphDocTest.java#simple-flow-graph

.. note::
   Junction *reference equality* defines *graph node equality* (i.e. the same merge *instance* used in a FlowGraph
   refers to the same location in the resulting graph).


By looking at the snippets above, it should be apparent that the :class:`FlowGraphBuilder` object is *mutable*.
The reason for this design choice is to enable simpler creation of complex graphs, which may even contain cycles.
Once the FlowGraph has been constructed though, the :class:`FlowGraph` instance *is immutable, thread-safe, and freely shareable*.

Linear Flows however are always immutable and appending an operation to a Flow always returns a new Flow instance.
This means that you can safely re-use one given Flow in multiple places in a processing graph. In the example below
we prepare a graph that consists of two parallel streams, in which we re-use the same instance of :class:`Flow`,
yet it will properly be materialized as two connections between the corresponding Sources and Sinks:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/FlowGraphDocTest.java#flow-graph-reusing-a-flow

.. _partial-flow-graph-java:

Constructing and combining Partial Flow Graphs
----------------------------------------------
Sometimes it is not possible (or needed) to construct the entire computation graph in one place, but instead construct
all of its different phases in different places and in the end connect them all into a complete graph and run it.

This can be achieved using :class:`PartialFlowGraph`. The reason of representing it as a different type is that a
:class:`FlowGraph` requires all ports to be connected, and if they are not it will throw an exception at construction
time, which helps to avoid simple wiring errors while working with graphs. A partial flow graph however does not perform
this validation, and allows graphs that are not yet fully connected.

A :class:`PartialFlowGraph` is defined as a :class:`FlowGraph` which contains so called "undefined elements",
such as ``UndefinedSink<T>`` or ``UndefinedSource<T>``, which can be reused and plugged into by consumers of that
partial flow graph. Let's imagine we want to provide users with a specialized element that given 3 inputs will pick
the greatest int value of each zipped triple. We'll want to expose 3 input ports (undefined sources) and one output port
(undefined sink).

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/StreamPartialFlowGraphDocTest.java#simple-partial-flow-graph

As you can see, first we construct the partial graph that contains all the zipping and comparing of stream
elements, then we import it (all of its nodes and connections) explicitly to the :class:`FlowGraph` instance in which all
the undefined elements are rewired to real sources and sinks. The graph can then be run and yields the expected result.

.. warning::
   Please note that a :class:`FlowGraph` is not able to provide compile time type-safety about whether or not all
   elements have been properly connected - this validation is performed as a runtime check during the graph's instantiation.

.. _constructing-sources-sinks-flows-from-partial-graphs-java:

Constructing Sources, Sinks and Flows from Partial Graphs
---------------------------------------------------------
Instead of treating a :class:`PartialFlowGraph` as simply a collection of flows and junctions which may not yet all be
connected it is sometimes useful to expose such a complex graph as a simpler structure,
such as a :class:`Source`, :class:`Sink` or :class:`Flow`.

In fact, these concepts can be easily expressed as special cases of a partially connected graph:

* :class:`Source` is a partial flow graph with *exactly one* :class:`UndefinedSink`,
* :class:`Sink` is a partial flow graph with *exactly one* :class:`UndefinedSource`,
* :class:`Flow` is a partial flow graph with *exactly one* :class:`UndefinedSource` and *exactly one* :class:`UndefinedSource`.

Being able to hide complex graphs inside of simple elements such as Sink / Source / Flow enables you to easily create one
complex element and from there on treat it as simple compound stage for linear computations.

In order to create a Source from a partial flow graph ``Source`` provides a special apply method that takes a function
that must return an ``UndefinedSink``. This undefined sink will become "the sink that must be attached before this Source
can run". Refer to the example below, in which we create a Source that zips together two numbers, to see this graph
construction in action:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/StreamPartialFlowGraphDocTest.java#source-from-partial-flow-graph

Similarly the same can be done for a ``Sink<T>``, in which case the returned value must be an ``UndefinedSource<T>``.
For defining a ``Flow<T>`` we need to expose both an undefined source and sink:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/StreamPartialFlowGraphDocTest.java#flow-from-partial-flow-graph

.. _graph-cycles-java:

Graph cycles, liveness and deadlocks
------------------------------------

By default :class:`FlowGraph` does not allow (or to be precise, its builder does not allow) the creation of cycles.
The reason for this is that cycles need special considerations to avoid potential deadlocks and other liveness issues.
This section shows several examples of problems that can arise from the presence of feedback arcs in stream processing
graphs.

The first example demonstrates a graph that contains a naive cycle (the presence of cycles is enabled by calling
``allowCycles()`` on the builder). The graph takes elements from the source, prints them, then broadcasts those elements
to a consumer (we just used ``Sink.ignore`` for now) and to a feedback arc that is merged back into the main stream via
a ``Merge`` junction.

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/GraphCyclesDocTest.java#deadlocked

Running this we observe that after a few numbers have been printed, no more elements are logged to the console -
all processing stops after some time. After some investigation we observe that:

* through merging from ``source`` we increase the number of elements flowing in the cycle
* by broadcasting back to the cycle we do not decrease the number of elements in the cycle

Since Akka Streams (and Reactive Streams in general) guarantee bounded processing (see the "Buffering" section for more
details) it means that only a bounded number of elements are buffered over any time span. Since our cycle gains more and
more elements, eventually all of its internal buffers become full, backpressuring ``source`` forever. To be able
to process more elements from ``source`` elements would need to leave the cycle somehow.

If we modify our feedback loop by replacing the ``Merge`` junction with a ``MergePreferred`` we can avoid the deadlock.
``MergePreferred`` is unfair as it always tries to consume from a preferred input port if there are elements available
before trying the other lower priority input ports. Since we feed back through the preferred port it is always guaranteed
that the elements in the cycles can flow.

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/GraphCyclesDocTest.java#unfair

If we run the example we see that the same sequence of numbers are printed
over and over again, but the processing does not stop. Hence, we avoided the deadlock, but ``source`` is still
back-pressured forever, because buffer space is never recovered: the only action we see is the circulation of a couple
of initial elements from ``source``.

.. note::
   What we see here is that in certain cases we need to choose between boundedness and liveness. Our first example would
   not deadlock if there would be an infinite buffer in the loop, or vice versa, if the elements in the cycle would
   be balanced (as many elements are removed as many are injected) then there would be no deadlock.

To make our cycle both live (not deadlocking) and fair we can introduce a dropping element on the feedback arc. In this
case we chose the ``buffer()`` operation giving it a dropping strategy ``OverflowStrategy.dropHead``.

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/GraphCyclesDocTest.java#dropping

If we run this example we see that

* The flow of elements does not stop, there are always elements printed
* We see that some of the numbers are printed several times over time (due to the feedback loop) but on average
  the numbers are increasing in the long term

This example highlights that one solution to avoid deadlocks in the presence of potentially unbalanced cycles
(cycles where the number of circulating elements are unbounded) is to drop elements. An alternative would be to
define a larger buffer with ``OverflowStrategy.fail`` which would fail the stream instead of deadlocking it after
all buffer space has been consumed.

As we discovered in the previous examples, the core problem was the unbalanced nature of the feedback loop. We
circumvented this issue by adding a dropping element, but now we want to build a cycle that is balanced from
the beginning instead. To achieve this we modify our first graph by replacing the ``Merge`` junction with a ``ZipWith``.
Since ``ZipWith`` takes one element from ``source`` *and* from the feedback arc to inject one element into the cycle,
we maintain the balance of elements.

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/GraphCyclesDocTest.java#zipping-dead

Still, when we try to run the example it turns out that no element is printed at all! After some investigation we
realize that:

* In order to get the first element from ``source`` into the cycle we need an already existing element in the cycle
* In order to get an initial element in the cycle we need an element from ``source``

These two conditions are a typical "chicken-and-egg" problem. The solution is to inject an initial
element into the cycle that is independent from ``source``. We do this by using a ``Concat`` junction on the backwards
arc that injects a single element using ``Source.single``.

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/GraphCyclesDocTest.java#zipping-live

When we run the above example we see that processing starts and never stops. The important takeaway from this example
is that balanced cycles often need an initial "kick-off" element to be injected into the cycle.
