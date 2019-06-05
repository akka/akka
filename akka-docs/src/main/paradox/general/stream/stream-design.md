# Design Principles behind Akka Streams

It took quite a while until we were reasonably happy with the look and feel of the API and the architecture of the implementation, and while being guided by intuition the design phase was very much exploratory research. This section details the findings and codifies them into a set of principles that have emerged during the process.

@@@ note

As detailed in the introduction keep in mind that the Akka Streams API is completely decoupled from the Reactive Streams interfaces which are an implementation detail for how to pass stream data between individual operators.

@@@

## What shall users of Akka Streams expect?

Akka is built upon a conscious decision to offer APIs that are minimal and consistent—as opposed to easy or intuitive. The credo is that we favor explicitness over magic, and if we provide a feature then it must work always, no exceptions. Another way to say this is that we minimize the number of rules a user has to learn instead of trying to keep the rules close to what we think users might expect.

From this follows that the principles implemented by Akka Streams are:

 * all features are explicit in the API, no magic
 * supreme compositionality: combined pieces retain the function of each part
 * exhaustive model of the domain of distributed bounded stream processing

This means that we provide all the tools necessary to express any stream processing topology, that we model all the essential aspects of this domain (back-pressure, buffering, transformations, failure recovery, etc.) and that whatever the user builds is reusable in a larger context.

### Akka Streams does not send dropped stream elements to the dead letter office

One important consequence of offering only features that can be relied upon is the restriction that Akka Streams cannot ensure that all objects sent through a processing topology will be processed. Elements can be dropped for a number of reasons:

 * plain user code can consume one element in a *map(...)* operator and produce an entirely different one as its result
 * common stream operators drop elements intentionally, e.g. take/drop/filter/conflate/buffer/…
 * stream failure will tear down the stream without waiting for processing to finish, all elements that are in flight will be discarded
 * stream cancellation will propagate upstream (e.g. from a *take* operator) leading to upstream processing steps being terminated without having processed all of their inputs

This means that sending JVM objects into a stream that need to be cleaned up will require the user to ensure that this happens outside of the Akka Streams facilities (e.g. by cleaning them up after a timeout or when their results are observed on the stream output, or by using other means like finalizers etc.).

### Resulting Implementation Constraints

Compositionality entails reusability of partial stream topologies, which led us to the lifted approach of describing data flows as (partial) graphs that can act as composite sources, flows (a.k.a. pipes) and sinks of data. These building blocks shall then be freely shareable, with the ability to combine them freely to form larger graphs. The representation of these pieces must therefore be an immutable blueprint that is materialized in an explicit step in order to start the stream processing. The resulting stream processing engine is then also immutable in the sense of having a fixed topology that is prescribed by the blueprint. Dynamic networks need to be modeled by explicitly using the Reactive Streams interfaces for plugging different engines together.

The process of materialization will often create specific objects that are useful to interact with the processing engine once it is running, for example for shutting it down or for extracting metrics. This means that the materialization function produces a result termed the *materialized value of a graph*.

## Interoperation with other Reactive Streams implementations

Akka Streams fully implement the Reactive Streams specification and interoperate with all other conformant implementations. We chose to completely separate the Reactive Streams interfaces from the user-level API because we regard them to be an SPI that is not targeted at endusers. In order to obtain a `Publisher` or `Subscriber` from an Akka Stream topology, a corresponding `Sink.asPublisher` or `Source.asSubscriber` element must be used.

All stream Processors produced by the default materialization of Akka Streams are restricted to having a single Subscriber, additional Subscribers will be rejected. The reason for this is that the stream topologies described using our DSL never require fan-out behavior from the Publisher sides of the elements, all fan-out is done using explicit elements like `Broadcast[T]`.

This means that `Sink.asPublisher(true)` (for enabling fan-out support) must be used where broadcast behavior is needed for interoperation with other Reactive Streams implementations.

### Rationale and benefits from Sink/Source/Flow not directly extending Reactive Streams interfaces

