.. _-entity-:

entity
======

Unmarshalls the request entity to the given type and passes it to its inner Route.  An unmarshaller
returns an ``Either`` with ``Right(value)`` if successful or ``Left(exception)`` for a failure.
The ``entity`` method will either pass the ``value`` to the inner route or map the ``exception`` to a
``spray.routing.Rejection``.

Signature
---------

.. includecode2:: /../../akka-http-scala/src/main/scala/akka/http/scaladsl/server/directives/MarshallingDirectives.scala
   :snippet: entity

Description
-----------

The ``entity`` directive works in conjuction with ``as`` and ``spray.httpx.unmarshalling`` to
convert some serialized "wire format" value into a higher-level object structure.  
:ref:`The unmarshalling documentation <http-unmarshalling-scala>` explains this process in detail.
This directive simplifies extraction and error handling to the specified type from the request.

An unmarshaller will return a ``Left(exception)`` in the case of an error.  This is converted to a
``spray.routing.Rejection`` within the ``entity`` directive.  The following table lists how exceptions
are mapped to rejections:

========================== ============
Left(exception)            Rejection
-------------------------- ------------
``ContentExpected``        ``RequestEntityExpectedRejection``
``UnsupportedContentType`` ``UnsupportedRequestContentTypeRejection``, which lists the supported types
``MaformedContent``        ``MalformedRequestContentRejection``, with an error message and cause
========================== ============

Examples
--------

The following example uses ``spray-json`` to unmarshall a json request into a simple ``Person`` 
class.  It utilizes ``SprayJsonSupport`` via the ``PersonJsonSupport`` object as the in-scope unmarshaller.

... includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MarshallingDirectivesExamplesSpec.scala
   :snippet: person-case-class

... includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MarshallingDirectivesExamplesSpec.scala
   :snippet: person-json-support


... includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MarshallingDirectivesExamplesSpec.scala
   :snippet: example-entity-with-json

