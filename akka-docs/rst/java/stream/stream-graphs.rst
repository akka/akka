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

.. _graph-dsl-java:

Constructing Graphs
-------------------

Graphs are built from simple Flows which serve as the linear connections within the graphs as well as junctions
which serve as fan-in and fan-out points for Flows. Thanks to the junctions having meaningful types based on their behaviour
and making them explicit elements these elements should be rather straightforward to use.

Akka Streams currently provide these junctions (for a detailed list see :ref:`stages-overview_java`):

* **Fan-out**

 - ``Broadcast<T>`` – *(1 input, N outputs)* given an input element emits to each output
 - ``Balance<T>`` – *(1 input, N outputs)* given an input element emits to one of its output ports
 - ``UnzipWith<In,A,B,...>`` – *(1 input, N outputs)* takes a function of 1 input that given a value for each input emits N output elements (where N <= 20)
 - ``UnZip<A,B>`` – *(1 input, 2 outputs)* splits a stream of ``Pair<A,B>`` tuples into two streams, one of type ``A`` and one of type ``B``

* **Fan-in**

 - ``Merge<In>`` – *(N inputs , 1 output)* picks randomly from inputs pushing them one by one to its output
 - ``MergePreferred<In>`` – like :class:`Merge` but if elements are available on ``preferred`` port, it picks from it, otherwise randomly from ``others``
 - ``ZipWith<A,B,...,Out>`` – *(N inputs, 1 output)* which takes a function of N inputs that given a value for each input emits 1 output element
 - ``Zip<A,B>`` – *(2 inputs, 1 output)* is a :class:`ZipWith` specialised to zipping input streams of ``A`` and ``B`` into a ``Pair(A,B)`` tuple stream
 - ``Concat<A>`` – *(2 inputs, 1 output)* concatenates two streams (first consume one, then the second one)

One of the goals of the GraphDSL DSL is to look similar to how one would draw a graph on a whiteboard, so that it is
simple to translate a design from whiteboard to code and be able to relate those two. Let's illustrate this by translating
the below hand drawn graph into Akka Streams:

.. image:: ../../images/simple-graph-example.png

Such graph is simple to translate to the Graph DSL since each linear element corresponds to a :class:`Flow`,
and each circle corresponds to either a :class:`Junction` or a :class:`Source` or :class:`Sink` if it is beginning
or ending a :class:`Flow`.

.. includecode:: ../code/docs/stream/GraphDSLDocTest.java#simple-graph-dsl

.. note::
   Junction *reference equality* defines *graph node equality* (i.e. the same merge *instance* used in a GraphDSL
   refers to the same location in the resulting graph).

By looking at the snippets above, it should be apparent that the ``builder`` object is *mutable*.
The reason for this design choice is to enable simpler creation of complex graphs, which may even contain cycles.
Once the GraphDSL has been constructed though, the :class:`RunnableGraph` instance *is immutable, thread-safe, and freely shareable*.
The same is true of all graph pieces—sources, sinks, and flows—once they are constructed.
This means that you can safely re-use one given Flow or junction in multiple places in a processing graph.

We have seen examples of such re-use already above: the merge and broadcast junctions were imported
into the graph using ``builder.add(...)``, an operation that will make a copy of the blueprint that
is passed to it and return the inlets and outlets of the resulting copy so that they can be wired up.
Another alternative is to pass existing graphs—of any shape—into the factory method that produces a
new graph. The difference between these approaches is that importing using ``builder.add(...)`` ignores the
materialized value of the imported graph while importing via the factory method allows its inclusion;
for more details see :ref:`stream-materialization-scala`.

In the example below we prepare a graph that consists of two parallel streams,
in which we re-use the same instance of :class:`Flow`, yet it will properly be
materialized as two connections between the corresponding Sources and Sinks:

.. includecode:: ../code/docs/stream/GraphDSLDocTest.java#graph-dsl-reusing-a-flow

.. _partial-graph-dsl-java:

Constructing and combining Partial Graphs
-----------------------------------------

Sometimes it is not possible (or needed) to construct the entire computation graph in one place, but instead construct
all of its different phases in different places and in the end connect them all into a complete graph and run it.

