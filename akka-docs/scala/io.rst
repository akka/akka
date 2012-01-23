.. _io-scala:

IO (Scala)
==========

.. sidebar:: Contents

   .. contents:: :local:

Introduction
------------

This documentation is in progress. More to come.

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

