.. _http-marshalling-java:

Marshalling
===========
TODO overhaul for Java

"Marshalling" is the process of converting a higher-level (object) structure into some kind of lower-level
representation, often a "wire format". Other popular names for it are "Serialization" or "Pickling".

In Akka HTTP "Marshalling" means the conversion of an object of type ``T`` into a lower-level target type,
e.g. a ``MessageEntity`` (which forms the "entity body" of an HTTP request or response) or a full ``HttpRequest`` or
``HttpResponse``.


Basic Design
------------

Marshalling of instances of type ``A`` into instances of type ``B`` is performed by a ``Marshaller[A, B]``.
Akka HTTP also predefines a number of helpful aliases for the types of marshallers that you'll likely work with most:

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/marshalling/package.scala
   :snippet: marshaller-aliases

Contrary to what you might initially expect ``Marshaller[A, B]`` is not a plain function ``A => B`` but rather
essentially a function ``A => Future[List[Marshalling[B]]]``.
Let's dissect this rather complicated looking signature piece by piece to understand what marshallers are designed this
way.
Given an instance of type ``A`` a ``Marshaller[A, B]`` produces:

1. A ``Future``: This is probably quite clear. Marshallers are not required to synchronously produce a result, so instead
they return a future, which allows for asynchronicity in the marshalling process.

2. of ``List``: Rather than only a single target representation for ``A`` marshallers can offer several ones. Which
one will be rendered onto the wire in the end is decided by content negotiation.
For example, the ``ToEntityMarshaller[OrderConfirmation]`` might offer a JSON as well as an XML representation.
The client can decide through the addition of an ``Accept`` request header which one is preferred. If the client doesn't
express a preference the first representation is picked.

3. of ``Marshalling[B]``: Rather than returning an instance of ``B`` directly marshallers first produce a
``Marshalling[B]``. This allows for querying the ``MediaType`` and potentially the ``HttpCharset`` that the marshaller
will produce before the actual marshalling is triggered. Apart from enabling content negotiation this design allows for
delaying the actual construction of the marshalling target instance to the very last moment when it is really needed.

This is how ``Marshalling`` is defined:

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/marshalling/Marshaller.scala
   :snippet: marshalling


Predefined Marshallers
----------------------

Akka HTTP already predefines a number of marshallers for the most common types.
Specifically these are:

- PredefinedToEntityMarshallers_

  - ``Array[Byte]``
  - ``ByteString``
  - ``Array[Char]``
  - ``String``
  - ``akka.http.scaladsl.model.FormData``
  - ``akka.http.scaladsl.model.MessageEntity``
  - ``T <: akka.http.scaladsl.model.Multipart``

- PredefinedToResponseMarshallers_

  - ``T``, if a ``ToEntityMarshaller[T]`` is available
  - ``HttpResponse``
  - ``StatusCode``
  - ``(StatusCode, T)``, if a ``ToEntityMarshaller[T]`` is available
  - ``(Int, T)``, if a ``ToEntityMarshaller[T]`` is available
  - ``(StatusCode, immutable.Seq[HttpHeader], T)``, if a ``ToEntityMarshaller[T]`` is available
  - ``(Int, immutable.Seq[HttpHeader], T)``, if a ``ToEntityMarshaller[T]`` is available

- PredefinedToRequestMarshallers_

  - ``HttpRequest``
  - ``Uri``
  - ``(HttpMethod, Uri, T)``, if a ``ToEntityMarshaller[T]`` is available
  - ``(HttpMethod, Uri, immutable.Seq[HttpHeader], T)``, if a ``ToEntityMarshaller[T]`` is available

- GenericMarshallers_

  - ``Marshaller[Throwable, T]``
  - ``Marshaller[Option[A], B]``, if a ``Marshaller[A, B]`` and an ``EmptyValue[B]`` is available
  - ``Marshaller[Either[A1, A2], B]``, if a ``Marshaller[A1, B]`` and a ``Marshaller[A2, B]`` is available
  - ``Marshaller[Future[A], B]``, if a ``Marshaller[A, B]`` is available
  - ``Marshaller[Try[A], B]``, if a ``Marshaller[A, B]`` is available