This can be achieved by using the returned :class:`Graph` from ``GraphDSL.create()`` rather than
passing it to ``RunnableGraph.fromGraph()`` to wrap it in a :class:`RunnableGraph`.The reason of representing it as a different type is that a
:class:`RunnableGraph` requires all ports to be connected, and if they are not
it will throw an exception at construction time, which helps to avoid simple
wiring errors while working with graphs. A partial graph however allows
you to return the set of yet to be connected ports from the code block that
performs the internal wiring.

Let's imagine we want to provide users with a specialized element that given 3 inputs will pick
the greatest int value of each zipped triple. We'll want to expose 3 input ports (unconnected sources) and one output port
(unconnected sink).

.. includecode:: ../code/docs/stream/StreamPartialGraphDSLDocTest.java#simple-partial-graph-dsl

As you can see, first we construct the partial graph that describes how to compute the maximum of two input streams, then
we reuse that twice while constructing the partial graph that extends this to three input streams,
then we import it (all of its nodes and connections) explicitly into the last graph in which all
the undefined elements are rewired to real sources and sinks. The graph can then be run and yields the expected result.

.. warning::
   Please note that :class:`GraphDSL` is not able to provide compile time type-safety about whether or not all
   elements have been properly connected—this validation is performed as a runtime check during the graph's instantiation.

   A partial graph also verifies that all ports are either connected or part of the returned :class:`Shape`.

.. _constructing-sources-sinks-flows-from-partial-graphs-java:

Constructing Sources, Sinks and Flows from Partial Graphs
---------------------------------------------------------

Instead of treating a ``Graph`` as simply a collection of flows and junctions which may not yet all be
connected it is sometimes useful to expose such a complex graph as a simpler structure,
such as a :class:`Source`, :class:`Sink` or :class:`Flow`.

In fact, these concepts can be easily expressed as special cases of a partially connected graph:

* :class:`Source` is a partial graph with *exactly one* output, that is it returns a :class:`SourceShape`.
* :class:`Sink` is a partial graph with *exactly one* input, that is it returns a :class:`SinkShape`.
* :class:`Flow` is a partial graph with *exactly one* input and *exactly one* output, that is it returns a :class:`FlowShape`.

Being able to hide complex graphs inside of simple elements such as Sink / Source / Flow enables you to easily create one
complex element and from there on treat it as simple compound stage for linear computations.

In order to create a Source from a graph the method ``Source.fromGraph`` is used, to use it we must have a
``Graph`` with a ``SourceShape``. This is constructed using ``GraphDSL.create`` and providing building a ``SourceShape``
graph. The single outlet must be provided to the ``SourceShape.of`` method and will become “the sink that must
be attached before this Source can run”.

Refer to the example below, in which we create a Source that zips together two numbers, to see this graph
construction in action:

.. includecode:: ../code/docs/stream/StreamPartialGraphDSLDocTest.java#source-from-partial-graph-dsl

Similarly the same can be done for a ``Sink<T>`` using ``SinkShape.of`` in which case the provided value must be an
``Inlet<T>``. For defining a ``Flow<T>`` we need to expose both an undefined source and sink:

.. includecode:: ../code/docs/stream/StreamPartialGraphDSLDocTest.java#flow-from-partial-graph-dsl

Combining Sources and Sinks with simplified API
-----------------------------------------------

There is simplified API you can use to combine sources and sinks with junctions like: ``Broadcast<T>``, ``Balance<T>``,
``Merge<In>`` and ``Concat<A>`` without the need for using the Graph DSL. The combine method takes care of constructing
the necessary graph underneath. In following example we combine two sources into one (fan-in):

.. includecode:: ../code/docs/stream/StreamPartialGraphDSLDocTest.java#source-combine

The same can be done for a ``Sink`` but in this case it will be fan-out:

.. includecode:: ../code/docs/stream/StreamPartialGraphDSLDocTest.java#sink-combine

.. _bidi-flow-java:

Bidirectional Flows
-------------------

A graph topology that is often useful is that of two flows going in opposite
directions. Take for example a codec stage that serializes outgoing messages
and deserializes incoming octet streams. Another such stage could add a framing
protocol that attaches a length header to outgoing data and parses incoming
frames back into the original octet stream chunks. These two stages are meant
to be composed, applying one atop the other as part of a protocol stack. For
this purpose exists the special type :class:`BidiFlow` which is a graph that
has exactly two open inlets and two open outlets. The corresponding shape is
called :class:`BidiShape` and is defined like this:

