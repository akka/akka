.. _http-unmarshalling-java:

Unmarshalling
=============
TODO overhaul for Java

"Unmarshalling" is the process of converting some kind of a lower-level representation, often a "wire format", into a
higher-level (object) structure. Other popular names for it are "Deserialization" or "Unpickling".

In Akka HTTP "Unmarshalling" means the conversion of a lower-level source object, e.g. a ``MessageEntity``
(which forms the "entity body" of an HTTP request or response) or a full ``HttpRequest`` or ``HttpResponse``,
into an instance of type ``T``.


Basic Design
------------

Unmarshalling of instances of type ``A`` into instances of type ``B`` is performed by an ``Unmarshaller[A, B]``.
Akka HTTP also predefines a number of helpful aliases for the types of unmarshallers that you'll likely work with most:

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/unmarshalling/package.scala
   :snippet: unmarshaller-aliases

At its core an ``Unmarshaller[A, B]`` is very similar to a ``Function<A, CompletionStage<B>>`` and as such quite a bit simpler
than its :ref:`marshalling <http-marshalling-java>` counterpart. The process of unmarshalling does not have to support
content negotiation which saves two additional layers of indirection that are required on the marshalling side.


Predefined Unmarshallers
------------------------

Akka HTTP already predefines a number of marshallers for the most common types.
Specifically these are:

- PredefinedFromStringUnmarshallers_

  - ``Byte``
  - ``Short``
  - ``Int``
  - ``Long``
  - ``Float``
  - ``Double``
  - ``Boolean``

- PredefinedFromEntityUnmarshallers_

  - ``Array[Byte]``
  - ``ByteString``
  - ``Array[Char]``
  - ``String``
  - ``akka.http.scaladsl.model.FormData``

- GenericUnmarshallers_

  - ``Unmarshaller[T, T]`` (identity unmarshaller)
  - ``Unmarshaller[Option[A], B]``, if an ``Unmarshaller[A, B]`` is available
  - ``Unmarshaller[A, Option[B]]``, if an ``Unmarshaller[A, B]`` is available

.. _PredefinedFromStringUnmarshallers: @github@/akka-http/src/main/scala/akka/http/scaladsl/unmarshalling/PredefinedFromStringUnmarshallers.scala
.. _PredefinedFromEntityUnmarshallers: @github@/akka-http/src/main/scala/akka/http/scaladsl/unmarshalling/PredefinedFromEntityUnmarshallers.scala
.. _GenericUnmarshallers: @github@/akka-http/src/main/scala/akka/http/scaladsl/unmarshalling/GenericUnmarshallers.scala


Implicit Resolution
-------------------

The unmarshalling infrastructure of Akka HTTP relies on a type-class based approach, which means that ``Unmarshaller``
instances from a certain type ``A`` to a certain type ``B`` have to be available implicitly.

The implicits for most of the predefined unmarshallers in Akka HTTP are provided through the companion object of the
``Unmarshaller`` trait. This means that they are always available and never need to be explicitly imported.
Additionally, you can simply "override" them by bringing your own custom version into local scope.


Custom Unmarshallers
--------------------

Akka HTTP gives you a few convenience tools for constructing unmarshallers for your own types.
Usually you won't have to "manually" implement the ``Unmarshaller`` trait directly.
Rather, it should be possible to use one of the convenience construction helpers defined on the ``Marshaller``
companion:

TODO rewrite sample for Java

..
.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/unmarshalling/Unmarshaller.scala
   :snippet: unmarshaller-creation


Deriving Unmarshallers
----------------------

Sometimes you can save yourself some work by reusing existing unmarshallers for your custom ones.
The idea is to "wrap" an existing unmarshaller with some logic to "re-target" it to your type.

Usually what you want to do is to transform the output of some existing unmarshaller and convert it to your type.
For this type of unmarshaller transformation Akka HTTP defines these methods:

- ``baseUnmarshaller.transform``
- ``baseUnmarshaller.map``
- ``baseUnmarshaller.mapWithInput``
- ``baseUnmarshaller.flatMap``
- ``baseUnmarshaller.flatMapWithInput``
- ``baseUnmarshaller.recover``
- ``baseUnmarshaller.withDefaultValue``
- ``baseUnmarshaller.mapWithCharset`` (only available for FromEntityUnmarshallers)
- ``baseUnmarshaller.forContentTypes`` (only available for FromEntityUnmarshallers)

The method signatures should make their semantics relatively clear.


Using Unmarshallers
-------------------

In many places throughput Akka HTTP unmarshallers are used implicitly, e.g. when you want to access the :ref:`-entity-`
of a request using the :ref:`Routing DSL <http-high-level-server-side-api>`.

However, you can also use the unmarshalling infrastructure directly if you wish, which can be useful for example in tests.
The best entry point for this is the ``akka.http.scaladsl.unmarshalling.Unmarshal`` object, which you can use like this:

.. TODO rewrite for java
.. .. includecode2:: ../../code/docs/http/scaladsl/UnmarshalSpec.scala
   :snippet: use unmarshal

