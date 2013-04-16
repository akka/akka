.. _io-java:

I/O (Java)
==========

Introduction
------------

The ``akka.io`` package has been developed in collaboration between the Akka
and `spray.io`_ teams. Its design combines experiences from the
``spray-io`` module with improvements that were jointly developed for
more general consumption as an actor-based service.

The guiding design goal for this I/O implementation was to reach extreme
scalability, make no compromises in providing an API correctly matching the
underlying transport mechanism and to be fully event-driven, non-blocking and
asynchronous.  The API is meant to be a solid foundation for the implementation
of network protocols and building higher abstractions; it is not meant to be a
full-service high-level NIO wrapper for end users.

Terminology, Concepts
---------------------
The I/O API is completely actor based, meaning that all operations are implemented with message passing instead of
direct method calls. Every I/O driver (TCP, UDP) has a special actor, called a *manager* that serves
as an entry point for the API. I/O is broken into several drivers. The manager for a particular driver
is accessible by querying an ``ActorSystem``. For example the following code
looks up the TCP manager and returns its ``ActorRef``:

.. includecode:: code/docs/io/japi/EchoManager.java#manager

The manager receives I/O command messages and instantiates worker actors in response. The worker actors present
themselves to the API user in the reply to the command that was sent. For example after a ``Connect`` command sent to
the TCP manager the manager creates an actor representing the TCP connection. All operations related to the given TCP
connections can be invoked by sending messages to the connection actor which announces itself by sending a ``Connected``
message.

DeathWatch and Resource Management
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

I/O worker actors receive commands and also send out events. They usually need a user-side counterpart actor listening
for these events (such events could be inbound connections, incoming bytes or acknowledgements for writes). These worker
actors *watch* their listener counterparts. If the listener stops then the worker will automatically release any
resources that it holds. This design makes the API more robust against resource leaks.
 
Thanks to the completely actor based approach of the I/O API the opposite direction works as well: a user actor
responsible for handling a connection can watch the connection actor to be notified if it unexpectedly terminates.

Write models (Ack, Nack)
^^^^^^^^^^^^^^^^^^^^^^^^

I/O devices have a maximum throughput which limits the frequency and size of writes. When an
application tries to push more data than a device can handle, the driver has to buffer bytes until the device
is able to write them. With buffering it is possible to handle short bursts of intensive writes --- but no buffer is infinite.
"Flow control" is needed to avoid overwhelming device buffers.

Akka supports two types of flow control:

* *Ack-based*, where the driver notifies the writer when writes have succeeded.

* *Nack-based*, where the driver notifies the writer when writes have failed.

Each of these models is available in both the TCP and the UDP implementations of Akka I/O.

Individual writes can be acknowledged by providing an ack object in the write message (``Write`` in the case of TCP and
``Send`` for UDP). When the write is complete the worker will send the ack object to the writing actor. This can be
used to implement *ack-based* flow control; sending new data only when old data has been acknowledged.

If a write (or any other command) fails, the driver notifies the actor that sent the command with a special message
(``CommandFailed`` in the case of UDP and TCP). This message will also notify the writer of a failed write, serving as a
nack for that write. Please note, that in a nack-based flow-control setting the writer has to be prepared for the fact
that the failed write might not be the most recent write it sent. For example, the failure notification for a write
``W1`` might arrive after additional write commands ``W2`` and ``W3`` have been sent. If the writer wants to resend any
nacked messages it may need to keep a buffer of pending messages.

.. warning::
  An acknowledged write does not mean acknowledged delivery or storage; receiving an ack for a write simply signals that
  the I/O driver has successfully processed the write. The Ack/Nack protocol described here is a means of flow control
  not error handling. In other words, data may still be lost, even if every write is acknowledged.

ByteString
^^^^^^^^^^

To maintain isolation, actors should communicate with immutable objects only. ``ByteString`` is an
immutable container for bytes. It is used by Akka's I/O system as an efficient, immutable alternative
the traditional byte containers used for I/O on the JVM, such as ``byte[]`` and ``ByteBuffer``.

