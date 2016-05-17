.. _marshalling-java:

Marshalling & Unmarshalling
===========================

"Marshalling" is the process of converting a higher-level (object) structure into some kind of lower-level
representation (and vice versa), often a binary wire format. Other popular names for it are "Serialization" or
"Pickling".

In akka-http "Marshalling" means the conversion of an object of type T into an HttpEntity, which forms the entity body
of an HTTP request or response (depending on whether used on the client or server side).

Marshalling
-----------

On the server-side marshalling is used to convert a application-domain object to a response (entity). Requests can
contain an ``Accept`` header that lists acceptable content types for the client. A marshaller contains the logic to
negotiate the result content types based on the ``Accept`` and the ``AcceptCharset`` headers.

Marshallers can be specified when completing a request with ``RequestContext.complete`` or by using one of the 
``RouteDirectives.complete`` directives.

These marshallers are provided by akka-http:

  * Use :ref:`json-jackson-support-java` to create an marshaller that can convert a POJO to an ``application/json``
    response using jackson_.
  * Use ``Marshaller.stringToEntity``, ``Marshaller.byteArrayToEntity``, ``Marshaller.byteStringToEntity``,
    combined with ``Marshaller.entityToResponse`` to create custom marshallers.

Unmarshalling
-------------

On the server-side unmarshalling is used to convert a request (entity) to a application-domain object. This is done
in the ``MarshallingDirectives.request`` or ``MarshallingDirectives.entity`` directive. There are several unmarshallers
provided by akka-http: 

 * Use :ref:`json-jackson-support-java` to create an unmarshaller that can convert an ``application/json`` request
   to a POJO using jackson_.
 * Use the predefined ``Unmarshaller.entityToString``, ``Unmarshaller.entityToByteString``, ``Unmarshaller.entityToByteArray``,
   ``Unmarshaller.entityToCharArray`` to convert to those basic types.
 * Use ``Unmarshaller.sync`` or ``Unmarshaller.async`` to create a custom unmarshaller.

.. _jackson: https://github.com/FasterXML/jackson