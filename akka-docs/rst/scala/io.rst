.. _io-scala:

IO (Scala)
==========


Introduction
------------

This documentation is in progress and some sections may be incomplete. More will be coming.

Components
----------

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

 
The encoded data then can be sent over socket (see ``IOManager``):
 
.. includecode:: code/docs/io/BinaryCoding.scala
   :include: sending


IO.Handle
^^^^^^^^^

``IO.Handle`` is an immutable reference to a Java NIO ``Channel``. Passing mutable ``Channel``\s between ``Actor``\s could lead to unsafe behavior, so instead subclasses of the ``IO.Handle`` trait are used. Currently there are 2 concrete subclasses: ``IO.SocketHandle`` (representing a ``SocketChannel``) and ``IO.ServerHandle`` (representing a ``ServerSocketChannel``).

IOManager
^^^^^^^^^

The ``IOManager`` takes care of the low level IO details. Each ``ActorSystem`` has it's own ``IOManager``, which can be accessed calling ``IOManager(system: ActorSystem)``. ``Actor``\s communicate with the ``IOManager`` with specific messages. The messages sent from an ``Actor`` to the ``IOManager`` are handled automatically when using certain methods and the messages sent from an ``IOManager`` are handled within an ``Actor``\'s ``receive`` method.

Connecting to a remote host:

.. code-block:: scala

  val address = new InetSocketAddress("remotehost", 80)
  val socket = IOManager(actorSystem).connect(address)

.. code-block:: scala

  val socket = IOManager(actorSystem).connect("remotehost", 80)

Creating a server:

.. code-block:: scala

  val address = new InetSocketAddress("localhost", 80)
  val serverSocket = IOManager(actorSystem).listen(address)

.. code-block:: scala

  val serverSocket = IOManager(actorSystem).listen("localhost", 80)

Receiving messages from the ``IOManager``:

.. code-block:: scala

  def receive = {

    case IO.Listening(server, address) =>
      println("The server is listening on socket " + address)

    case IO.Connected(socket, address) =>
      println("Successfully connected to " + address)

    case IO.NewClient(server) =>
      println("New incoming connection on server")
      val socket = server.accept()
      println("Writing to new client socket")
      socket.write(bytes)
      println("Closing socket")
      socket.close()

    case IO.Read(socket, bytes) =>
      println("Received incoming data from socket")

    case IO.Closed(socket: IO.SocketHandle, cause) =>
      println("Socket has closed, cause: " + cause)

    case IO.Closed(server: IO.ServerHandle, cause) =>
      println("Server socket has closed, cause: " + cause)

  }

IO.Iteratee
^^^^^^^^^^^

Included with Akka's IO support is a basic implementation of ``Iteratee``\s. ``Iteratee``\s are an effective way of handling a stream of data without needing to wait for all the data to arrive. This is especially useful when dealing with non blocking IO since we will usually receive data in chunks which may not include enough information to process, or it may contain much more data than we currently need.

This ``Iteratee`` implementation is much more basic than what is usually found. There is only support for ``ByteString`` input, and enumerators aren't used. The reason for this limited implementation is to reduce the amount of explicit type signatures needed and to keep things simple. It is important to note that Akka's ``Iteratee``\s are completely optional, incoming data can be handled in any way, including other ``Iteratee`` libraries.

``Iteratee``\s work by processing the data that it is given and returning either the result (with any unused input) or a continuation if more input is needed. They are monadic, so methods like ``flatMap`` can be used to pass the result of an ``Iteratee`` to another.

The basic ``Iteratee``\s included in the IO support can all be found in the ScalaDoc under ``akka.actor.IO``, and some of them are covered in the example below.

Examples
--------

Http Server
^^^^^^^^^^^

This example will create a simple high performance HTTP server. We begin with our imports:

.. includecode:: code/docs/io/HTTPServer.scala
   :include: imports

Some commonly used constants:

