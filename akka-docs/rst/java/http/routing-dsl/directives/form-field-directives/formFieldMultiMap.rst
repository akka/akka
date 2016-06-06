.. _-formFieldMultiMap-java-:

formFieldMultiMap
=================

Description
-----------

Extracts all HTTP form fields at once as a multi-map of type ``Map<String, <List<String>>`` mapping
a form name to a list of all its values.

This directive can be used if form fields can occur several times.

The order of values is *not* specified.

Warning
-------
Use of this directive can result in performance degradation or even in ``OutOfMemoryError`` s.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/FormFieldDirectivesExamplesTest.java#formFieldMultiMap