``ByteString`` is a `rope-like <http://en.wikipedia.org/wiki/Rope_(computer_science)>`_ data structure that is immutable
and provides fast concatenation and slicing operations (perfect for I/O). When two ``ByteString``\s are concatenated
together they are both stored within the resulting ``ByteString`` instead of copying both to a new array. Operations
such as ``drop`` and ``take`` return ``ByteString``\s that still reference the original array, but just change the
offset and length that is visible. Great care has also been taken to make sure that the internal array cannot be
modified. Whenever a potentially unsafe array is used to create a new ``ByteString`` a defensive copy is created. If
you require a ``ByteString`` that only blocks a much memory as necessary for it's content, use the ``compact`` method to
get a ``CompactByteString`` instance. If the ``ByteString`` represented only a slice of the original array, this will
result in copying all bytes in that slice.

``ByteString`` inherits all methods from ``IndexedSeq``, and it also has some new ones. For more information, look up the ``akka.util.ByteString`` class and it's companion object in the ScalaDoc.

``ByteString`` also comes with its own optimized builder and iterator classes ``ByteStringBuilder`` and
``ByteIterator`` which provide extra features in addition to those of normal builders and iterators.

Compatibility with java.io
..........................

A ``ByteStringBuilder`` can be wrapped in a ``java.io.OutputStream`` via the ``asOutputStream`` method. Likewise, ``ByteIterator`` can we wrapped in a ``java.io.InputStream`` via ``asInputStream``. Using these, ``akka.io`` applications can integrate legacy code based on ``java.io`` streams.

Encoding and decoding binary data
---------------------------------

Akka adopted and adapted the implementation of data processing pipelines found
in the ``spray-io`` module. The idea is that encoding and decoding often
go hand in hand and keeping the code pertaining to one protocol layer together
is deemed more important than writing down the complete read side—say—in the
iteratee style in one go; pipelines encourage packaging the stages in a form
which lends itself better to reuse in a protocol stack. Another reason for
choosing this abstraction is that it is at times necessary to change the
behavior of encoding and decoding within a stage based on a message stream’s
state, and pipeline stages allow communication between the read and write
halves quite naturally.

The actual byte-fiddling can be done within pipeline stages, for example using
the rich API of :class:`ByteIterator` and :class:`ByteStringBuilder` as shown
below. All these activities are synchronous transformations which benefit
greatly from CPU affinity to make good use of those data caches. Therefore the
design of the pipeline infrastructure is completely synchronous, every stage’s
handler code can only directly return the events and/or commands resulting from
an input, there are no callbacks. Exceptions thrown within a pipeline stage
will abort processing of the whole pipeline under the assumption that
recoverable error conditions will be signaled in-band to the next stage instead
of raising an exception.

An overall “logical” pipeline can span multiple execution contexts, for example
starting with the low-level protocol layers directly within an actor handling
the reads and writes to a TCP connection and then being passed to a number of
higher-level actors which do the costly application level processing. This is
supported by feeding the generated events into a sink which sends them to
another actor, and that other actor will then upon reception feed them into its
own pipeline.

Introducing the Sample Protocol
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

In the following the process of implementing a protocol stack using pipelines
is demonstrated on the following simple example:

.. code-block:: text

  frameLen: Int
  persons: Int
  persons times {
    first: String
    last: String
  }
  points: Int
  points times Double

mapping to the following data type:

.. includecode:: code/docs/io/japi/Message.java#message

We will split the handling of this protocol into two parts: the frame-length
encoding handles the buffering necessary on the read side and the actual
encoding of the frame contents is done in a separate stage.

Building a Pipeline Stage
^^^^^^^^^^^^^^^^^^^^^^^^^

As a common example, which is also included in the ``akka-actor`` package, let
us look at a framing protocol which works by prepending a length field to each
message (the following is a simplified version for demonstration purposes, the
real implementation is more configurable and implemented in Scala).

.. includecode:: code/docs/io/japi/LengthFieldFrame.java
   :include: frame

In the end a pipeline stage is nothing more than a set of three methods: one
transforming commands arriving from above, one transforming events arriving
from below and the third transforming incoming management commands (not shown
here, see below for more information). The result of the transformation can in
either case be a sequence of commands flowing downwards or events flowing
upwards (or a combination thereof).

In the case above the data type for commands and events are equal as both
functions operate only on ``ByteString``, and the transformation does not
change that type because it only adds or removes four octets at the front.