.. includecode:: ../../../../akka-stream/src/main/scala/akka/stream/Shape.scala
   :include: bidi-shape
   :exclude: implementation-details-elided

A bidirectional flow is defined just like a unidirectional :class:`Flow` as
demonstrated for the codec mentioned above:

.. includecode:: ../code/docs/stream/BidiFlowDocTest.java
   :include: codec
   :exclude: implementation-details-elided

The first version resembles the partial graph constructor, while for the simple
case of a functional 1:1 transformation there is a concise convenience method
as shown on the last line. The implementation of the two functions is not
difficult either:

.. includecode:: ../code/docs/stream/BidiFlowDocTest.java#codec-impl

In this way you could easily integrate any other serialization library that
turns an object into a sequence of bytes.

The other stage that we talked about is a little more involved since reversing
a framing protocol means that any received chunk of bytes may correspond to
zero or more messages. This is best implemented using a :class:`GraphStage`
(see also :ref:`graphstage-java`).

.. includecode:: ../code/docs/stream/BidiFlowDocTest.java#framing

With these implementations we can build a protocol stack and test it:

.. includecode:: ../code/docs/stream/BidiFlowDocTest.java#compose

This example demonstrates how :class:`BidiFlow` subgraphs can be hooked 
together and also turned around with the ``.reversed()`` method. The test
simulates both parties of a network communication protocol without actually
having to open a network connection—the flows can just be connected directly.

.. _graph-matvalue-java:

Accessing the materialized value inside the Graph
-------------------------------------------------

In certain cases it might be necessary to feed back the materialized value of a Graph (partial, closed or backing a
Source, Sink, Flow or BidiFlow). This is possible by using ``builder.materializedValue`` which gives an ``Outlet`` that
can be used in the graph as an ordinary source or outlet, and which will eventually emit the materialized value.
If the materialized value is needed at more than one place, it is possible to call ``materializedValue`` any number of
times to acquire the necessary number of outlets.

.. includecode:: ../code/docs/stream/GraphDSLDocTest.java#graph-dsl-matvalue

Be careful not to introduce a cycle where the materialized value actually contributes to the materialized value.
The following example demonstrates a case where the materialized ``CompletionStage`` of a fold is fed back to the fold itself.

.. includecode:: ../code/docs/stream/GraphDSLDocTest.java#graph-dsl-matvalue-cycle

.. _graph-cycles-java:

Graph cycles, liveness and deadlocks
------------------------------------

Cycles in bounded stream topologies need special considerations to avoid potential deadlocks and other liveness issues.
This section shows several examples of problems that can arise from the presence of feedback arcs in stream processing
graphs.

In the following examples runnable graphs are created but do not run because each have some issue and will deadlock after start.
``Source`` variable is not defined as the nature and number of element does not matter for described problems.

The first example demonstrates a graph that contains a naive cycle.
The graph takes elements from the source, prints them, then broadcasts those elements
to a consumer (we just used ``Sink.ignore`` for now) and to a feedback arc that is merged back into the main
via a ``Merge`` junction.

.. includecode:: ../code/docs/stream/GraphCyclesDocTest.java#deadlocked

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

.. includecode:: ../code/docs/stream/GraphCyclesDocTest.java#unfair

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

.. includecode:: ../code/docs/stream/GraphCyclesDocTest.java#dropping

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

.. includecode:: ../code/docs/stream/GraphCyclesDocTest.java#zipping-dead

Still, when we try to run the example it turns out that no element is printed at all! After some investigation we
realize that:

* In order to get the first element from ``source`` into the cycle we need an already existing element in the cycle
* In order to get an initial element in the cycle we need an element from ``source``

These two conditions are a typical "chicken-and-egg" problem. The solution is to inject an initial
element into the cycle that is independent from ``source``. We do this by using a ``Concat`` junction on the backwards
arc that injects a single element using ``Source.single``.

.. includecode:: ../code/docs/stream/GraphCyclesDocTest.java#zipping-live

When we run the above example we see that processing starts and never stops. The important takeaway from this example
is that balanced cycles often need an initial "kick-off" element to be injected into the cycle.
