.. _stream-design:

Design Principles behind Akka Streams
=====================================

It took quite a while until we were reasonably happy with the look and feel of the API and the architecture of the implementation, and while being guided by intuition the design phase was very much exploratory research. This section details the findings and codifies them into a set of principles that have emerged during the process.

.. note::

  As detailed in the introduction keep in mind that the Akka Streams API is completely decoupled from the Reactive Streams interfaces which are just an implementation detail for how to pass stream data between individual processing stages.

What shall users of Akka Streams expect?
----------------------------------------

Akka is built upon a conscious decision to offer APIs that are minimal and consistent—as opposed to easy or intuitive. The credo is that we favor explicitness over magic, and if we provide a feature then it must work always, no exceptions. Another way to say this is that we minimize the number of rules a user has to learn instead of trying to keep the rules close to what we think users might expect.

From this follows that the principles implemented by Akka Streams are:

  * all features are explicit in the API, no magic
  * supreme compositionality: combined pieces retain the function of each part
  * exhaustive model of the domain of distributed bounded stream processing

This means that we provide all the tools necessary to express any stream processing topology, that we model all the essential aspects of this domain (back-pressure, buffering, transformations, failure recovery, etc.) and that whatever the user builds is reusable in a larger context.

Resulting Implementation Constraints
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Compositionality entails reusability of partial stream topologies, which led us to the lifted approach of describing data flows as (partial) FlowGraphs that can act as composite sources, flows (a.k.a. pipes) and sinks of data. These building blocks shall then be freely shareable, with the ability to combine them freely to form larger flows. The representation of these pieces must therefore be an immutable blueprint that is materialized in an explicit step in order to start the stream processing. The resulting stream processing engine is then also immutable in the sense of having a fixed topology that is prescribed by the blueprint. Dynamic networks need to be modeled by explicitly using the Reactive Streams interfaces for plugging different engines together.

The process of materialization may be parameterized, e.g. instantiating a blueprint for handling a TCP connection’s data with specific information about the connection’s address and port information. Additionally, materialization will often create specific objects that are useful to interact with the processing engine once it is running, for example for shutting it down or for extracting metrics. This means that the materialization function takes a set of parameters from the outside and it produces a set of results. Compositionality demands that these two sets cannot interact, because that would establish a covert channel by which different pieces could communicate, leading to problems of initialization order and inscrutable runtime failures.

Another aspect of materialization is that we want to support distributed stream processing, meaning that both the parameters and the results need to be location transparent—either serializable immutable values or ActorRefs. Using for example Futures would restrict materialization to the local JVM. There may be cases for which this will typically not be a severe restriction (like opening a TCP connection), but the principle remains.

Interoperation with other Reactive Streams implementations
----------------------------------------------------------

Akka Streams fully implement the Reactive Streams specification and interoperate with all other conformant implementations. We chose to completely separate the Reactive Streams interfaces (which we regard to be an SPI) from the user-level API. In order to obtain a :class:`Publisher` or :class:`Subscriber` from an Akka Stream topology, a corresponding ``Sink.publisher`` or ``Source.subscriber`` element must be used.

All stream Processors produced by the default materialization of Akka Streams are restricted to having a single Subscriber, additional Subscribers will be rejected. The reason for this is that the stream topologies described using our DSL never require fan-out behavior from the Publisher sides of the elements, all fan-out is done using explicit elements like :class:`Broadcast[T]`.

This means that ``Sink.publisher(<max number of Subscribers>)`` must be used where broadcast behavior is needed for interoperation with other Reactive Streams implementations.

What shall users of streaming libraries expect?
-----------------------------------------------

We expect libraries to be built on top of Akka Streams, in fact Akka HTTP is one such example that lives within the Akka project itself. In order to allow users to profit from the principles that are described for Akka Streams above, the following rules are established:

  * libraries shall provide their users with reusable pieces, allowing full compositionality
  * libraries may optionally and additionally provide facilities that consume and materialize flow descriptions

The reasoning behind the first rule is that compositionality would be destroyed if different libraries only accepted flow descriptions and expected to materialize them: using two of these together would be impossible because materialization can only happen once. As a consequence, the functionality of a library must be expressed such that materialization can be done by the user, outside of the library’s control.

The second rule allows a library to additionally provide nice sugar for the common case, an example of which is the Akka HTTP API that provides a ``handleWith`` method for convenient materialization.