The pair of command and event transformation functions is represented by an
object of type :class:`AbstractPipePair`, or in this case a
:class:`AbstractSymmetricPipePair`.  This object could benefit from knowledge
about the context it is running in, for example an :class:`Actor`, and this
context is introduced by making a :class:`PipelineStage` be a factory for
producing a :class:`PipePair`. The factory method is called :meth:`apply` (a
Scala tradition) and receives the context object as its argument. The
implementation of this factory method could now make use of the context in
whatever way it sees fit, you will see an example further down.

Manipulating ByteStrings
^^^^^^^^^^^^^^^^^^^^^^^^

The second stage of our sample protocol stack illustrates in more depth what
showed only a little in the pipeline stage built above: constructing and
deconstructing byte strings. Let us first take a look at the encoder:

.. includecode:: code/docs/io/japi/MessageStage.java
   :include: format
   :exclude: decoding-omitted,omitted

Note how the byte order to be used by this stage is fixed in exactly one place,
making it impossible get wrong between commands and events; the way how the
byte order is passed into the stage demonstrates one possible use for the
stage’s ``context`` parameter. 

The basic tool for constucting a :class:`ByteString` is a
:class:`ByteStringBuilder`. This builder is specialized for concatenating byte
representations of the primitive data types like ``Int`` and ``Double`` or
arrays thereof.  Encoding a ``String`` requires a bit more work because not
only the sequence of bytes needs to be encoded but also the length, otherwise
the decoding stage would not know where the ``String`` terminates. When all
values making up the :class:`Message` have been appended to the builder, we
simply pass the resulting :class:`ByteString` on to the next stage as a command
using the optimized :meth:`singleCommand` facility.

.. warning::

  The :meth:`singleCommand` and :meth:`singleEvent` methods provide a way to
  generate responses which transfer exactly one result from one pipeline stage
  to the next without suffering the overhead of object allocations. This means
  that the returned collection object will not work for anything else (you will
  get :class:`ClassCastExceptions`!) and this facility can only be used *EXACTLY
  ONCE* during the processing of one input (command or event).

Now let us look at the decoder side:

.. includecode:: code/docs/io/japi/MessageStage.java
   :include: decoding

The decoding side does the same things that the encoder does in the same order,
it just uses a :class:`ByteIterator` to retrieve primitive data types or arrays
of those from the underlying :class:`ByteString`. And in the end it hands the
assembled :class:`Message` as an event to the next stage using the optimized
:meth:`singleEvent` facility (see warning above).

Building a Pipeline
^^^^^^^^^^^^^^^^^^^

Given the two pipeline stages introduced in the sections above we can now put
them to some use. First we define some message to be encoded:

.. includecode:: code/docs/io/japi/PipelineTest.java
   :include: message

Then we need to create a pipeline context which satisfies our declared needs:

.. includecode:: code/docs/io/japi/PipelineTest.java
   :include: byteorder

Building the pipeline and encoding this message then is quite simple:

.. includecode:: code/docs/io/japi/PipelineTest.java
   :include: build-sink

First we *sequence* the two stages, i.e. attach them such that the output of
one becomes the input of the other. Then we create a :class:`PipelineSink`
which is essentially a callback interface for what shall happen with the
encoded commands or decoded events, respectively. Then we build the pipeline
using the :class:`PipelineFactory`, which returns an interface for feeding
commands and events into this pipeline instance. As a demonstration of how to
use this, we simply encode the message shown above and the resulting
:class:`ByteString` will then be sent to the ``commandHandler`` actor. Decoding
works in the same way, only using :meth:`injectEvent`.

Injecting into a pipeline using a :class:`PipelineInjector` will catch
exceptions resulting from processing the input, in which case the exception
(there can only be one per injection) is passed into the respective sink. The
default implementation of :meth:`onCommandFailure` and :meth:`onEventFailure`
will re-throw the exception (whence originates the ``throws`` declaration of
the ``inject*`` method).

