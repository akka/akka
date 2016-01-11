.. _stream-introduction-scala:

############
Introduction
############

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
`Reactive Streams`_ initiative of which Akka is a
founding member. For you this means that the hard problem of propagating and
reacting to back-pressure has been incorporated in the design of Akka Streams
already, so you have one less thing to worry about; it also means that Akka
Streams interoperate seamlessly with all other Reactive Streams implementations
(where Reactive Streams interfaces define the interoperability SPI while
implementations like Akka Streams offer a nice user API).

.. _Reactive Streams: http://reactive-streams.org/

Relationship with Reactive Streams
----------------------------------

The Akka Streams API is completely decoupled from the Reactive Streams
interfaces. While Akka Streams focus on the formulation of transformations on
data streams the scope of Reactive Streams is just to define a common mechanism
of how to move data across an asynchronous boundary without losses, buffering
or resource exhaustion.

The relationship between these two is that the Akka Streams API is geared
towards end-users while the Akka Streams implementation uses the Reactive
Streams interfaces internally to pass data between the different processing
stages. For this reason you will not find any resemblance between the Reactive
Streams interfaces and the Akka Streams API. This is in line with the
expectations of the Reactive Streams project, whose primary purpose is to
define interfaces such that different streaming implementation can
interoperate; it is not the purpose of Reactive Streams to describe an end-user
API.

How to read these docs
======================

Stream processing is a different paradigm to the Actor Model or to Future
composition, therefore it may take some careful study of this subject until you
feel familiar with the tools and techniques. The documentation is here to help
and for best results we recommend the following approach:

* Read the :ref:`stream-quickstart-scala` to get a feel for how streams
  look like and what they can do.
* The top-down learners may want to peruse the :ref:`stream-design` at this
  point.
* The bottom-up learners may feel more at home rummaging through the
  :ref:`stream-cookbook-scala`.
* For a complete overview of the built-in processing stages you can look at the
  table in :ref:`stages-overview`
* The other sections can be read sequentially or as needed during the previous
  steps, each digging deeper into specific topics.

