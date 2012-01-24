.. _io-scala:

IO (Scala)
==========

.. sidebar:: Contents

   .. contents:: :local:

Introduction
------------

This documentation is in progress. More to come.

Components
----------

ByteString
^^^^^^^^^^

A primary goal of Akka's IO module is to only communicate between actors with immutable objects. When dealing with network IO on the jvm ``Array[Byte]`` and ``ByteBuffer`` are commonly used to represent collections of ``Byte``\s, but they are mutable. Scala's collection library also lacks a suitably efficient immutable collection for ``Byte``\s. Being able to safely and efficiently move ``Byte``\s around is very important for this IO module, so ``ByteString`` was developed.

``ByteString`` is a `Rope-like <http://en.wikipedia.org/wiki/Rope_(computer_science)>`_ data structure that is immutable and efficient. When 2 ``ByteString``\s are concatenated together they are both stored within the resulting ``ByteString`` instead of copying both to a new ``Array``. Operations such as ``drop`` and ``take`` return ``ByteString``\s that still reference the original ``Array``, but just change the offset and length that is visible. Great care has also been taken to make sure that the internal ``Array`` cannot be modified. Whenever a potentially unsafe ``Array`` is used to create a new ``ByteString`` a defensive copy is created.

``ByteString`` inherits all methods from ``IndexedSeq``, and it also has some new ones:

copyToBuffer(buffer: ByteBuffer): Int
    Copy as many bytes as possible to a ``ByteBuffer``, starting from it's current position. This method will not overflow the buffer. It returns the number of bytes copied.

compact: ByteString
    Creates a new ``ByteString`` with all contents compacted into a single byte array. If the contents of this ``ByteString`` are already compacted it will return itself unchanged.

asByteBuffer: ByteBuffer
    If possible this will return a read-only ``ByteBuffer`` that wraps the internal byte array. If this ``ByteString`` contains more then one byte array then this method will return the result of ``toByteBuffer``.

toByteBuffer: ByteBuffer
    Creates a new ByteBuffer with a copy of all bytes contained in this ``ByteString``.

decodeString(charset: String): String
    Decodes this ``ByteString`` using a charset to produce a ``String``.

utf8String: String
    Decodes this ``ByteString`` as a UTF-8 encoded String.

There are also several factory methods in the ``ByteString`` companion object to assist in creating a new ``ByteString``. The ``apply`` method accepts ``Array[Byte]``, ``Byte*``, ``ByteBuffer``, ``String``, as well as a ``String`` with a charset. There is also ``fromArray(array, offset, length)`` for creating a ``ByteString`` using only part of an ``Array[Byte]``.

Finally, there is a ``ByteStringBuilder`` to build up a ``ByteString`` using Scala's mutable ``Builder`` concept. It can be especially useful when many ``ByteString``\s need to be concatenated in a performance critical section of a program.

IO.Handle
^^^^^^^^^

``IO.Handle`` is an immutable reference to a Java NIO ``Channel``. Passing mutable ``Channel``\s between ``Actor``\s could lead to unsafe behavior, so instead subclasses of the ``IO.Handle`` trait are used. Currently there are 2 concrete subclasses: ``IO.SocketHandle`` (representing a ``SocketChannel``) and ``IO.ServerHandle`` (representing a ``ServerSocketChannel``).

IOManager
^^^^^^^^^

The ``IOManager`` takes care of the low level IO details. Each ``ActorSystem`` has it's own ``IOManager``, which can be accessed calling ``IOManager(system: ActorSystem)``. ``Actor``\s communicate with the ``IOManager`` with specific messages. The messages sent from an ``Actor`` to the ``IOManager`` are created and sent from certain methods:

IOManager(system).connect(address: SocketAddress): IO.SocketHandle
    Opens a ``SocketChannel`` and connects to an address. Can also use ``connect(host: String, port: Int)``.

IOManager(system).listen(address: SocketAddress): IO.ServerHandle
    Opens a ``ServerSocketChannel`` and listens on an address. Can also use ``listen(host: String, port: Int)``.

socketHandle.write(bytes: ByteString)
    Write to the ``SocketChannel``.

serverHandle.accept(): IO.SocketHandle
    Accepts an incoming connection, and returns the ``IO.SocketHandle`` for the new connection.

handle.close()
    Closes the ``Channel``.

