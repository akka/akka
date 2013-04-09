.. _io-scala:

I/O (Scala)
===========

Introduction
------------

The ``akka.io`` package has been developed in collaboration between the Akka
and `spray.io`_ teams. Its design combines experiences from the
``spray-io`` module with improvements that were jointly developed for
more general consumption as an actor-based service.

This documentation is in progress and some sections may be incomplete. More will be coming.

.. note::
  The old I/O implementation has been deprecated and its documentation has been moved: :ref:`io-scala-old`

Terminology, Concepts
---------------------
The I/O API is completely actor based, meaning that all operations are implemented with message passing instead of
direct method calls. Every I/O driver (TCP, UDP) has a special actor, called a *manager* that serves
as an entry point for the API. I/O is broken into several drivers. The manager for a particular driver
is accessible through the ``IO`` entry point. For example the following code
looks up the TCP manager and returns its ``ActorRef``:

.. code-block:: scala

  val tcpManager = IO(Tcp)

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
the traditional byte containers used for I/O on the JVM, such as ``Array[Byte]`` and ``ByteBuffer``.

``ByteString`` is a `rope-like <http://en.wikipedia.org/wiki/Rope_(computer_science)>`_ data structure that is immutable
and provides fast concatenation and slicing operations (perfect for I/O). When two ``ByteString``\s are concatenated
together they are both stored within the resulting ``ByteString`` instead of copying both to a new ``Array``. Operations
such as ``drop`` and ``take`` return ``ByteString``\s that still reference the original ``Array``, but just change the
offset and length that is visible. Great care has also been taken to make sure that the internal ``Array`` cannot be
modified. Whenever a potentially unsafe ``Array`` is used to create a new ``ByteString`` a defensive copy is created. If
you require a ``ByteString`` that only blocks as much memory as necessary for it's content, use the ``compact`` method to
get a ``CompactByteString`` instance. If the ``ByteString`` represented only a slice of the original array, this will
result in copying all bytes in that slice.

``ByteString`` inherits all methods from ``IndexedSeq``, and it also has some new ones. For more information, look up the ``akka.util.ByteString`` class and it's companion object in the ScalaDoc.

``ByteString`` also comes with its own optimized builder and iterator classes ``ByteStringBuilder`` and
``ByteIterator`` which provide extra features in addition to those of normal builders and iterators.

Compatibility with java.io
..........................

A ``ByteStringBuilder`` can be wrapped in a ``java.io.OutputStream`` via the ``asOutputStream`` method. Likewise, ``ByteIterator`` can be wrapped in a ``java.io.InputStream`` via ``asInputStream``. Using these, ``akka.io`` applications can integrate legacy code based on ``java.io`` streams.

Encoding and decoding binary data
---------------------------------

.. note::

  Previously Akka offered a specialized Iteratee implementation in the
  ``akka.actor.IO`` object which is now deprecated in favor of the pipeline
  mechanism described here. The documentation for Iteratees can be found `here
  <http://doc.akka.io/doc/akka/2.1.2/scala/io.html#Encoding_and_decoding_binary_data>`_.

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

.. includecode:: code/docs/io/Pipelines.scala#data

We will split the handling of this protocol into two parts: the frame-length
encoding handles the buffering necessary on the read side and the actual
encoding of the frame contents is done in a separate stage.

Building a Pipeline Stage
^^^^^^^^^^^^^^^^^^^^^^^^^

As a common example, which is also included in the ``akka-actor`` package, let
us look at a framing protocol which works by prepending a length field to each
message.

.. includecode:: ../../../akka-actor/src/main/scala/akka/io/Pipelines.scala
   :include: length-field-frame
   :exclude: range-checks-omitted

In the end a pipeline stage is nothing more than a set of three functions: one
transforming commands arriving from above, one transforming events arriving
from below and the third transforming incoming management commands (not shown
here, see below for more information). The result of the transformation can in
either case be a sequence of commands flowing downwards or events flowing
upwards (or a combination thereof).

In the case above the data type for commands and events are equal as both
functions operate only on ``ByteString``, and the transformation does not
change that type because it only adds or removes four octets at the front.

The pair of command and event transformation functions is represented by an
object of type :class:`PipePair`, or in this case a :class:`SymmetricPipePair`.
This object could benefit from knowledge about the context it is running in,
for example an :class:`Actor`, and this context is introduced by making a
:class:`PipelineStage` be a factory for producing a :class:`PipePair`. The
factory method is called :meth:`apply` (in good Scala tradition) and receives
the context object as its argument. The implementation of this factory method
could now make use of the context in whatever way it sees fit, you will see an
example further down.

