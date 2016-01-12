.. _stream-quickstart-scala:

Quick Start Guide: Reactive Tweets
==================================

A typical use case for stream processing is consuming a live stream of data that we want to extract or aggregate some
other data from. In this example we'll consider consuming a stream of tweets and extracting information concerning Akka from them.

We will also consider the problem inherent to all non-blocking streaming
solutions: *"What if the subscriber is too slow to consume the live stream of
data?"*. Traditionally the solution is often to buffer the elements, but this
can—and usually will—cause eventual buffer overflows and instability of such
systems. Instead Akka Streams depend on internal backpressure signals that
allow to control what should happen in such scenarios.

Here's the data model we'll be working with throughout the quickstart examples:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#model

.. note::
  If you would like to get an overview of the used vocabulary first instead of diving head-first
  into an actual example you can have a look at the :ref:`core-concepts-scala` and :ref:`defining-and-running-streams-scala`
  sections of the docs, and then come back to this quickstart to see it all pieced together into a simple example application.

Transforming and consuming simple streams
-----------------------------------------
The example application we will be looking at is a simple Twitter feed stream from which we'll want to extract certain information,
like for example finding all twitter handles of users who tweet about ``#akka``.

In order to prepare our environment by creating an :class:`ActorSystem` and :class:`ActorMaterializer`,
which will be responsible for materializing and running the streams we are about to create:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#materializer-setup

The :class:`ActorMaterializer` can optionally take :class:`ActorMaterializerSettings` which can be used to define
materialization properties, such as default buffer sizes (see also :ref:`stream-buffers-scala`), the dispatcher to
be used by the pipeline etc. These can be overridden with ``withAttributes`` on :class:`Flow`, :class:`Source`, :class:`Sink` and :class:`Graph`.

Let's assume we have a stream of tweets readily available. In Akka this is expressed as a :class:`Source[Out, M]`:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#tweet-source

