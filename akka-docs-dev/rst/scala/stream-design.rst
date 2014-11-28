.. _stream-design:

Design Principles behind Akka Streams
====================================

It took quite a while until we were reasonably happy with the look and feel of the API and the architecture of the implementation, and while being guided by intuition the design phase was very much exploratory research. This section details the findings and codifies them into a set of principles that have emerged during the process.

What shall users of Akka Streams expect?
-------------------------------------------

Akka is built upon a conscious decision to offer APIs that are minimal and consistent—as opposed to easy or intuitive. The credo is that we favor explicitness over magic, and if we provide a feature then it must work always, no exceptions. Another way to say this is that we minimize the number of rules a user has to learn instead of trying to keep the rules close to what we think users might expect.

From this follows that the principles implemented by Akka Streams are:

  * all features are explicit in the API, no magic
  * supreme compositionality: combined pieces retain the function of each part
  * exhaustive model of the domain of distributed bounded stream processing

This means that we provide all the tools necessary to express any stream processing topology, that we model all the essential aspects of this domain (back-pressure, buffering, transformations, failure recovery, etc.) and that whatever the user builds is reusable in a larger context.

Resulting Implementation Constraints
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Compositionality entails reusability of partial stream topologies, which led us to the lifted approach of describing data flows as (Partial)FlowGraphs that can act as composite sources, flows and sinks of data. These building blocks shall then be freely shareable, with the ability to combine them freely to form larger flows. The representation of these pieces must therefore be an immutable blueprint that is materialized in an explicit step in order to start the stream processing; the resulting stream processing engine is then also immutable in the sense of having a fixed topology that is prescribed by the blueprint; dynamic networks need to be modeled by explicitly using the Reactive Streams interfaces for plugging different engines together.

The process of materialization may be parameterized, e.g. instantiating a blueprint for handling a TCP connection’s data with specific information about the connection’s address and port information. Additionally, materialization will often create specific objects that are useful to interact with the processing engine once it is running, for example for shutting it down or for extracting metrics. This means that the materialization function takes a set of parameters from the outside and it produces a set of results. Compositionality demands that these two sets cannot interact, because that would establish a covert channel by which different pieces could communicate, leading to problems of initialization order and inscrutable runtime failures.

Another aspect of materialization is that we want to support distributed stream processing, meaning that both the parameters and the results need to be location transparent—either serializable immutable values or ActorRefs. Using for example Futures would restrict materialization to the local JVM. There may be cases for which this will typically not be a severe restriction (like opening a TCP connection), but the principle remains.

What shall users of streaming libraries expect?
-------------------------------------

We expect libraries to be built on top of Akka Streams, in fact Akka HTTP is one such example that lives within the Akka project itself. In order to allow users to profit from the principles that are described for Akka Streams above, the following rules are established:

  * libraries shall provide their users with reusable pieces, allowing full compositionality
  * libraries may optionally and additionally provide facilities that consume and materialize flow descriptions

The reasoning behind the first rule is that compositionality would be destroyed if different libraries only accepted flow descriptions and expected to materialize them: using two of these together would be impossible because materialization can only happen once. As a consequence, the functionality of a library must be expressed such that materialization can be done by the user, outside of the library’s control.

The second rule allows a library to additionally provide nice sugar for the common case, an example of which is the Akka HTTP API that provides a ``handleWith`` method for convenient materialization.

.. note::

  One important consequence of this is that a reusable flow description cannot be bound to “live” resources, any connection to or allocation of such resources must be deferred until materialization time.

Resulting Implementation Constraints
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Akka Streams must enable a library to express any stream processing utility in terms of immutable blueprints. The most common building blocks are

  * Source: something with exactly one output
  * Sink: something with exactly one input
  * Flow: something with exactly one input and one output
  * BidirectionalFlow: something with exactly two inputs and two outputs that behave like two Flows of opposite direction

Other topologies can always be expressed as a combination of a PartialFlowGraph with a set of inputs and a set of outputs. The preferred form of such expression is an object that combines these three elements, favoring object composition over class inheritance.
