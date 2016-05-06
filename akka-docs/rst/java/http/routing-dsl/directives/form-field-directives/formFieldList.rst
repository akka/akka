.. _-formFieldList-java-:

formFieldList
=============

Description
-----------
Extracts all HTTP form fields at once in the original order as (name, value) tuples of type ``Map.Entry<String, String>``.

This directive can be used if the exact order of form fields is important or if parameters can occur several times.

Warning
-------
The directive reads all incoming HTT form fields without any configured upper bound.
It means, that requests with form fields holding significant amount of data (ie. during a file upload)
can cause performance issues or even an ``OutOfMemoryError`` s.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