.. note::

  One important consequence of this is that a reusable flow description cannot be bound to “live” resources, any connection to or allocation of such resources must be deferred until materialization time. Examples of “live” resources are already existing TCP connections, a multicast Publisher, etc.; a TickSource does not fall into this category if its timer is created only upon materialization (as is the case for our implementation).

Resulting Implementation Constraints
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Akka Streams must enable a library to express any stream processing utility in terms of immutable blueprints. The most common building blocks are

  * Source: something with exactly one output stream
  * Sink: something with exactly one input stream
  * Flow: something with exactly one input and one output stream
  * BidirectionalFlow: something with exactly two input streams and two output streams that conceptually behave like two Flows of opposite direction
  * Graph: a packaged stream processing topology that exposes a certain set of input and output ports, characterized by an object of type :class:`Shape`.

.. note::

  A source that emits a stream of streams is still just a normal Source, the kind of elements that are produced does not play a role in the static stream topology that is being expressed.

The difference between Error and Failure
----------------------------------------

The starting point for this discussion is the `definition given by the Reactive Manifesto <http://www.reactivemanifesto.org/glossary#Failure>`_. Translated to streams this means that an error is accessible within the stream as a normal data element, while a failure means that the stream itself has failed and is collapsing. In concrete terms, on the Reactive Streams interface level data elements (including errors) are signaled via ``onNext`` while failures raise the ``onError`` signal.

.. note::

  Unfortunately the method name for signaling *failure* to a Subscriber is called ``onError`` for historical reasons. Always keep in mind that the Reactive Streams interfaces (Publisher/Subscription/Subscriber) are modeling the low-level infrastructure for passing streams between execution units, and errors on this level are precisely the failures that we are talking about on the higher level that is modeled by Akka Streams.

There is only limited support for treating ``onError`` in Akka Streams compared to the operators that are available for the transformation of data elements, which is intentional in the spirit of the previous paragraph. Since ``onError`` signals that the stream is collapsing, its ordering semantics are not the same as for stream completion: transformation stages of any kind will just collapse with the stream, possibly still holding elements in implicit or explicit buffers. This means that data elements emitted before a failure can still be lost if the ``onError`` overtakes them.

The ability for failures to propagate faster than data elements is essential for tearing down streams that are back-pressured—especially since back-pressure can be the failure mode (e.g. by tripping upstream buffers which then abort because they cannot do anything else; or if a dead-lock occurred).

The semantics of stream recovery
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

A recovery element (i.e. any transformation that absorbs an ``onError`` signal and turns that into possibly more data elements followed normal stream completion) acts as a bulkhead that confines a stream collapse to a given region of the flow topology. Within the collapsed region buffered elements may be lost, but the outside is not affected by the failure.

This works in the same fashion as a ``try``–``catch`` expression: it marks a region in which exceptions are caught, but the exact amount of code that was skipped within this region in case of a failure might not be known precisely—the placement of statements matters.

The finer points of stream materialization
------------------------------------------

.. note::

  This is not yet implemented as stated here, this document illustrates intent.

It is commonly necessary to parameterize a flow so that it can be materialized for different arguments, an example would be the handler Flow that is given to a server socket implementation and materialized for each incoming connection with information about the peer’s address. On the other hand it is frequently necessary to retrieve specific objects that result from materialization, for example a ``Future[Unit]`` that signals the completion of a ``ForeachSink``.

It might be tempting to allow different pieces of a flow topology to access the materialization results of other pieces in order to customize their behavior, but that would violate composability and reusability as argued above. Therefore the arguments and results of materialization need to be segregated:

  * The Materializer is configured with a (type-safe) mapping from keys to values, which is exposed to the processing stages during their materialization.
  * The values in this mapping may act as channels, for example by using a Promise/Future pair to communicate a value; another possibility for such information-passing is of course to explicitly model it as a stream of configuration data elements within the graph itself.
  * The materialized values obtained from the processing stages are combined as prescribed by the user, but can of course be dependent on the values in the argument mapping.

To avoid having to use ``Future`` values as key bindings, materialization itself may become fully asynchronous. This would allow for example the use of the bound server port within the rest of the flow, and only if the port was actually bound successfully. The downside is that some APIs will then return ``Future[MaterializedMap]``, which means that others will have to accept this in turn in order to keep the usage burden as low as possible.
