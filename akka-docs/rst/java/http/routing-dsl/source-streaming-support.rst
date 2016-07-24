.. _json-streaming-java:

Source Streaming
================

Akka HTTP supports completing a request with an Akka ``Source<T, ?>``, which makes it possible to very easily build
streaming end-to-end APIs which apply back-pressure throughout the entire stack. 

It is possible to complete requests with raw ``Source<ByteString, ?>``, however often it is more convenient to 
stream on an element-by-element basis, and allow Akka HTTP to handle the rendering internally - for example as a JSON array,
or CSV stream (where each element is separated by a new-line).

In the following sections we investigate how to make use of the JSON Streaming infrastructure,
however the general hints apply to any kind of element-by-element streaming you could imagine. 

It is possible to implement your own framing for any content type you might need, including bianary formats 
by implementing :class:`FramingWithContentType`.

JSON Streaming
==============

`JSON Streaming`_ is a term refering to streaming a (possibly infinite) stream of element as independent JSON
objects as a continuous HTTP request or response. The elements are most often separated using newlines,
however do not have to be. Concatenating elements side-by-side or emitting "very long" JSON array is also another
use case.

In the below examples, we'll be refering to the ``User`` and ``Measurement`` case classes as our model, which are defined as:

.. includecode:: ../../code/docs/http/javadsl/server/JsonStreamingExamplesTest.java#models

.. _Json Streaming: https://en.wikipedia.org/wiki/JSON_Streaming

Responding with JSON Streams
----------------------------

In this example we implement an API representing an infinite stream of tweets, very much like Twitter's `Streaming API`_.

Firstly, we'll need to get some additional marshalling infrastructure set up, that is able to marshal to and from an
Akka Streams ``Source<T, ?>``. One such trait, containing the needed marshallers is ``SprayJsonSupport``, which uses
spray-json (a high performance json parser library), and is shipped as part of Akka HTTP in the
``akka-http-spray-json-experimental`` module.

The last bit of setup, before we can render a streaming json response

.. includecode:: ../../code/docs/http/javadsl/server/JsonStreamingExamplesTest.java#response-streaming

.. _Streaming API: https://dev.twitter.com/streaming/overview

Consuming JSON Streaming uploads
--------------------------------

Sometimes the client may be sending a streaming request, for example an embedded device initiated a connection with
the server and is feeding it with one line of measurement data.

In this example, we want to consume this data in a streaming fashion from the request entity, and also apply
back-pressure to the underlying TCP connection, if the server can not cope with the rate of incoming data (back-pressure
will be applied automatically thanks to using Akka HTTP/Streams).

.. includecode:: ../../code/docs/http/javadsl/server/JsonStreamingExamplesTest.java#formats

.. includecode:: ../../code/docs/http/javadsl/server/JsonStreamingExamplesTest.java#incoming-request-streaming

Implementing custom (Un)Marshaller support for JSON streaming
-------------------------------------------------------------

The following types that may need to be implemented by a custom framed-streaming support library are:

- ``SourceRenderingMode`` which can customise how to render the begining / between-elements and ending of such 
  stream (while writing a response, i.e. by calling ``complete(source)``).
  Implementations for JSON are available in ``akka.http.scaladsl.common.JsonSourceRenderingMode``.
- ``FramingWithContentType`` which is needed to be able to split incoming ``ByteString`` 
  chunks into frames of the higher-level data type format that is understood by the provided unmarshallers.
  In the case of JSON it means chunking up ByteStrings such that each emitted element corresponds to exactly one JSON object,
  this framing is implemented in ``EntityStreamingSupport``.