Using the Pipeline’s Context
^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Up to this point there was always a parameter ``ctx`` which was used when
constructing a pipeline, but it was not explained in full. The context is a
piece of information which is made available to all stages of a pipeline. The
context may also carry behavior, provide infrastructure or helper methods etc.
It should be noted that the context is bound to the pipeline and as such must
not be accessed concurrently from different threads unless care is taken to
properly synchronize such access. Since the context will in many cases be
provided by an actor it is not recommended to share this context with code
executing outside of the actor’s message handling.

.. warning::

  A PipelineContext instance *MUST NOT* be used by two different pipelines
  since it contains mutable fields which are used during message processing.

Using Management Commands
^^^^^^^^^^^^^^^^^^^^^^^^^

Since pipeline stages do not have any reference to the pipeline or even to
their neighbors they cannot directly effect the injection of commands or events
outside of their normal processing. But sometimes things need to happen driven
by a timer, for example. In this case the timer would need to cause sending
tick messages to the whole pipeline, and those stages which wanted to receive
them would act upon those. In order to keep the type signatures for events and
commands useful, such external triggers are sent out-of-band, via a different
channel—the management port. One example which makes use of this facility is
the :class:`TickGenerator` which comes included with ``akka-actor`` (this is a
transcription of the Scala version which is actually included in the
``akka-actor`` JAR):

.. includecode:: code/docs/io/japi/HasActorContext.java#actor-context

.. includecode:: code/docs/io/japi/TickGenerator.java#tick-generator

This pipeline stage is to be used within an actor, and it will make use of this
context in order to schedule the delivery of ``Tick`` messages; the actor is
then supposed to feed these messages into the management port of the pipeline.
An example could look like this:

.. includecode:: code/docs/io/japi/Processor.java
   :include: actor
   :exclude: omitted

This actor extends our well-known pipeline with the tick generator and attaches
the outputs to functions which send commands and events to actors for further
processing. The pipeline stages will then all receive on ``Tick`` per second
which can be used like so:

.. includecode:: code/docs/io/japi/MessageStage.java
   :include: mgmt-ticks
   :exclude: omitted

.. note::

  Management commands are delivered to all stages of a pipeline “effectively
  parallel”, like on a broadcast medium. No code will actually run concurrently
  since a pipeline is strictly single-threaded, but the order in which these
  commands are processed is not specified.

The intended purpose of management commands is for each stage to define its
special command types and then listen only to those (where the aforementioned
``Tick`` message is a useful counter-example), exactly like sending packets on
a wifi network where every station receives all traffic but reacts only to
those messages which are destined for it.

If you need all stages to react upon something in their defined order, then
this must be modeled either as a command or event, i.e. it will be part of the
“business” type of the pipeline.

Using TCP
---------

The code snippets through-out this section assume the following imports:

.. includecode:: code/docs/io/japi/IODocTest.java#imports

All of the Akka I/O APIs are accessed through manager objects. When using an I/O API, the first step is to acquire a
reference to the appropriate manager. The code below shows how to acquire a reference to the ``Tcp`` manager.

.. includecode:: code/docs/io/japi/EchoManager.java#manager

The manager is an actor that handles the underlying low level I/O resources (selectors, channels) and instantiates
workers for specific tasks, such as listening to incoming connections.

Connecting
^^^^^^^^^^

.. includecode:: code/docs/io/japi/IODocTest.java#client

The first step of connecting to a remote address is sending a :class:`Connect`
message to the TCP manager; in addition to the simplest form shown above there
is also the possibility to specify a local :class:`InetSocketAddress` to bind
to and a list of socket options to apply.

.. note::

  The SO_NODELAY (TCP_NODELAY on Windows) socket option defaults to true in
  Akka, independently of the OS default settings. This setting disables Nagle's
  algorithm, considerably improving latency for most applications. This setting
  could be overridden by passing ``SO.TcpNoDelay(false)`` in the list of socket
  options of the ``Connect`` message.

The TCP manager will then reply either with a :class:`CommandFailed` or it will
spawn an internal actor representing the new connection. This new actor will
then send a :class:`Connected` message to the original sender of the
:class:`Connect` message.

In order to activate the new connection a :class:`Register` message must be
sent to the connection actor, informing that one about who shall receive data
from the socket. Before this step is done the connection cannot be used, and
there is an internal timeout after which the connection actor will shut itself
down if no :class:`Register` message is received.

