.. _akka-http-spray-json:

JSON Support
============

Akka HTTP's :ref:`marshalling <http-marshalling-scala>` and :ref:`unmarshalling <http-unmarshalling-scala>`
infrastructure makes it rather easy to seamlessly support specific wire representations of your data objects, like JSON,
XML or even binary encodings.

For JSON Akka HTTP currently provides support for `spray-json`_ right out of the box through it's
``akka-http-spray-json`` module.

Other JSON libraries are supported by the community.
See `the list of current community extensions for Akka HTTP`_.

.. _`the list of current community extensions for Akka HTTP`: http://akka.io/community/#extensions-to-akka-http

spray-json Support
------------------

The SprayJsonSupport_ trait provides a ``FromEntityUnmarshaller[T]`` and ``ToEntityMarshaller[T]`` for every type ``T``
that an implicit ``spray.json.RootJsonReader`` and/or ``spray.json.RootJsonWriter`` (respectively) is available for.

This is how you enable automatic support for (un)marshalling from and to JSON with `spray-json`_:

1. Add a library dependency onto ``"com.typesafe.akka" %% "akka-http-spray-json-experimental" % "@version@"``.

2. ``import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._`` or mix in the
   ``akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport`` trait.

3. Provide a ``RootJsonFormat[T]`` for your type and bring it into scope.
   Check out the `spray-json`_ documentation for more info on how to do this.

Once you have done this (un)marshalling between JSON and your type ``T`` should work nicely and transparently.

.. includecode:: ../../code/docs/http/scaladsl/SprayJsonExampleSpec.scala
  :include: example

To learn more about how spray-json works please refer to its `documentation <https://github.com/spray/spray-json>`_.


.. _spray-json: https://github.com/spray/spray-json
.. _SprayJsonSupport: @github@/akka-http-marshallers-scala/akka-http-spray-json/src/main/scala/akka/http/scaladsl/marshallers/sprayjson/SprayJsonSupport.scala
