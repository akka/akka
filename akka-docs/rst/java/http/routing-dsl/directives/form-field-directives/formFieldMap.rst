.. _-formFieldMap-java-:

formFieldMap
============

Description
-----------
Extracts all HTTP form fields at once as a ``Map<String, String>`` mapping form field names to form field values.

If form data contain a field value several times, the map will contain the last one.

See :ref:`-formFields-java-` for an in-depth description.

Warning
-------
Use of this directive can result in performance degradation or even in ``OutOfMemoryError`` s.
See :ref:`-formFieldList-java-` for details.

Example
-------
TODO: Add example snippet.
.. 
.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/FormFieldDirectivesExamplesSpec.scala
   :snippet: formFieldMap