The connection actor watches the registered handler and closes the connection
when that one terminates, thereby cleaning up all internal resources associated
with that connection.

The actor in the example above uses :meth:`become` to switch from unconnected
to connected operation, demonstrating the commands and events which are
observed in that state. For a discussion on :class:`CommandFailed` see
`Throttling Reads and Writes`_ below. :class:`ConnectionClosed` is a trait,
which marks the different connection close events. The last line handles all
connection close events in the same way. It is possible to listen for more
fine-grained connection close events, see `Closing Connections`_ below.

Accepting connections
^^^^^^^^^^^^^^^^^^^^^

.. includecode:: code/docs/io/japi/IODocTest.java#server

To create a TCP server and listen for inbound connections, a :class:`Bind`
command has to be sent to the TCP manager.  This will instruct the TCP manager
to listen for TCP connections on a particular :class:`InetSocketAddress`; the
port may be specified as ``0`` in order to bind to a random port.

The actor sending the :class:`Bind` message will receive a :class:`Bound`
message signalling that the server is ready to accept incoming connections;
this message also contains the :class:`InetSocketAddress` to which the socket
was actually bound (i.e. resolved IP address and correct port number). 

From this point forward the process of handling connections is the same as for
outgoing connections. The example demonstrates that handling the reads from a
certain connection can be delegated to another actor by naming it as the
handler when sending the :class:`Register` message. Writes can be sent from any
actor in the system to the connection actor (i.e. the actor which sent the
:class:`Connected` message). The simplistic handler is defined as:

.. includecode:: code/docs/io/japi/IODocTest.java#simplistic-handler

For a more complete sample which also takes into account the possibility of
failures when sending please see `Throttling Reads and Writes`_ below.

The only difference to outgoing connections is that the internal actor managing
the listen port—the sender of the :class:`Bound` message—watches the actor
which was named as the recipient for :class:`Connected` messages in the
:class:`Bind` message. When that actor terminates the listen port will be
closed and all resources associated with it will be released; existing
connections will not be terminated at this point.

Closing connections
^^^^^^^^^^^^^^^^^^^

A connection can be closed by sending one of the commands ``Close``, ``ConfirmedClose`` or ``Abort`` to the connection
actor.

``Close`` will close the connection by sending a ``FIN`` message, but without waiting for confirmation from
the remote endpoint. Pending writes will be flushed. If the close is successful, the listener will be notified with
``Closed``.

``ConfirmedClose`` will close the sending direction of the connection by sending a ``FIN`` message, but data 
will continue to be received until the remote endpoint closes the connection, too. Pending writes will be flushed. If the close is
successful, the listener will be notified with ``ConfirmedClosed``.

``Abort`` will immediately terminate the connection by sending a ``RST`` message to the remote endpoint. Pending
writes will be not flushed. If the close is successful, the listener will be notified with ``Aborted``.

``PeerClosed`` will be sent to the listener if the connection has been closed by the remote endpoint. Per default, the
connection will then automatically be closed from this endpoint as well. To support half-closed connections set the
``keepOpenOnPeerClosed`` member of the ``Register`` message to ``true`` in which case the connection stays open until
it receives one of the above close commands.

``ErrorClosed`` will be sent to the listener whenever an error happened that forced the connection to be closed.

All close notifications are sub-types of ``ConnectionClosed`` so listeners who do not need fine-grained close events
may handle all close events in the same way.

Throttling Reads and Writes
^^^^^^^^^^^^^^^^^^^^^^^^^^^

The basic model of the TCP connection actor is that it has no internal
buffering (i.e. it can only process one write at a time, meaning it can buffer
one write until it has been passed on to the O/S kernel in full). Congestion
needs to be handled at the user level, for which there are three modes of
operation:

* *ACK-based:* every :class:`Write` command carries an arbitrary object, and if
  this object is not ``Tcp.NoAck`` then it will be returned to the sender of
  the :class:`Write` upon successfully writing all contained data to the
  socket. If no other write is initiated before having received this
  acknowledgement then no failures can happen due to buffer overrun.