Manipulating ByteStrings
^^^^^^^^^^^^^^^^^^^^^^^^

The second stage of our sample protocol stack illustrates in more depth what
showed only a little in the pipeline stage built above: constructing and
deconstructing byte strings. Let us first take a look at the encoder:

.. includecode:: code/docs/io/Pipelines.scala
   :include: format
   :exclude: decoding-omitted,omitted

Note how the byte order to be used by this stage is fixed in exactly one place,
making it impossible get wrong between commands and events; the way how the
byte order is passed into the stage demonstrates one possible use for the
stage’s ``context`` parameter. 

The basic tool for constucting a :class:`ByteString` is a
:class:`ByteStringBuilder` which can be obtained by calling
:meth:`ByteString.newBuilder` since byte strings implement the
:class:`IndexesSeq[Byte]` interface of the standard Scala collections. This
builder knows a few extra tricks, though, for appending byte representations of
the primitive data types like ``Int`` and ``Double`` or arrays thereof.
Encoding a ``String`` requires a bit more work because not only the sequence of
bytes needs to be encoded but also the length, otherwise the decoding stage
would not know where the ``String`` terminates. When all values making up the
:class:`Message` have been appended to the builder, we simply pass the
resulting :class:`ByteString` on to the next stage as a command using the
optimized :meth:`singleCommand` facility.

.. warning::

  The :meth:`singleCommand` and :meth:`singleEvent` methods provide a way to
  generate responses which transfer exactly one result from one pipeline stage
  to the next without suffering the overhead of object allocations. This means
  that the returned collection object will not work for anything else (you will
  get :class:`ClassCastExceptions`!) and this facility can only be used *EXACTLY
  ONCE* during the processing of one input (command or event).

Now let us look at the decoder side:

.. includecode:: code/docs/io/Pipelines.scala
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

.. includecode:: code/docs/io/Pipelines.scala
   :include: message

Then we need to create a pipeline context which satisfies our declared needs:

.. includecode:: code/docs/io/Pipelines.scala
   :include: byteorder

Building the pipeline and encoding this message then is quite simple:

.. includecode:: code/docs/io/Pipelines.scala
   :include: build-pipeline

The tuple returned from :meth:`buildFunctionTriple` contains one function for
injecting commands, one for events and a third for injecting management
commands (see below). In this case we demonstrate how a single message ``msg``
is encoded by passing it into the ``cmd`` function. The return value is a pair
of sequences, one for the resulting events and the other for the resulting
commands. For the sample pipeline this will contain exactly one command—one
:class:`ByteString`. Decoding works in the same way, only with the ``evt``
function (which can again also result in commands being generated, although
that is not demonstrated in this sample).

Besides the more functional style there is also an explicitly side-effecting one:

.. includecode:: code/docs/io/Pipelines.scala
   :include: build-sink

The functions passed into the :meth:`buildWithSinkFunctions` factory method
describe what shall happen to the commands and events as they fall out of the
pipeline. In this case we just send those to some actors, since that is usually
quite a good strategy for distributing the work represented by the messages.

The types of commands or events fed into the provided sink functions are
wrapped within :class:`Try` so that failures can also be encoded and acted
upon. This means that injecting into a pipeline using a
:class:`PipelineInjector` will catch exceptions resulting from processing the
input, in which case the exception (there can only be one per injection) is
passed into the respective sink.

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
the :class:`TickGenerator` which comes included with ``akka-actor``:

.. includecode:: ../../../akka-actor/src/main/scala/akka/io/Pipelines.scala
   :include: tick-generator

This pipeline stage is to be used within an actor, and it will make use of this
context in order to schedule the delivery of :class:`TickGenerator.Trigger`
messages; the actor is then supposed to feed these messages into the management
port of the pipeline.  An example could look like this:

.. includecode:: code/docs/io/Pipelines.scala#actor

This actor extends our well-known pipeline with the tick generator and attaches
the outputs to functions which send commands and events to actors for further
processing. The pipeline stages will then all receive one ``Tick`` per second
which can be used like so:

.. includecode:: code/docs/io/Pipelines.scala
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

All of the Akka I/O APIs are accessed through manager objects. When using an I/O API, the first step is to acquire a
reference to the appropriate manager. The code below shows how to acquire a reference to the ``Tcp`` manager.

.. code-block:: scala

  import akka.io.IO
  import akka.io.Tcp
  val tcpManager = IO(Tcp)