.. includecode:: code/docs/io/HTTPServer.scala
   :include: constants

And case classes to hold the resulting request:

.. includecode:: code/docs/io/HTTPServer.scala
   :include: request-class

Now for our first ``Iteratee``. There are 3 main sections of a HTTP request: the request line, the headers, and an optional body. The main request ``Iteratee`` handles each section separately:

.. includecode:: code/docs/io/HTTPServer.scala
   :include: read-request

In the above code ``readRequest`` takes the results of 3 different ``Iteratees`` (``readRequestLine``, ``readHeaders``, ``readBody``) and combines them into a single ``Request`` object. ``readRequestLine`` actually returns a tuple, so we extract it's individual components. ``readBody`` depends on values contained within the header section, so we must pass those to the method.

The request line has 3 parts to it: the HTTP method, the requested URI, and the HTTP version. The parts are separated by a single space, and the entire request line ends with a ``CRLF``.

.. includecode:: code/docs/io/HTTPServer.scala
   :include: read-request-line

Reading the request method is simple as it is a single string ending in a space. The simple ``Iteratee`` that performs this is ``IO.takeUntil(delimiter: ByteString): Iteratee[ByteString]``. It keeps consuming input until the specified delimiter is found. Reading the HTTP version is also a simple string that ends with a ``CRLF``.

The ``ascii`` method is a helper that takes a ``ByteString`` and parses it as a ``US-ASCII`` ``String``.

Reading the request URI is a bit more complicated because we want to parse the individual components of the URI instead of just returning a simple string:

.. includecode:: code/docs/io/HTTPServer.scala
   :include: read-request-uri

For this example we are only interested in handling absolute paths. To detect if we the URI is an absolute path we use ``IO.peek(length: Int): Iteratee[ByteString]``, which returns a ``ByteString`` of the request length but doesn't actually consume the input. We peek at the next bit of input and see if it matches our ``PATH`` constant (defined above as ``ByteString("/")``). If it doesn't match we throw an error, but for a more robust solution we would want to handle other valid URIs.

Next we handle the path itself:

.. includecode:: code/docs/io/HTTPServer.scala
   :include: read-path

The ``step`` method is a recursive method that takes a ``List`` of the accumulated path segments. It first checks if the remaining input starts with the ``PATH`` constant, and if it does, it drops that input, and returns the ``readUriPart`` ``Iteratee`` which has it's result added to the path segment accumulator and the ``step`` method is run again.

If after reading in a path segment the next input does not start with a path, we reverse the accumulated segments and return it (dropping the last segment if it is blank).

Following the path we read in the query (if it exists):

.. includecode:: code/docs/io/HTTPServer.scala
   :include: read-query

It is much simpler than reading the path since we aren't doing any parsing of the query since there is no standard format of the query string.

Both the path and query used the ``readUriPart`` ``Iteratee``, which is next:

.. includecode:: code/docs/io/HTTPServer.scala
   :include: read-uri-part

Here we have several ``Set``\s that contain valid characters pulled from the URI spec. The ``readUriPart`` method takes a ``Set`` of valid characters (already mapped to ``Byte``\s) and will continue to match characters until it reaches on that is not part of the ``Set``. If it is a percent encoded character then that is handled as a valid character and processing continues, or else we are done collecting this part of the URI.

Headers are next:

.. includecode:: code/docs/io/HTTPServer.scala
   :include: read-headers

And if applicable, we read in the message body:

.. includecode:: code/docs/io/HTTPServer.scala
   :include: read-body

Finally we get to the actual ``Actor``:

.. includecode:: code/docs/io/HTTPServer.scala
   :include: actor

And it's companion object:

.. includecode:: code/docs/io/HTTPServer.scala
   :include: actor-companion

And the OKResponse:

.. includecode:: code/docs/io/HTTPServer.scala
   :include: ok-response

A ``main`` method to start everything up:

.. includecode:: code/docs/io/HTTPServer.scala
   :include: main
