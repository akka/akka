.. _-formFieldList-java-:

formFieldSeq
============

Description
-----------
Extracts all HTTP form fields at once in the original order as (name, value) tuples of type ``(String, String)``.

This directive can be used if the exact order of form fields is important or if parameters can occur several times.

See :ref:`-formFields-java-` for an in-depth description.

Warning
-------
The directive reads all incoming HTT form fields without any configured upper bound.
It means, that requests with form fields holding significant amount of data (ie. during a file upload)
can cause performance issues or even an ``OutOfMemoryError`` s.

Example
-------
TODO: Add example snippet.
.. 
.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/FormFieldDirectivesExamplesSpec.scala
   :snippet: formFieldSeq