* *NACK-based:* every write which arrives while a previous write is not yet
  completed will be replied to with a :class:`CommandFailed` message containing
  the failed write. Just relying on this mechanism requires the implemented
  protocol to tolerate skipping writes (e.g. if each write is a valid message
  on its own and it is not required that all are delivered). This mode is
  enabled by setting the ``useResumeWriting`` flag to ``false`` within the
  :class:`Register` message during connection activation.

* *NACK-based with write suspending:* this mode is very similar to the
  NACK-based one, but once a single write has failed no further writes will
  succeed until a :class:`ResumeWriting` message is received. This message will
  be answered with a :class:`WritingResumed` message once the last accepted
  write has completed. If the actor driving the connection implements buffering
  and resends the NACK’ed messages after having awaited the
  :class:`WritingResumed` signal then every message is delivered exactly once
  to the network socket.

These models (with the exception of the second which is rather specialised) are
demonstrated in complete examples below. The full and contiguous source is
available `on github <@github@/akka-docs/rst/java/code/io/japi>`_.

.. note::

   It should be obvious that all these flow control schemes only work between
   one writer and one connection actor; as soon as multiple actors send write
   commands to a single connection no consistent result can be achieved.

ACK-Based Back-Pressure
^^^^^^^^^^^^^^^^^^^^^^^

For proper function of the following example it is important to configure the
connection to remain half-open when the remote side closed its writing end:
this allows the example :class:`EchoHandler` to write all outstanding data back
to the client before fully closing the connection. This is enabled using a flag
upon connection activation (observe the :class:`Register` message):

.. includecode:: code/docs/io/japi/EchoManager.java#echo-manager

With this preparation let us dive into the handler itself:

.. includecode:: code/docs/io/japi/SimpleEchoHandler.java#simple-echo-handler
   :exclude: storage-omitted

The principle is simple: when having written a chunk always wait for the
``Ack`` to come back before sending the next chunk. While waiting we switch
behavior such that new incoming data are buffered. The helper functions used
are a bit lengthy but not complicated:

.. includecode:: code/docs/io/japi/SimpleEchoHandler.java#simple-helpers

The most interesting part is probably the last: an ``Ack`` removes the oldest
data chunk from the buffer, and if that was the last chunk then we either close
the connection (if the peer closed its half already) or return to the idle
behavior; otherwise we just send the next buffered chunk and stay waiting for
the next ``Ack``.

Back-pressure can be propagated also across the reading side back to the writer
on the other end of the connection by sending the :class:`SuspendReading`
command to the connection actor. This will lead to no data being read from the
socket anymore (although this does happen after a delay because it takes some
time until the connection actor processes this command, hence appropriate
head-room in the buffer should be present), which in turn will lead to the O/S
kernel buffer filling up on our end, then the TCP window mechanism will stop
the remote side from writing, filling up its write buffer, until finally the
writer on the other side cannot push any data into the socket anymore. This is
how end-to-end back-pressure is realized across a TCP connection.

NACK-Based Back-Pressure with Write Suspending
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. includecode:: code/docs/io/japi/EchoHandler.java#echo-handler
   :exclude: buffering,closing,storage-omitted

The principle here is to keep writing until a :class:`CommandFailed` is
received, using acknowledgements only to prune the resend buffer. When a such a
failure was received, transition into a different state for handling and handle
resending of all queued data:

.. includecode:: code/docs/io/japi/EchoHandler.java#buffering

It should be noted that all writes which are currently buffered have also been
sent to the connection actor upon entering this state, which means that the
:class:`ResumeWriting` message is enqueued after those writes, leading to the
reception of all outstanding :class:`CommandFailre` messages (which are ignored
in this state) before receiving the :class:`WritingResumed` signal. That latter
message is sent by the connection actor only once the internally queued write
has been fully completed, meaning that a subsequent write will not fail. This
is exploited by the :class:`EchoHandler` to switch to an ACK-based approach for
the first ten writes after a failure before resuming the optimistic
write-through behavior.

.. includecode:: code/docs/io/japi/EchoHandler.java#closing

Closing the connection while still sending all data is a bit more involved than
in the ACK-based approach: the idea is to always send all outstanding messages
and acknowledge all successful writes, and if a failure happens then switch
behavior to await the :class:`WritingResumed` event and start over.

The helper functions are very similar to the ACK-based case:

.. includecode:: code/docs/io/japi/EchoHandler.java#helpers


