.. _json-jackson-support-java:

Json Support via Jackson
========================

akka-http provides support to convert application-domain objects from and to JSON using jackson_. To make use
of the support module, you need to add a dependency on `akka-http-jackson-experimental`.

Use ``akka.http.javadsl.marshallers.jackson.Jackson.jsonAs[T]`` to create a ``RequestVal<T>`` which expects the request
body to be of type ``application/json`` and converts it to ``T`` using Jackson.

See `this example`__ in the sources for an example.

Use ``akka.http.javadsl.marshallers.jackson.Jackson.json[T]`` to create a ``Marshaller<T>`` which can be used with
``RequestContext.completeAs`` to convert a POJO to an HttpResponse.


.. _jackson: https://github.com/FasterXML/jackson
__ @github@/akka-http-tests/src/main/java/akka/http/javadsl/server/examples/petstore/PetStoreExample.java