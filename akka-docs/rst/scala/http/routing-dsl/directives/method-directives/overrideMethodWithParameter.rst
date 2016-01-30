.. _-overrideMethodWithParameter-:

overrideMethodWithParameter
===========================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/MethodDirectives.scala
   :snippet: overrideMethodWithParameter

Description
-----------
Changes the request method to the value of the specified query parameter.

Changes the HTTP method of the request to the value of the specified query string parameter.
If the query string parameter is not specified this directive has no effect.

If the query string is specified as something that is not a HTTP method,
then this directive completes the request with a ``501 Not Implemented`` response.

This directive is useful for:

- Use in combination with JSONP (JSONP only supports GET)
- Supporting older browsers that lack support for certain HTTP methods. E.g. IE8 does not support PATCH

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MethodDirectivesExamplesSpec.scala
   :snippet: overrideMethodWithParameter-0
