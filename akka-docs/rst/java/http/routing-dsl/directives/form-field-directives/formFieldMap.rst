.. _-formFieldMap-java-:

formFieldMap
============

Description
-----------
Extracts all HTTP form fields at once as a ``Map<String, String>`` mapping form field names to form field values.

If form data contain a field value several times, the map will contain the last one.

Warning
-------
Use of this directive can result in performance degradation or even in ``OutOfMemoryError`` s.
See :ref:`-formFieldList-java-` for details.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
