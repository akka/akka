.. _io-scala:

IO (Scala)
==========

Introduction
------------

The ``akka.io`` package has been developed in collaboration between the Akka
and `spray.io`_ teams. Its design incorporates the experiences with the
``spray-io`` module along with improvements that were jointly developed for
more general consumption as an actor-based service.

This documentation is in progress and some sections may be incomplete. More will be coming.

.. note::
  The old IO implementation has been deprecated and its documentation has been moved: :ref:`io-scala-old`

Terminology, Concepts
---------------------
The I/O API is completely actor based, meaning that all operations are implemented as message passing instead of
direct method calls. Every I/O driver (TCP, UDP) has a special actor, called *manager* that serves
as the entry point for the API. The manager is accessible through an extension, for example the following code
looks up the TCP manager and returns its ``ActorRef``:

.. code-block:: scala

  import akka.io.IO
  import akka.io.Tcp
  val tcpManager = IO(Tcp)

For various I/O commands the manager instantiates worker actors that will expose themselves to the user of the
API by replying to the command. For example after a ``Connect`` command sent to the TCP manager the manager creates
an actor representing the TCP connection. All operations related to the given TCP connections can be invoked by sending
messages to the connection actor which announces itself by sending a ``Connected`` message.

DeathWatch and Resource Management
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Worker actors usually need a user-side counterpart actor listening for events (such events could be inbound connections,
incoming bytes or acknowledgements for writes). These worker actors *watch* their listener counterparts, therefore the
resources assigned to them are automatically released when the listener stops. This design makes the API more robust
against resource leaks.

Write models (Ack, Nack)
^^^^^^^^^^^^^^^^^^^^^^^^

Basically all of the I/O devices have a maximum throughput which limits the frequency and size of writes. When an
application tries to push more data then a device can handle, the driver has to buffer all bytes that the device has
not yet been able to write. With this approach it is possible to handle short bursts of intensive writes --- but no buffer is infinite.
Therefore, the driver has to notify the writer (a user-side actor) either that no further writes are possible, or by
explicitly notifying it when the next chunk is possible to be written or buffered.

Both of these models are available in the TCP and UDP implementations of Akka IO. Ack based flow control can be enabled
by providing an ack object in the write message (``Write`` in the case of TCP and ``Send`` for UDP) that will be used by
the worker to notify the writer about the success.

If a write (or any other command) fails, the driver notifies the commander with a special message (``CommandFailed`` in
the case of UDP and TCP). This message also serves as a means to notify the writer of a failed write. Please note, that
in a Nack based flow-control setting the writer has to buffer some of the writes as the failure notification for a
write ``W1`` might arrive after additional write commands ``W2`` ``W3`` has been sent.

.. warning::
  An acknowledged write does not mean acknowledged delivery or storage. The Ack/Nack
  protocol described here is a means of flow control not error handling: receiving an Ack for a write signals that the
  I/O driver is ready to accept a new one.

ByteString
^^^^^^^^^^

A primary goal of Akka's IO support is to only communicate between actors with immutable objects. When dealing with network IO on the jvm ``Array[Byte]`` and ``ByteBuffer`` are commonly used to represent collections of ``Byte``\s, but they are mutable. Scala's collection library also lacks a suitably efficient immutable collection for ``Byte``\s. Being able to safely and efficiently move ``Byte``\s around is very important for this IO support, so ``ByteString`` was developed.

``ByteString`` is a `Rope-like <http://en.wikipedia.org/wiki/Rope_(computer_science)>`_ data structure that is immutable and efficient. When 2 ``ByteString``\s are concatenated together they are both stored within the resulting ``ByteString`` instead of copying both to a new ``Array``. Operations such as ``drop`` and ``take`` return ``ByteString``\s that still reference the original ``Array``, but just change the offset and length that is visible. Great care has also been taken to make sure that the internal ``Array`` cannot be modified. Whenever a potentially unsafe ``Array`` is used to create a new ``ByteString`` a defensive copy is created. If you require a ``ByteString`` that only blocks a much memory as necessary for it's content, use the ``compact`` method to get a ``CompactByteString`` instance. If the ``ByteString`` represented only a slice of the original array, this will result in copying all bytes in that slice.

``ByteString`` inherits all methods from ``IndexedSeq``, and it also has some new ones. For more information, look up the ``akka.util.ByteString`` class and it's companion object in the ScalaDoc.

``ByteString`` also comes with it's own optimized builder and iterator classes ``ByteStringBuilder`` and ``ByteIterator`` which provides special features in addition to the standard builder / iterator methods:

Compatibility with java.io
..........................

A ``ByteStringBuilder`` can be wrapped in a `java.io.OutputStream` via the ``asOutputStream`` method. Likewise, ``ByteIterator`` can we wrapped in a ``java.io.InputStream`` via ``asInputStream``. Using these, ``akka.io`` applications can integrate legacy code based on ``java.io`` streams.

Encoding and decoding of binary data
....................................

``ByteStringBuilder`` and ``ByteIterator`` support encoding and decoding of binary data. As an example, consider a stream of binary data frames with the following format:

.. code-block:: text

  frameLen: Int
  n: Int
  m: Int
  n times {
    a: Short
    b: Long
  }
  data: m times Double

In this example, the data is to be stored in arrays of ``a``, ``b`` and ``data``.

Decoding of such frames can be efficiently implemented in the following fashion:

.. includecode:: code/docs/io/BinaryCoding.scala
   :include: decoding

This implementation naturally follows the example data format. In a true Scala application, one might, of course, want use specialized immutable Short/Long/Double containers instead of mutable Arrays.

After extracting data from a ``ByteIterator``, the remaining content can also be turned back into a ``ByteString`` using the ``toSeq`` method

.. includecode:: code/docs/io/BinaryCoding.scala
   :include: rest-to-seq

with no copying from bytes to rest involved. In general, conversions from ByteString to ByteIterator and vice versa are O(1) for non-chunked ByteStrings and (at worst) O(nChunks) for chunked ByteStrings.

Encoding of data also is very natural, using ``ByteStringBuilder``

.. includecode:: code/docs/io/BinaryCoding.scala
   :include: encoding

Using TCP
---------

TODO

Connecting
^^^^^^^^^^

TODO

Accepting connections
^^^^^^^^^^^^^^^^^^^^^

TODO

Using UDP
---------

TODO

Connectionless UDP
^^^^^^^^^^^^^^^^^^^
    - Simple send
    - Bind and send

Connection based UDP
^^^^^^^^^^^^^^^^^^^^

.. note::
  There is some performance benefit in using connection based UDP API over the connectionless one -- if its possible.
  If there is a SecurityManager enabled on the system, every connectionless message send has to go through a security
  check, while in the case of connection-based UDP the security check is cached after connection, thus writes does
  not suffer an additional performance penalty.

Integration with Iteratees
--------------------------

Architecture in-depth
---------------------

For further details on the design and internal architecture see :ref:`io-layer`.

.. _spray.io: http://spray.io