Messages that the ``IOManager`` can send to an ``Actor`` are:

IO.Listening(server: IO.ServerHandle, address: SocketAddress)
    Sent when a ``ServerSocketChannel`` is created. If port 0 (random port) was requested then the address returned here will contain the actual port.

IO.Connected(socket: IO.SocketHandle, address: SocketAddress)
    Sent after a ``SocketChannel`` has successfully connected.

IO.NewClient(server: IO.ServerHandle)
    Sent when a new client has connected to a ``ServerSocketChannel``. The ``accept`` method must be called on the ``IO.ServerHandle`` in order to get the ``IO.SocketHandle`` to communicate to the new client.

IO.Read(handle: IO.ReadHandle, bytes: ByteString)
    Sent when bytes have been read from a ``SocketChannel``. The handle is a ``IO.ReadHandle``, which is a superclass of ``IO.SocketHandle``.

IO.Closed(handle: IO.Handle, cause: Option[Exception])
    Sent when a ``Channel`` has closed. If an ``Exception`` was thrown due to this ``Channel`` closing it will be contained here.

IO.Iteratee
^^^^^^^^^^^

See example below.

Examples
--------

Http Server
^^^^^^^^^^^

Some commonly used constants:

.. includecode:: code/akka/docs/io/HTTPServer.scala
   :include: constants

And case classes to hold the resulting request:

.. includecode:: code/akka/docs/io/HTTPServer.scala
   :include: request-class

Now for our first ``Iteratee``. There are 3 main sections of a HTTP request: the request line, the headers, and an optional body. The main request ``Iteratee`` handles each section separately:

.. includecode:: code/akka/docs/io/HTTPServer.scala
   :include: read-request

In the above code ``readRequest`` takes the results of 3 different ``Iteratees`` (``readRequestLine``, ``readHeaders``, ``readBody``) and combines them into a single ``Request`` object. ``readRequestLine`` actually returns a tuple, so we extract it's individual components. ``readBody`` depends on values contained within the header section, so we must pass those to the method.

The request line has 3 parts to it: the HTTP method, the requested URI, and the HTTP version. The parts are separated by a single space, and the entire request line ends with a ``CRLF``.

.. includecode:: code/akka/docs/io/HTTPServer.scala
   :include: read-request-line

Reading the request method is simple as it is a single string ending in a space. The simple ``Iteratee`` that performs this is ``IO.takeUntil(delimiter: ByteString): Iteratee[ByteString]``. It keeps consuming input until the specified delimiter is found. Reading the HTTP version is also a simple string that ends with a ``CRLF``.

The ``ascii`` method is a helper that takes a ``ByteString`` and parses it as a ``US-ASCII`` ``String``.

Reading the request URI is a bit more complicated because we want to parse the individual components of the URI instead of just returning a simple string:

.. includecode:: code/akka/docs/io/HTTPServer.scala
   :include: read-request-uri

For this example we are only interested in handling absolute paths. To detect if we the URI is an absolute path we use ``IO.peek(length: Int): Iteratee[ByteString]``, which returns a ``ByteString`` of the request length but doesn't actually consume the input. We peek at the next bit of input and see if it matches our ``PATH`` constant (defined above as ``ByteString("/")``). If it doesn't match we throw an error, but for a more robust solution we would want to handle other valid URIs.

Reading the URI path will be our most complex ``Iteratee``. It involves a recursive method that reads in each path segment of the URI:

.. includecode:: code/akka/docs/io/HTTPServer.scala
   :include: read-path

The ``step`` method is a recursive method that takes a ``List`` of the accumulated path segments. It first checks if the remaining input starts with the ``PATH`` constant, and if it does, it drops that input, and returns the ``readUriPart`` ``Iteratee`` which has it's result added to the path segment accumulator and the ``step`` method is run again.

If after reading in a path segment the next input does not start with a path, we reverse the accumulated segments and return it (dropping the last segment if it is blank).

.. includecode:: code/akka/docs/io/HTTPServer.scala
   :include: read-query

.. includecode:: code/akka/docs/io/HTTPServer.scala
   :include: read-uri-part

.. includecode:: code/akka/docs/io/HTTPServer.scala
   :include: read-headers

.. includecode:: code/akka/docs/io/HTTPServer.scala
   :include: read-body

