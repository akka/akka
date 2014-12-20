.. _http-model-scala:

Model
=====

The akka HTTP model contains a mostly immutable, case-class based model of the major HTTP data structures,
like HTTP requests, responses and common headers. It also includes a parser for the latter, which is able to construct
the more structured header models from raw unstructured header name/value pairs.

Overview
--------

Since akka-http-core provides the central HTTP data structures you will find the following import in quite a
few places around the code base (and probably your own code as well):

.. includecode:: ../code/docs/http/ModelSpec.scala
   :include: import-model

This brings in scope all of the relevant things that are defined here and that you’ll want to work with, mainly:

- ``HttpRequest`` and ``HttpResponse``, the central message model
- ``headers``, the package containing all the predefined HTTP header models and supporting types
- Supporting types like ``Uri``, ``HttpMethods``, ``MediaTypes``, ``StatusCodes``, etc.

A common pattern is that the model of a certain entity is represented by an immutable type (class or trait),
while the actual instances of the entity defined by the HTTP spec live in an accompanying object carrying the name of
the type plus a trailing plural 's'.

For example:

- Defined HttpMethod instances live in the HttpMethods object.
- Defined HttpCharset instances live in the HttpCharsets object.
- Defined HttpEncoding instances live in the HttpEncodings object.
- Defined HttpProtocol instances live in the HttpProtocols object.
- Defined MediaType instances live in the MediaTypes object.
- Defined StatusCode instances live in the StatusCodes object.

HttpRequest / HttpResponse
--------------------------

``HttpRequest`` and ``HttpResponse`` are the basic case classes representing HTTP messages.

An ``HttpRequest`` consists of

 - method (GET, POST, etc.)
 - URI
 - protocol
 - headers
 - entity (body data)

Here are some examples how to construct an ``HttpRequest``:

.. includecode:: ../code/docs/http/ModelSpec.scala
   :include: construct-request

All parameters of ``HttpRequest`` have default values set, so e.g. ``headers`` don't need to be specified
if there are none. Many of the parameters types (like ``HttpEntity`` and ``Uri``) define implicit conversions
for common use cases to simplify the creation of request and response instances.

An ``HttpResponse`` consists of

 - status code
 - protocol
 - headers
 - entity

Here are some examples how to construct an ``HttpResponse``:

.. includecode:: ../code/docs/http/ModelSpec.scala
   :include: construct-response

Aside from the simple ``HttpEntity`` constructors to create an entity from a fixed ``ByteString`` shown here,
subclasses of ``HttpEntity`` allow data to be specified as a stream of data which is explained in the next section.

.. _HttpEntity:

HttpEntity
----------

An ``HttpEntity`` carries the content of a request together with its content-type which is needed to interpret the raw
byte data.

Akka HTTP provides several kinds of entities to support static and streaming data for the different kinds of ways
to transport streaming data with HTTP. There are four subtypes of HttpEntity:


HttpEntity.Strict
  An entity which wraps a static ``ByteString``. It represents a standard, non-chunked HTTP message with ``Content-Length``
  set.


HttpEntity.Default
  A streaming entity which needs a predefined length and a ``Producer[ByteString]`` to produce the body data of
  the message. It represents a standard, non-chunked HTTP message with ``Content-Length`` set. It is an error if the
  provided ``Producer[ByteString]`` doesn't produce exactly as many bytes as specified. On the wire, a Strict entity
  and a Default entity cannot be distinguished. However, they offer a valuable alternative in the API to distinguish
  between strict and streamed data.


HttpEntity.Chunked
  A streaming entity of unspecified length that uses `Chunked Transfer Coding`_ for transmitting data. Data is
  represented by a ``Producer[ChunkStreamPart]``. A ``ChunkStreamPart`` is either a non-empty ``Chunk`` or a ``LastChunk``
  containing optional trailer headers. The stream must consist of 0..n ``Chunked`` parts and can be terminated by an
  optional ``LastChunk`` part (which carries optional trailer headers).


HttpEntity.CloseDelimited
  A streaming entity of unspecified length that is delimited by closing the connection ("Connection: close"). Note,
  that this entity type can only be used in an ``HttpResponse``.

HttpEntity.IndefiniteLength
  A streaming entity of unspecified length that can be used as a ``BodyPart`` entity.

Entity types ``Strict``, ``Default``, and ``Chunked`` are a subtype of ``HttpEntity.Regular`` which allows to use them for
requests and responses. In contrast, ``HttpEntity.CloseDelimited`` can only be used for responses.

Streaming entity types (i.e. all but ``Strict``) cannot be shared or serialized. To create a strict, sharable copy of an
entity or message use ``HttpEntity.toStrict`` or ``HttpMessage.toStrict`` which returns a Future of the object with the
body data collected into a ``ByteString``.

.. _Chunked Transfer Coding: http://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-26#section-4.1

The ``HttpEntity`` companion object contains several helper constructors to create entities from common types easily.

You can pattern match over the subtypes of ``HttpEntity`` if you want to provide special handling for each of the
subtypes. However, in many cases a recipient of an `HttpEntity` doesn't care about of which subtype an entity is
(and how data is transported exactly on the HTTP layer). Therefore, a general ``HttpEntity.dataBytes`` is provided
which allows access to the data of an entity regardless of its concrete subtype.

.. note::

  When to use which subtype?
    - Use Strict if the amount of data is small and it is already in the heap (or even available as a ``ByteString``)
    - Use Default if the data is generated by a streaming data source and the size of the data is fixed
    - Use Chunked to support a data stream of unknown length
    - Use CloseDelimited for a response as an alternative to Chunked e.g. if chunked transfer encoding isn't supported
      by a client.
    - Use IndefiniteLength instead of CloseDelimited in a BodyPart.

Header model
------------

Akka HTTP contains a rich model of the common HTTP headers. Parsing and rendering is done automatically so that
applications don't need to care for the actual syntax of headers. Headers not modelled explicitly are represented
as a ``RawHeader``.

See these examples of how to deal with headers:

.. includecode:: ../code/docs/http/ModelSpec.scala
   :include: headers

Parsing / Rendering
-------------------

Parsing and rendering of HTTP data structures is heavily optimized and for most types there's currently no public API
provided to parse (or render to) Strings or byte arrays.
