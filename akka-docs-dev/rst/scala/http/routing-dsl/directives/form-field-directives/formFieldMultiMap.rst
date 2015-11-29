.. _-formFieldMultiMap-:

formFieldMultiMap
=================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/FormFieldDirectives.scala
   :snippet: formFieldMultiMap

Description
-----------

Extracts all HTTP form fields at once as a multi-map of type ``Map[String, List[String]`` mapping
a form name to a list of all its values.

This directive can be used if form fields can occur several times.

The order of values is *not* specified.

See :ref:`-formFields-` for an in-depth description.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/FormFieldDirectivesExamplesSpec.scala
   :snippet: formFieldMultiMap
