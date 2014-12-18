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

.. includecode:: code/docs/stream/GraphCyclesSpec.scala#deadlocked

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

.. includecode:: code/docs/stream/GraphCyclesSpec.scala#unfair

If we run the example we see that the same sequence of numbers are printed
over and over again, but the processing does not stop. Hence, we avoided the deadlock, but ``source`` is still
backpressured forever, because buffer space is never recovered: the only action we see is the circulation of a couple
of initial elements from ``source``.

.. note::
  What we see here is that in certain cases we need to choose between boundedness and liveness. Our first example would
  not deadlock if there would be an infinite buffer in the loop, or vice versa, if the elements in the cycle would
  be balanced (as many elements are removed as many are injected) then there would be no deadlock.

To make our cycle both live (not deadlocking) and fair we can introduce a dropping element on the feedback arc. In this
case we chose the ``buffer()`` operation giving it a dropping strategy ``OverflowStrategy.dropHead``.

.. includecode:: code/docs/stream/GraphCyclesSpec.scala#dropping

If we run this example we see that

* The flow of elements does not stop, there are always elements printed
* We see that some of the numbers are printed several times over time (due to the feedback loop) but on average
the numbers are increasing in the long term

This example highlights that one solution to avoid deadlocks in the presence of potentially unbalanced cycles
(cycles where the number of circulating elements are unbounded) is to drop elements. An alternative would be to
define a larger buffer with ``OverflowStrategy.error`` which would fail the stream instead of deadlocking it after
all buffer space has been consumed.

As we discovered in the previous examples, the core problem was the unbalanced nature of the feedback loop. We
circumvented this issue by adding a dropping element, but now we want to build a cycle that is balanced from
the beginning instead. To achieve this we modify our first graph by replacing the ``Merge`` junction with a ``ZipWith``.
Since ``ZipWith`` takes one element from ``source`` *and* from the feedback arc to inject one element into the cycle,
we maintain the balance of elements.

.. includecode:: code/docs/stream/GraphCyclesSpec.scala#zipping-dead

Still, when we try to run the example it turns out that no element is printed at all! After some investigation we
realize that:

* In order to get the first element from ``source`` into the cycle we need an already existing element in the cycle
* In order to get an initial element in the cycle we need an element from ``source``

These two conditions are a typical "chicken-and-egg" problem. The solution is to inject an initial
element into the cycle that is independent from ``source``. We do this by using a ``Concat`` junction on the backwards
arc that injects a single element using ``Source.single``.

.. includecode:: code/docs/stream/GraphCyclesSpec.scala#zipping-live

When we run the above example we see that processing starts and never stops. The important takeaway from this example
is that balanced cycles often need an initial "kick-off" element to be injected into the cycle.