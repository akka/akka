.. _stream-introduction-java:

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

How to read these docs
======================

Stream processing is a different paradigm to the Actor Model or to Future
composition, therefore it may take some careful study of this subject until you
feel familiar with the tools and techniques. The documentation is here to help
and for best results we recommend the following approach:

* Read the :ref:`quickstart-java` to get a feel for how streams
  look like and what they can do.
* The top-down learners may want to peruse the :ref:`stream-design` at this
  point.
* The bottom-up learners may feel more at home rummaging through the
  :ref:`stream-cookbook-java`.
* The other sections can be read sequentially or as needed during the previous
  steps, each digging deeper into specific topics.

