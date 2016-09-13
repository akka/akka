.. _-entity-java-:

entity
======

Description
-----------
Unmarshalls the request entity to the given type and passes it to its inner Route.  An unmarshaller
returns an ``Either`` with ``Right(value)`` if successful or ``Left(exception)`` for a failure.
The ``entity`` method will either pass the ``value`` to the inner route or map the ``exception`` to a
:class:``akka.http.javadsl.server.Rejection``.

The ``entity`` directive works in conjuction with ``as`` and ``akka.http.scaladsl.unmarshalling`` to
convert some serialized "wire format" value into a higher-level object structure.  
:ref:`The unmarshalling documentation <http-unmarshalling-java>` explains this process in detail.
This directive simplifies extraction and error handling to the specified type from the request.

An unmarshaller will return a ``Left(exception)`` in the case of an error.  This is converted to a
``akka.http.scaladsl.server.Rejection`` within the ``entity`` directive.  The following table lists how exceptions
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

TODO: Add example snippets.

It is also possible to use the ``entity`` directive to obtain raw ``JsValue`` ( spray-json_ ) objects, by simply using
``as[JsValue]``, or any other JSON type for which you have marshallers in-scope.

TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.

.. _spray-json: https://github.com/spray/spray-json