Using UDP
---------

UDP support comes in two flavors: connectionless and connection-based. With connectionless UDP, workers can send datagrams
to any remote address. Connection-based UDP workers are linked to a single remote address.

The connectionless UDP manager is accessed through ``Udp``. ``Udp`` refers to the "fire-and-forget" style of sending
UDP datagrams.
 
.. includecode:: code/docs/io/UdpDocTest.java#manager

The connection-based UDP manager is accessed through ``UdpConnected``.

.. includecode:: code/docs/io/UdpConnectedDocTest.java#manager

UDP servers can be only implemented by the connectionless API, but clients can use both.

Connectionless UDP
^^^^^^^^^^^^^^^^^^

The following imports are assumed in the following sections:

.. includecode:: code/docs/io/UdpDocTest.java#imports

Simple Send
............

To simply send a UDP datagram without listening to an answer one needs to send the ``SimpleSender`` command to the
``Udp`` manager:

.. includecode:: code/docs/io/UdpDocTest.java#simplesend

The manager will create a worker for sending, and the worker will reply with a ``SimpleSendReady`` message:

.. includecode:: code/docs/io/UdpDocTest.java#simplesend-finish

After saving the sender of the ``SimpleSendReady`` message it is possible to send out UDP datagrams with a simple
message send:

.. includecode:: code/docs/io/UdpDocTest.java#simplesend-send


Bind (and Send)
...............

To listen for UDP datagrams arriving on a given port, the ``Bind`` command has to be sent to the connectionless UDP
manager

.. includecode:: code/docs/io/UdpDocTest.java#bind

After the bind succeeds, the sender of the ``Bind`` command will be notified with a ``Bound`` message. The sender of
this message is the worker for the UDP channel bound to the local address.

.. includecode:: code/docs/io/UdpDocTest.java#bind-finish

The actor passed in the ``handler`` parameter will receive inbound UDP datagrams sent to the bound address:

.. includecode:: code/docs/io/UdpDocTest.java#bind-receive

The ``Received`` message contains the payload of the datagram and the address of the sender.

It is also possible to send UDP datagrams using the ``ActorRef`` of the worker:

.. includecode:: code/docs/io/UdpDocTest.java#bind-send


.. note::
  The difference between using a bound UDP worker to send instead of a simple-send worker is that in the former case
  the sender field of the UDP datagram will be the bound local address, while in the latter it will be an undetermined
  ephemeral port.

Connection based UDP
^^^^^^^^^^^^^^^^^^^^

The service provided by the connection based UDP API is similar to the bind-and-send service we saw earlier, but
the main difference is that a connection is only able to send to the ``remoteAddress`` it was connected to, and will
receive datagrams only from that address.

Connecting is similar to what we have seen in the previous section:

.. includecode:: code/docs/io/UdpConnectedDocTest.java#connect

Or, with more options:

.. includecode:: code/docs/io/UdpConnectedDocTest.java#connect-with-options

After the connect succeeds, the sender of the ``Connect`` command will be notified with a ``Connected`` message. The sender of
this message is the worker for the UDP connection.

.. includecode:: code/docs/io/UdpConnectedDocTest.java#connected

The actor passed in the ``handler`` parameter will receive inbound UDP datagrams sent to the bound address:

.. includecode:: code/docs/io/UdpConnectedDocTest.java#received

The ``Received`` message contains the payload of the datagram but unlike in the connectionless case, no sender address
is provided, as a UDP connection only receives messages from the endpoint it has been connected to.

It is also possible to send UDP datagrams using the ``ActorRef`` of the worker:

.. includecode:: code/docs/io/UdpConnectedDocTest.java#send

Again, like the ``Received`` message, the ``Send`` message does not contain a remote address. This is because the address
will always be the endpoint we originally connected to.

.. note::
  There is a small performance benefit in using connection based UDP API over the connectionless one.
  If there is a SecurityManager enabled on the system, every connectionless message send has to go through a security
  check, while in the case of connection-based UDP the security check is cached after connect, thus writes does
  not suffer an additional performance penalty.

Architecture in-depth
---------------------

For further details on the design and internal architecture see :ref:`io-layer`.

.. _spray.io: http://spray.io
