.. _akka-http-xml-marshalling:

XML Support
===========

Akka HTTP's :ref:`marshalling <http-marshalling-scala>` and :ref:`unmarshalling <http-unmarshalling-scala>`
infrastructure makes it rather easy to seamlessly support specific wire representations of your data objects, like JSON,
XML or even binary encodings.

For XML Akka HTTP currently provides support for `Scala XML`_ right out of the box through it's
``akka-http-xml`` module.


Scala XML Support
-----------------

The ScalaXmlSupport_ trait provides a ``FromEntityUnmarshaller[NodeSeq]`` and ``ToEntityMarshaller[NodeSeq]`` that
you can use directly or build upon.

This is how you enable support for (un)marshalling from and to JSON with `Scala XML`_ ``NodeSeq``:

1. Add a library dependency onto ``"com.typesafe.akka" %% "akka-http-xml-experimental" % "1.x"``.

2. ``import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._`` or mix in the
   ``akka.http.scaladsl.marshallers.xml.ScalaXmlSupport`` trait.

Once you have done this (un)marshalling between XML and ``NodeSeq`` instances should work nicely and transparently.


.. _Scala XML: https://github.com/scala/scala-xml
.. _ScalaXmlSupport: @github@/akka-http-marshallers-scala/akka-http-xml/src/main/scala/akka/http/scaladsl/marshallers/xml/ScalaXmlSupport.scala