.. _PredefinedToEntityMarshallers: @github@/akka-http/src/main/scala/akka/http/scaladsl/marshalling/PredefinedToEntityMarshallers.scala
.. _PredefinedToResponseMarshallers: @github@/akka-http/src/main/scala/akka/http/scaladsl/marshalling/PredefinedToResponseMarshallers.scala
.. _PredefinedToRequestMarshallers: @github@/akka-http/src/main/scala/akka/http/scaladsl/marshalling/PredefinedToRequestMarshallers.scala
.. _GenericMarshallers: @github@/akka-http/src/main/scala/akka/http/scaladsl/marshalling/GenericMarshallers.scala


Implicit Resolution
-------------------

The marshalling infrastructure of Akka HTTP relies on a type-class based approach, which means that ``Marshaller``
instances from a certain type ``A`` to a certain type ``B`` have to be available implicitly.

The implicits for most of the predefined marshallers in Akka HTTP are provided through the companion object of the
``Marshaller`` trait. This means that they are always available and never need to be explicitly imported.
Additionally, you can simply "override" them by bringing your own custom version into local scope.


Custom Marshallers
------------------

Akka HTTP gives you a few convenience tools for constructing marshallers for your own types.
Before you do that you need to think about what kind of marshaller you want to create.
If all your marshaller needs to produce is a ``MessageEntity`` then you should probably provide a
``ToEntityMarshaller[T]``. The advantage here is that it will work on both the client- as well as the server-side since
a ``ToResponseMarshaller[T]`` as well as a ``ToRequestMarshaller[T]`` can automatically be created if a
``ToEntityMarshaller[T]`` is available.

If, however, your marshaller also needs to set things like the response status code, the request method, the request URI
or any headers then a ``ToEntityMarshaller[T]`` won't work. You'll need to fall down to providing a
``ToResponseMarshaller[T]`` or a ``ToRequestMarshaller[T]`` directly.

For writing you own marshallers you won't have to "manually" implement the ``Marshaller`` trait directly.
Rather, it should be possible to use one of the convenience construction helpers defined on the ``Marshaller``
companion:

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/marshalling/Marshaller.scala
   :snippet: marshaller-creation


Deriving Marshallers
--------------------

Sometimes you can save yourself some work by reusing existing marshallers for your custom ones.
The idea is to "wrap" an existing marshaller with some logic to "re-target" it to your type.

In this regard wrapping a marshaller can mean one or both of the following two things:

- Transform the input before it reaches the wrapped marshaller
- Transform the output of the wrapped marshaller

For the latter (transforming the output) you can use ``baseMarshaller.map``, which works exactly as it does for functions.
For the former (transforming the input) you have four alternatives:

- ``baseMarshaller.compose``
- ``baseMarshaller.composeWithEC``
- ``baseMarshaller.wrap``
- ``baseMarshaller.wrapWithEC``

``compose`` works just like it does for functions.
``wrap`` is a compose that allows you to also change the ``ContentType`` that the marshaller marshals to.
The ``...WithEC`` variants allow you to receive an ``ExecutionContext`` internally if you need one, without having to
depend on one being available implicitly at the usage site.


Using Marshallers
-----------------

In many places throughput Akka HTTP marshallers are used implicitly, e.g. when you define how to :ref:`-complete-` a
request using the :ref:`Routing DSL <http-high-level-server-side-api>`.

However, you can also use the marshalling infrastructure directly if you wish, which can be useful for example in tests.
The best entry point for this is the ``akka.http.scaladsl.marshalling.Marshal`` object, which you can use like this:

.. TODO rewrite for Java
.. .. includecode2:: ../../code/docs/http/scaladsl/MarshalSpec.scala
   :snippet: use marshal
