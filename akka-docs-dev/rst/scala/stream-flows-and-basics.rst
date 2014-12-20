.. _stream-flow-scala:

#############################
Basics and working with Flows
#############################

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
