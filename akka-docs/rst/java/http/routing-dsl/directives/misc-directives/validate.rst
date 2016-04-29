.. _-validate-java-:

validate
========
Allows validating a precondition before handling a route.

Description
-----------
Checks an arbitrary condition and passes control to the inner route if it returns ``true``.
Otherwise, rejects the request with a ``ValidationRejection`` containing the given error message.

Example
-------
TODO: Add example snippet.
.. 
.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala
  :snippet: validate-example