The manager is an actor that handles the underlying low level I/O resources (selectors, channels) and instantiates
workers for specific tasks, such as listening to incoming connections.

.. _connecting-scala:

Connecting
^^^^^^^^^^

The first step of connecting to a remote address is sending a ``Connect`` message to the TCP manager:

.. code-block:: scala

  import akka.io.Tcp._
  IO(Tcp) ! Connect(remoteSocketAddress)

When connecting, it is also possible to set various socket options or specify a local address:

.. code-block:: scala

  IO(Tcp) ! Connect(remoteSocketAddress, Some(localSocketAddress), List(SO.KeepAlive(true)))

.. note::
  The SO_NODELAY (TCP_NODELAY on Windows) socket option defaults to true in Akka, independently of the OS default
  settings. This setting disables Nagle's algorithm considerably improving latency for most applications. This setting
  could be overridden by passing ``SO.TcpNoDelay(false)`` in the list of socket options of the ``Connect`` message.

After issuing the ``Connect`` command the TCP manager spawns a worker actor to handle commands related to the
connection. This worker actor will reveal itself by replying with a ``Connected`` message to the actor who sent the
``Connect`` command.

.. code-block:: scala

  case Connected(remoteAddress, localAddress) =>
    connectionActor = sender

At this point, there is still no listener associated with the connection. To finish the connection setup a ``Register``
has to be sent to the connection actor with the listener ``ActorRef`` as a parameter.

.. code-block:: scala

  connectionActor ! Register(listener)

Upon registration, the connection actor will watch the listener actor provided in the ``listener`` parameter.
If the listener actor stops, the connection is closed, and all resources allocated for the connection released. During the
lifetime of the connection the listener may receive various event notifications:

.. code-block:: scala

  case Received(dataByteString) => // handle incoming chunk of data
  case CommandFailed(cmd)       => // handle failure of command: cmd
  case _: ConnectionClosed      => // handle closed connections

``ConnectionClosed`` is a trait, which the different connection close events all implement.
The last line handles all connection close events in the same way. It is possible to listen for more fine-grained
connection close events, see :ref:`closing-connections-scala` below.


Accepting connections
^^^^^^^^^^^^^^^^^^^^^

To create a TCP server and listen for inbound connections, a ``Bind`` command has to be sent to the TCP manager.
This will instruct the TCP manager to listen for TCP connections on a particular address.

.. code-block:: scala

  import akka.io.IO
  import akka.io.Tcp
  IO(Tcp) ! Bind(handler, localAddress)

The actor sending the ``Bind`` message will receive a ``Bound`` message signalling that the server is ready to accept
incoming connections. The process for accepting connections is similar to the process for making :ref:`outgoing
connections <connecting-scala>`: when an incoming connection is established, the actor provided as ``handler`` will
receive a ``Connected`` message whose sender is the connection actor.

.. code-block:: scala

  case Connected(remoteAddress, localAddress) =>
    connectionActor = sender

At this point, there is still no listener associated with the connection. To finish the connection setup a ``Register``
has to be sent to the connection actor with the listener ``ActorRef`` as a parameter.

.. code-block:: scala

  connectionActor ! Register(listener)

Upon registration, the connection actor will watch the listener actor provided in the ``listener`` parameter.
If the listener stops, the connection is closed, and all resources allocated for the connection are released. During the
connection lifetime the listener will receive various event notifications in the same way as in the outbound
connection case.

.. _closing-connections-scala:

Closing connections
^^^^^^^^^^^^^^^^^^^

A connection can be closed by sending one of the commands ``Close``, ``ConfirmedClose`` or ``Abort`` to the connection
actor.

``Close`` will close the connection by sending a ``FIN`` message, but without waiting for confirmation from
the remote endpoint. Pending writes will be flushed. If the close is successful, the listener will be notified with
``Closed``.

``ConfirmedClose`` will close the sending direction of the connection by sending a ``FIN`` message, but receives
will continue until the remote endpoint closes the connection, too. Pending writes will be flushed. If the close is
successful, the listener will be notified with ``ConfirmedClosed``.

``Abort`` will immediately terminate the connection by sending a ``RST`` message to the remote endpoint. Pending
writes will be not flushed. If the close is successful, the listener will be notified with ``Aborted``.

``PeerClosed`` will be sent to the listener if the connection has been closed by the remote endpoint.

``ErrorClosed`` will be sent to the listener whenever an error happened that forced the connection to be closed.

