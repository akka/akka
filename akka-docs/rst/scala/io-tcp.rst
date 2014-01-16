.. _io-scala-tcp:

Using TCP
=========

The code snippets through-out this section assume the following imports:

.. includecode:: code/docs/io/IODocSpec.scala#imports

All of the Akka I/O APIs are accessed through manager objects. When using an I/O API, the first step is to acquire a
reference to the appropriate manager. The code below shows how to acquire a reference to the ``Tcp`` manager.

.. includecode:: code/docs/io/IODocSpec.scala#manager

The manager is an actor that handles the underlying low level I/O resources (selectors, channels) and instantiates
workers for specific tasks, such as listening to incoming connections.

Connecting
----------

.. includecode:: code/docs/io/IODocSpec.scala#client

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
---------------------

.. includecode:: code/docs/io/IODocSpec.scala#server
   :exclude: do-some-logging-or-setup

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

.. includecode:: code/docs/io/IODocSpec.scala#simplistic-handler

For a more complete sample which also takes into account the possibility of
failures when sending please see `Throttling Reads and Writes`_ below.

The only difference to outgoing connections is that the internal actor managing
the listen port—the sender of the :class:`Bound` message—watches the actor
which was named as the recipient for :class:`Connected` messages in the
:class:`Bind` message. When that actor terminates the listen port will be
closed and all resources associated with it will be released; existing
connections will not be terminated at this point.

Closing connections
-------------------

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

Writing to a connection
-----------------------

Once a connection has been established data can be sent to it from any actor in the form of a ``Tcp.WriteCommand``.
``Tcp.WriteCommand`` is an abstract class with three concrete implementations:

Tcp.Write
  The simplest ``WriteCommand`` implementation which wraps a ``ByteString`` instance and an "ack" event.
  A ``ByteString`` (as explained in :ref:`this section <bytestring_scala>`) models one or more chunks of immutable
  in-memory data with a maximum (total) size of 2 GB (2^31 bytes).

Tcp.WriteFile
  If you want to send "raw" data from a file you can do so efficiently with the ``Tcp.WriteFile`` command.
  This allows you do designate a (contiguous) chunk of on-disk bytes for sending across the connection without
  the need to first load them into the JVM memory. As such ``Tcp.WriteFile`` can "hold" more than 2GB of data and
  an "ack" event if required.

Tcp.CompoundWrite
  Sometimes you might want to group (or interleave) several ``Tcp.Write`` and/or ``Tcp.WriteFile`` commands into
  one atomic write command which gets written to the connection in one go. The ``Tcp.CompoundWrite`` allows you
  to do just that and offers three benefits:

  1. As explained in the following section the TCP connection actor can only handle one single write command at a time.
     By combining several writes into one ``CompoundWrite`` you can have them be sent across the connection with
     minimum overhead and without the need to spoon feed them to the connection actor via an *ACK-based* message
     protocol.

  2. Because a ``WriteCommand`` is atomic you can be sure that no other actor can "inject" other writes into your
     series of writes if you combine them into one single ``CompoundWrite``. In scenarios where several actors write
     to the same connection this can be an important feature which can be somewhat hard to achieve otherwise.

  3. The "sub writes" of a ``CompoundWrite`` are regular ``Write`` or ``WriteFile`` commands that themselves can request
     "ack" events. These ACKs are sent out as soon as the respective "sub write" has been completed. This allows you to
     attach more than one ACK to a ``Write`` or ``WriteFile`` (by combining it with an empty write that itself requests
     an ACK) or to have the connection actor acknowledge the progress of transmitting the ``CompoundWrite`` by sending
     out intermediate ACKs at arbitrary points.

Throttling Reads and Writes
---------------------------

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
available `on github <@github@/akka-docs/rst/scala/code/docs/io/EchoServer.scala>`_.

.. note::

   It should be obvious that all these flow control schemes only work between
   one writer and one connection actor; as soon as multiple actors send write
   commands to a single connection no consistent result can be achieved.

ACK-Based Back-Pressure
-----------------------

For proper function of the following example it is important to configure the
connection to remain half-open when the remote side closed its writing end:
this allows the example :class:`EchoHandler` to write all outstanding data back
to the client before fully closing the connection. This is enabled using a flag
upon connection activation (observe the :class:`Register` message):

.. includecode:: code/docs/io/EchoServer.scala#echo-manager

With this preparation let us dive into the handler itself:

.. includecode:: code/docs/io/EchoServer.scala#simple-echo-handler
   :exclude: storage-omitted

The principle is simple: when having written a chunk always wait for the
``Ack`` to come back before sending the next chunk. While waiting we switch
behavior such that new incoming data are buffered. The helper functions used
are a bit lengthy but not complicated:

.. includecode:: code/docs/io/EchoServer.scala#simple-helpers

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
----------------------------------------------

.. includecode:: code/docs/io/EchoServer.scala#echo-handler
   :exclude: buffering,closing,storage-omitted

The principle here is to keep writing until a :class:`CommandFailed` is
received, using acknowledgements only to prune the resend buffer. When a such a
failure was received, transition into a different state for handling and handle
resending of all queued data:

.. includecode:: code/docs/io/EchoServer.scala#buffering

It should be noted that all writes which are currently buffered have also been
sent to the connection actor upon entering this state, which means that the
:class:`ResumeWriting` message is enqueued after those writes, leading to the
reception of all outstanding :class:`CommandFailed` messages (which are ignored
in this state) before receiving the :class:`WritingResumed` signal. That latter
message is sent by the connection actor only once the internally queued write
has been fully completed, meaning that a subsequent write will not fail. This
is exploited by the :class:`EchoHandler` to switch to an ACK-based approach for
the first ten writes after a failure before resuming the optimistic
write-through behavior.

.. includecode:: code/docs/io/EchoServer.scala#closing

Closing the connection while still sending all data is a bit more involved than
in the ACK-based approach: the idea is to always send all outstanding messages
and acknowledge all successful writes, and if a failure happens then switch
behavior to await the :class:`WritingResumed` event and start over.

The helper functions are very similar to the ACK-based case:

.. includecode:: code/docs/io/EchoServer.scala#helpers
