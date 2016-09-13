.. _json-support-java:

Json Support
============

akka-http provides support to convert application-domain objects from and to JSON using jackson_ in an
extra artifact.

Integration with other JSON libraries may be supported by the community.
See `the list of current community extensions for Akka HTTP`_.

.. _`the list of current community extensions for Akka HTTP`: http://akka.io/community/#extensions-to-akka-http

.. _json-jackson-support-java:

Json Support via Jackson
------------------------

To make use of the support module, you need to add a dependency on `akka-http-jackson-experimental`.

Use ``akka.http.javadsl.marshallers.jackson.Jackson.unmarshaller(T.class)`` to create an ``Unmarshaller<HttpEntity,T>`` which expects the request
body (HttpEntity) to be of type ``application/json`` and converts it to ``T`` using Jackson.

See `this example`__ in the sources for an example.

Use ``akka.http.javadsl.marshallers.jackson.Jackson.marshaller(T.class)`` to create a ``Marshaller<T,RequestEntity>`` which can be used with
``RequestContext.complete`` or ``RouteDirectives.complete`` to convert a POJO to an HttpResponse.


.. _jackson: https://github.com/FasterXML/jackson
__ @github@/akka-http-tests/src/main/java/akka/http/javadsl/server/examples/petstore/PetStoreExample.java