All close notifications are subclasses of ``ConnectionClosed`` so listeners who do not need fine-grained close events
may handle all close events in the same way.

Throttling Reads and Writes
^^^^^^^^^^^^^^^^^^^^^^^^^^^

*This section is not yet ready. More coming soon*

Using UDP
---------

UDP support comes in two flavors: connectionless and connection-based. With connectionless UDP, workers can send datagrams
to any remote address. Connection-based UDP workers are linked to a single remote address.

The connectionless UDP manager is accessed through ``Udp``. ``Udp`` refers to the "fire-and-forget" style of sending
UDP datagrams.

.. code-block:: scala

  import akka.io.IO
  import akka.io.Udp
  val connectionLessUdp = IO(Udp)

The connection-based UDP manager is accessed through ``UdpConnected``.

.. code-block:: scala

  import akka.io.UdpConnected
  val connectionBasedUdp = IO(UdpConnected)

UDP servers can be only implemented by the connectionless API, but clients can use both.

Connectionless UDP
^^^^^^^^^^^^^^^^^^

Simple Send
............

To simply send a UDP datagram without listening to an answer one needs to send the ``SimpleSender`` command to the
``Udp`` manager:

.. code-block:: scala

  IO(Udp) ! SimpleSender
  // or with socket options:
  import akka.io.Udp._
  IO(Udp) ! SimpleSender(List(SO.Broadcast(true)))

The manager will create a worker for sending, and the worker will reply with a ``SimpleSendReady`` message:

.. code-block:: scala

  case SimpleSendReady =>
    simpleSender = sender

After saving the sender of the ``SimpleSendReady`` message it is possible to send out UDP datagrams with a simple
message send:

.. code-block:: scala

  simpleSender ! Send(data, serverAddress)


Bind (and Send)
...............

To listen for UDP datagrams arriving on a given port, the ``Bind`` command has to be sent to the connectionless UDP
manager

.. code-block:: scala

  IO(Udp) ! Bind(handler, localAddress)

After the bind succeeds, the sender of the ``Bind`` command will be notified with a ``Bound`` message. The sender of
this message is the worker for the UDP channel bound to the local address.

.. code-block:: scala

  case Bound =>
    udpWorker = sender // Save the worker ref for later use

The actor passed in the ``handler`` parameter will receive inbound UDP datagrams sent to the bound address:

.. code-block:: scala

  case Received(dataByteString, remoteAddress) => // Do something with the data

The ``Received`` message contains the payload of the datagram and the address of the sender.

It is also possible to send UDP datagrams using the ``ActorRef`` of the worker saved in ``udpWorker``:

.. code-block:: scala

 udpWorker ! Send(data, serverAddress)

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

.. code-block:: scala

  IO(UdpConnected) ! Connect(handler, remoteAddress)

Or, with more options:

.. code-block:: scala

  IO(UdpConnected) ! Connect(handler, Some(localAddress), remoteAddress, List(SO.Broadcast(true)))

After the connect succeeds, the sender of the ``Connect`` command will be notified with a ``Connected`` message. The sender of
this message is the worker for the UDP connection.

.. code-block:: scala

  case Connected =>
    udpConnectionActor = sender // Save the worker ref for later use

The actor passed in the ``handler`` parameter will receive inbound UDP datagrams sent to the bound address:

.. code-block:: scala

  case Received(dataByteString) => // Do something with the data

The ``Received`` message contains the payload of the datagram but unlike in the connectionless case, no sender address
is provided, as a UDP connection only receives messages from the endpoint it has been connected to.

UDP datagrams can be sent by sending a ``Send`` message to the worker actor.

.. code-block:: scala

 udpConnectionActor ! Send(data)

Again, like the ``Received`` message, the ``Send`` message does not contain a remote address. This is because the address
will always be the endpoint we originally connected to.

.. note::
  There is a small performance benefit in using connection based UDP API over the connectionless one.
  If there is a SecurityManager enabled on the system, every connectionless message send has to go through a security
  check, while in the case of connection-based UDP the security check is cached after connect, thus writes do
  not suffer an additional performance penalty.

Throttling Reads and Writes
^^^^^^^^^^^^^^^^^^^^^^^^^^^

*This section is not yet ready. More coming soon*


Architecture in-depth
---------------------

For further details on the design and internal architecture see :ref:`io-layer`.

.. _spray.io: http://spray.io

Link to the old IO documentation
--------------------------------

.. This is only in here to avoid a warning about io-old not being part of any toctree.

.. toctree::
   :maxdepth: 1

   io-old

