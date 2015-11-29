.. _-formFieldMap-:

formFieldMap
============

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/FormFieldDirectives.scala
   :snippet: formFieldMap

Description
-----------
Extracts all HTTP form fields at once as a ``Map[String, String]`` mapping form field names to form field values.

If form data contain a field value several times, the map will contain the last one.

See :ref:`-formFields-` for an in-depth description.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala
   :snippet: parameterMap
