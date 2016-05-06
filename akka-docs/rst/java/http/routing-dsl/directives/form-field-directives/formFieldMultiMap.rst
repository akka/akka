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
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