Streams always start flowing from a :class:`Source[Out,M1]` then can continue through :class:`Flow[In,Out,M2]` elements or
more advanced graph elements to finally be consumed by a :class:`Sink[In,M3]` (ignore the type parameters ``M1``, ``M2``
and ``M3`` for now, they are not relevant to the types of the elements produced/consumed by these classes – they are
"materialized types", which we'll talk about :ref:`below <materialized-values-quick-scala>`).

The operations should look familiar to anyone who has used the Scala Collections library,
however they operate on streams and not collections of data (which is a very important distinction, as some operations
only make sense in streaming and vice versa):

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#authors-filter-map

Finally in order to :ref:`materialize <stream-materialization-scala>` and run the stream computation we need to attach
the Flow to a :class:`Sink` that will get the Flow running. The simplest way to do this is to call
``runWith(sink)`` on a ``Source``. For convenience a number of common Sinks are predefined and collected as methods on
the :class:`Sink` `companion object <http://doc.akka.io/api/akka-stream-and-http-experimental/@version@/#akka.stream.scaladsl.Sink$>`_.
For now let's simply print each author:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#authors-foreachsink-println

or by using the shorthand version (which are defined only for the most popular Sinks such as ``Sink.fold`` and ``Sink.foreach``):

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#authors-foreach-println

Materializing and running a stream always requires a :class:`Materializer` to be in implicit scope (or passed in explicitly,
like this: ``.run(materializer)``).

The complete snippet looks like this:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#first-sample

Flattening sequences in streams
-------------------------------
In the previous section we were working on 1:1 relationships of elements which is the most common case, but sometimes
we might want to map from one element to a number of elements and receive a "flattened" stream, similarly like ``flatMap``
works on Scala Collections. In order to get a flattened stream of hashtags from our stream of tweets we can use the ``mapConcat``
combinator:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#hashtags-mapConcat

.. note::
  The name ``flatMap`` was consciously avoided due to its proximity with for-comprehensions and monadic composition.
  It is problematic for two reasons: first, flattening by concatenation is often undesirable in bounded stream processing
  due to the risk of deadlock (with merge being the preferred strategy), and second, the monad laws would not hold for
  our implementation of flatMap (due to the liveness issues).

  Please note that the ``mapConcat`` requires the supplied function to return a strict collection (``f:Out=>immutable.Seq[T]``),
  whereas ``flatMap`` would have to operate on streams all the way through.

Broadcasting a stream
---------------------
Now let's say we want to persist all hashtags, as well as all author names from this one live stream.
For example we'd like to write all author handles into one file, and all hashtags into another file on disk.
This means we have to split the source stream into two streams which will handle the writing to these different files.

Elements that can be used to form such "fan-out" (or "fan-in") structures are referred to as "junctions" in Akka Streams.
One of these that we'll be using in this example is called :class:`Broadcast`, and it simply emits elements from its
input port to all of its output ports.

Akka Streams intentionally separate the linear stream structures (Flows) from the non-linear, branching ones (Graphs)
in order to offer the most convenient API for both of these cases. Graphs can express arbitrarily complex stream setups
at the expense of not reading as familiarly as collection transformations.

Graphs are constructed using :class:`GraphDSL` like this:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#flow-graph-broadcast

As you can see, inside the :class:`GraphDSL` we use an implicit graph builder ``b`` to mutably construct the graph
using the ``~>`` "edge operator" (also read as "connect" or "via" or "to"). The operator is provided implicitly
by importing ``GraphDSL.Implicits._``.

``GraphDSL.create`` returns a :class:`Graph`, in this example a :class:`Graph[ClosedShape, Unit]` where
:class:`ClosedShape` means that it is *a fully connected graph* or "closed" - there are no unconnected inputs or outputs.
Since it is closed it is possible to transform the graph into a :class:`RunnableGraph` using ``RunnableGraph.fromGraph``.
The runnable graph can then be ``run()`` to materialize a stream out of it.

Both :class:`Graph` and :class:`RunnableGraph` are *immutable, thread-safe, and freely shareable*.

A graph can also have one of several other shapes, with one or more unconnected ports. Having unconnected ports
expresses a grapth that is a *partial graph*. Concepts around composing and nesting graphs in large structures are
explained in detail in :ref:`composition-scala`. It is also possible to wrap complex computation graphs
as Flows, Sinks or Sources, which will be explained in detail in
:ref:`constructing-sources-sinks-flows-from-partial-graphs-scala`.

Back-pressure in action
-----------------------
One of the main advantages of Akka Streams is that they *always* propagate back-pressure information from stream Sinks
(Subscribers) to their Sources (Publishers). It is not an optional feature, and is enabled at all times. To learn more
about the back-pressure protocol used by Akka Streams and all other Reactive Streams compatible implementations read
:ref:`back-pressure-explained-scala`.

A typical problem applications (not using Akka Streams) like this often face is that they are unable to process the incoming data fast enough,
either temporarily or by design, and will start buffering incoming data until there's no more space to buffer, resulting
in either ``OutOfMemoryError`` s or other severe degradations of service responsiveness. With Akka Streams buffering can
and must be handled explicitly. For example, if we are only interested in the "*most recent tweets, with a buffer of 10
elements*" this can be expressed using the ``buffer`` element:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#tweets-slow-consumption-dropHead

The ``buffer`` element takes an explicit and required ``OverflowStrategy``, which defines how the buffer should react
when it receives another element while it is full. Strategies provided include dropping the oldest element (``dropHead``),
dropping the entire buffer, signalling errors etc. Be sure to pick and choose the strategy that fits your use case best.

.. _materialized-values-quick-scala:

Materialized values
-------------------
So far we've been only processing data using Flows and consuming it into some kind of external Sink - be it by printing
values or storing them in some external system. However sometimes we may be interested in some value that can be
obtained from the materialized processing pipeline. For example, we want to know how many tweets we have processed.
While this question is not as obvious to give an answer to in case of an infinite stream of tweets (one way to answer
this question in a streaming setting would be to create a stream of counts described as "*up until now*, we've processed N tweets"),
but in general it is possible to deal with finite streams and come up with a nice result such as a total count of elements.

First, let's write such an element counter using ``Sink.fold`` and see how the types look like:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#tweets-fold-count

First we prepare a reusable ``Flow`` that will change each incoming tweet into an integer of value ``1``. We'll use this in
order to combine those with a ``Sink.fold`` that will sum all ``Int`` elements of the stream and make its result available as
a ``Future[Int]``. Next we connect the ``tweets`` stream to ``count`` with ``via``. Finally we connect the Flow to the previously
prepared Sink using ``toMat``.

Remember those mysterious ``Mat`` type parameters on ``Source[+Out, +Mat]``, ``Flow[-In, +Out, +Mat]`` and ``Sink[-In, +Mat]``?
They represent the type of values these processing parts return when materialized. When you chain these together,
you can explicitly combine their materialized values. In our example we used the ``Keep.right`` predefined function,
which tells the implementation to only care about the materialized type of the stage currently appended to the right.
The materialized type of ``sumSink`` is ``Future[Int]`` and because of using ``Keep.right``, the resulting :class:`RunnableGraph`
has also a type parameter of ``Future[Int]``.

This step does *not* yet materialize the
processing pipeline, it merely prepares the description of the Flow, which is now connected to a Sink, and therefore can
be ``run()``, as indicated by its type: ``RunnableGraph[Future[Int]]``. Next we call ``run()`` which uses the implicit :class:`ActorMaterializer`
to materialize and run the Flow. The value returned by calling ``run()`` on a ``RunnableGraph[T]`` is of type ``T``.
In our case this type is ``Future[Int]`` which, when completed, will contain the total length of our ``tweets`` stream.
In case of the stream failing, this future would complete with a Failure.

A :class:`RunnableGraph` may be reused
and materialized multiple times, because it is just the "blueprint" of the stream. This means that if we materialize a stream,
for example one that consumes a live stream of tweets within a minute, the materialized values for those two materializations
will be different, as illustrated by this example:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#tweets-runnable-flow-materialized-twice

Many elements in Akka Streams provide materialized values which can be used for obtaining either results of computation or
steering these elements which will be discussed in detail in :ref:`stream-materialization-scala`. Summing up this section, now we know
what happens behind the scenes when we run this one-liner, which is equivalent to the multi line version above:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#tweets-fold-count-oneline

.. note::
  ``runWith()`` is a convenience method that automatically ignores the materialized value of any other stages except
  those appended by the ``runWith()`` itself. In the above example it translates to using ``Keep.right`` as the combiner
  for materialized values.
