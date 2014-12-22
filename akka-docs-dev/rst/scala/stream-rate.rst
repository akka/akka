.. _stream-rate-scala:

#############################
Buffers and working with rate
#############################

Akka Streams processing stages are asynchronous and pipelined by default which means that a stage, after handing out
an element to its downstream consumer is able to immediately process the next message. To demonstrate what we mean
by this, let's take a look at the following example:

.. includecode:: code/docs/stream/StreamBuffersRateSpec.scala#pipelining

Running the above example, one of the possible outputs looks like this:

::

    A: 1
    A: 2
    B: 1
    A: 3
    B: 2
    C: 1
    B: 3
    C: 2
    C: 3

Note that the order is *not* ``A:1, B:1, C:1, A:2, B:2, C:2,`` which would correspond to a synchronous execution model
where an element completely flows through the processing pipeline before the next element enters the flow. The next
element is processed by a stage as soon as it emitted the previous one.

While pipelining in general increases throughput, in practice there is a cost of passing an element through the
asynchronous (and therefore thread crossing) boundary which is significant. To amortize this cost Akka Streams uses
a *windowed*, *batching* backpressure strategy internally. It is windowed because as opposed to a `Stop-And-Wait`_
protocol multiple elements might be "in-flight" concurrently with requests for elements. It is also batching because
a new element is not immediately requested once an element has been drained from the window-buffer but multiple elements
are requested after multiple elements has been drained. This batching strategy reduces the communication cost of
propagating the backpressure signal through the asynchronous boundary.

While this internal protocol is mostly invisible to the user (apart form its throughput increasing effects) there are
situations when these details get exposed. In all of our previous examples we always assumed that the rate of the
processing chain is strictly coordinated through the backpressure signal causing all stages to process no faster than
the throughput of the connected chain. There are tools in Akka Streams however that enable the rates of different segments
of a processing chain to be "detached" or to define the maximum throughput of the stream through external timing sources.
These situations are exactly those where the internal batching buffering strategy suddenly becomes non-transparent.

.. _Stop-And-Wait: https://en.wikipedia.org/wiki/Stop-and-wait_ARQ
.. _Reactive Streams: http://reactive-streams.org/

.. _stream-buffers-scala:

Buffers in Akka Streams
=======================

Internal buffers and their effect
---------------------------------

As we have explained, for performance reasons Akka Streams introduces a buffer for every processing stage. The purpose
of these buffers is solely optimization, in fact the size of 1 would be the most natural choice if there would be no
need for throughput improvements. Therefore it is recommended to keep these buffer sizes small, and increase them only
to a level that throughput requirements of the application require. Default buffer sizes can be set through configuration:

::

    akka.stream.materializer.max-input-buffer-size = 16

Alternatively they can be set by passing a :class:`MaterializerSettings` to the materializer:

.. includecode:: code/docs/stream/StreamBuffersRateSpec.scala#materializer-buffer

If buffer size needs to be set for segments of a Flow only, it is possible by defining a ``section()``:

.. includecode:: code/docs/stream/StreamBuffersRateSpec.scala#section-buffer

Here is an example of a code that demonstrate some of the issues caused by internal buffers:

.. includecode:: code/docs/stream/StreamBuffersRateSpec.scala#buffering-abstraction-leak

Running the above example one would expect the number *3* to be printed in every 3 seconds (the ``conflate`` step here
is configured so that it counts the number of elements received before the downstream ``ZipWith`` consumes them). What
is being printed is different though, we will see the number *1*. The reason for this is the internal buffer which is
by default 16 elements large, and prefetches elements before the ``ZipWith`` starts consuming them. It is possible
to fix this issue by changing the buffer size of ``ZipWith`` (or the whole graph) to 1. We will still see a leading
1 though which is caused by an initial prefetch of the ``ZipWith`` element.

.. note::
   In general, when time or rate driven processing stages exhibit strange behavior, one of the first solution to try
   should be to decrease the input buffer of the affected elements to 1.

Explicit user defined buffers
-----------------------------

*TODO*

Rate transformation
===================

Understanding conflate
----------------------

*TODO*

Understanding expand
--------------------

*TODO*