A sometimes overlooked crucial piece of information about [Reactive Streams](https://github.com/reactive-streams/reactive-streams-jvm/) is that they are a [Service Provider Interface](https://en.m.wikipedia.org/wiki/Service_provider_interface), as explained in depth in one of the [early discussions](https://github.com/reactive-streams/reactive-streams-jvm/pull/25) about the specification.
Akka Streams was designed during the development of Reactive Streams, so they both heavily influenced one another.

It may be enlightening to learn that even within the Reactive Specification the types had initially attempted to hide `Publisher`, `Subscriber` and the other SPI types from users of the API.
Though since those internal SPI types would end up surfacing to end users of the standard in some cases, it was decided to [remove the API types, and only keep the SPI types](https://github.com/reactive-streams/reactive-streams-jvm/pull/25) which are the `Publisher`, `Subscriber` et al.

With this historical knowledge and context about the purpose of the standard – being an internal detail of interoperable libraries - we can with certainty say that it can't be really said that an direct _inheritance_ relationship with these types can be considered some form of advantage or meaningful differentiator between libraries.
Rather, it could be seen that APIs which expose those SPI types to end-users are leaking internal implementation details accidentally. 

The `Source`, `Sink` and `Flow` types which are part of Akka Streams have the purpose of providing the fluent DSL, as well as to be "factories" for running those streams.
Their direct counterparts in Reactive Streams are, respectively, `Publisher`, `Subscriber` and `Processor`. 
In other words, Akka Streams operate on a lifted representation of the computing graph,
which then is materialized and executed in accordance to Reactive Streams rules. This also allows Akka Streams to perform optimizations like fusing and dispatcher configuration during the materialization step.

Another not obvious gain from hiding the Reactive Streams interfaces comes from the fact that `org.reactivestreams.Subscriber` (et al) have now been included in Java 9+, and thus become part of Java itself, so libraries should migrate to using the `java.util.concurrent.Flow.Subscriber` instead of `org.reactivestreams.Subscriber`.
Libraries which selected to expose and directly extend the Reactive Streams types will now have a tougher time to adapt the JDK9+ types -- all their classes that extend Subscriber and friends will need to be copied or changed to extend the exact same interface,
but from a different package. In Akka we simply expose the new type when asked to -- already supporting JDK9 types, from the day JDK9 was released.

The other, and perhaps more important reason for hiding the Reactive Streams interfaces comes back to the first points of this explanation: the fact of Reactive Streams being an SPI, and as such is hard to "get right" in ad-hoc implementations. Thus Akka Streams discourages the use of the hard to implement pieces of the underlying infrastructure, and offers simpler, more type-safe, yet more powerful abstractions for users to work with: GraphStages and operators. It is of course still (and easily) possible to accept or obtain Reactive Streams (or JDK+ Flow) representations of the stream operators by using methods like `asPublisher` or `fromSubscriber`.

## What shall users of streaming libraries expect?

We expect libraries to be built on top of Akka Streams, in fact Akka HTTP is one such example that lives within the Akka project itself. In order to allow users to profit from the principles that are described for Akka Streams above, the following rules are established:

 * libraries shall provide their users with reusable pieces, i.e. expose factories that return operators, allowing full compositionality
 * libraries may optionally and additionally provide facilities that consume and materialize operators

The reasoning behind the first rule is that compositionality would be destroyed if different libraries only accepted operators and expected to materialize them: using two of these together would be impossible because materialization can only happen once. As a consequence, the functionality of a library must be expressed such that materialization can be done by the user, outside of the library’s control.

The second rule allows a library to additionally provide nice sugar for the common case, an example of which is the Akka HTTP API that provides a `handleWith` method for convenient materialization.

@@@ note

One important consequence of this is that a reusable flow description cannot be bound to “live” resources, any connection to or allocation of such resources must be deferred until materialization time. Examples of “live” resources are already existing TCP connections, a multicast Publisher, etc.; a TickSource does not fall into this category if its timer is created only upon materialization (as is the case for our implementation).

Exceptions from this need to be well-justified and carefully documented.

@@@

### Resulting Implementation Constraints

Akka Streams must enable a library to express any stream processing utility in terms of immutable blueprints. The most common building blocks are

 * Source: something with exactly one output stream
 * Sink: something with exactly one input stream
 * Flow: something with exactly one input and one output stream
 * BidiFlow: something with exactly two input streams and two output streams that conceptually behave like two Flows of opposite direction
 * Graph: a packaged stream processing topology that exposes a certain set of input and output ports, characterized by an object of type `Shape`.

@@@ note

A source that emits a stream of streams is still a normal Source, the kind of elements that are produced does not play a role in the static stream topology that is being expressed.

@@@

## The difference between Error and Failure

The starting point for this discussion is the [definition given by the Reactive Manifesto](http://www.reactivemanifesto.org/glossary#Failure). Translated to streams this means that an error is accessible within the stream as a normal data element, while a failure means that the stream itself has failed and is collapsing. In concrete terms, on the Reactive Streams interface level data elements (including errors) are signaled via `onNext` while failures raise the `onError` signal.

@@@ note

Unfortunately the method name for signaling *failure* to a Subscriber is called `onError` for historical reasons. Always keep in mind that the Reactive Streams interfaces (Publisher/Subscription/Subscriber) are modeling the low-level infrastructure for passing streams between execution units, and errors on this level are precisely the failures that we are talking about on the higher level that is modeled by Akka Streams.

@@@

There is only limited support for treating `onError` in Akka Streams compared to the operators that are available for the transformation of data elements, which is intentional in the spirit of the previous paragraph. Since `onError` signals that the stream is collapsing, its ordering semantics are not the same as for stream completion: transformation operators of any kind will collapse with the stream, possibly still holding elements in implicit or explicit buffers. This means that data elements emitted before a failure can still be lost if the `onError` overtakes them.

The ability for failures to propagate faster than data elements is essential for tearing down streams that are back-pressured—especially since back-pressure can be the failure mode (e.g. by tripping upstream buffers which then abort because they cannot do anything else; or if a dead-lock occurred).

### The semantics of stream recovery

A recovery element (i.e. any transformation that absorbs an `onError` signal and turns that into possibly more data elements followed normal stream completion) acts as a bulkhead that confines a stream collapse to a given region of the stream topology. Within the collapsed region buffered elements may be lost, but the outside is not affected by the failure.

This works in the same fashion as a `try`–`catch` expression: it marks a region in which exceptions are caught, but the exact amount of code that was skipped within this region in case of a failure might not be known precisely—the placement of statements matters.
