.. _json-streaming-java:

Source Streaming
================

Akka HTTP supports completing a request with an Akka ``Source<T, _>``, which makes it possible to easily build
and consume streaming end-to-end APIs which apply back-pressure throughout the entire stack. 

It is possible to complete requests with raw ``Source<ByteString, _>``, however often it is more convenient to 
stream on an element-by-element basis, and allow Akka HTTP to handle the rendering internally - for example as a JSON array,
or CSV stream (where each element is separated by a new-line).

In the following sections we investigate how to make use of the JSON Streaming infrastructure,
however the general hints apply to any kind of element-by-element streaming you could imagine.

JSON Streaming
==============

`JSON Streaming`_ is a term refering to streaming a (possibly infinite) stream of element as independent JSON
objects as a continuous HTTP request or response. The elements are most often separated using newlines,
however do not have to be. Concatenating elements side-by-side or emitting "very long" JSON array is also another
use case.

In the below examples, we'll be refering to the ``Tweet`` and ``Measurement`` case classes as our model, which are defined as:

.. includecode:: ../../code/docs/http/javadsl/server/JsonStreamingExamplesTest.java#models

.. _Json Streaming: https://en.wikipedia.org/wiki/JSON_Streaming

Responding with JSON Streams
----------------------------

In this example we implement an API representing an infinite stream of tweets, very much like Twitter's `Streaming API`_.

Firstly, we'll need to get some additional marshalling infrastructure set up, that is able to marshal to and from an
Akka Streams ``Source<T,_>``. Here we'll use the ``Jackson`` helper class from ``akka-http-jackson`` (a separate library
that you should add as a dependency if you want to use Jackson with Akka HTTP).

First we enable JSON Streaming by making an implicit ``EntityStreamingSupport`` instance available (Step 1).

The default mode of rendering a ``Source`` is to represent it as an JSON Array. If you want to change this representation
for example to use Twitter style new-line separated JSON objects, you can do so by configuring the support trait accordingly.

In Step 1.1. we demonstrate to configure configude the rendering to be new-line separated, and also how parallel marshalling 
can be applied. We configure the Support object to render the JSON as series of new-line separated JSON objects,
simply by providing the ``start``, ``sep`` and ``end`` ByteStrings, which will be emitted at the apropriate
places in the rendered stream. Although this format is *not* valid JSON, it is pretty popular since parsing it is relatively
simple - clients need only to find the new-lines and apply JSON unmarshalling for an entire line of JSON.

The final step is simply completing a request using a Source of tweets, as simple as that:

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


Simple CSV streaming example
----------------------------

Akka HTTP provides another ``EntityStreamingSupport`` out of the box, namely ``csv`` (comma-separated values).
For completeness, we demonstrate its usage in the below snippet. As you'll notice, switching betweeen streaming
modes is fairly simple, one only has to make sure that an implicit ``Marshaller`` of the requested type is available,
and that the streaming support operates on the same ``Content-Type`` as the rendered values. Otherwise you'll see
an error during runtime that the marshaller did not expose the expected content type and thus we can not render
the streaming response).

.. includecode:: ../../code/docs/http/javadsl/server/JsonStreamingExamplesTest.java#csv-example

Implementing custom EntityStreamingSupport traits
-------------------------------------------------

The ``EntityStreamingSupport`` infrastructure is open for extension and not bound to any single format, content type
or marshalling library. The provided JSON support does not rely on Spray JSON directly, but uses ``Marshaller<T, ByteString>``
instances, which can be provided using any JSON marshalling library (such as Circe, Jawn or Play JSON).

When implementing a custom support trait, one should simply extend the ``EntityStreamingSupport`` abstract class,
and implement all of it's methods. It's best to use the existing implementations as a guideline.
