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
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
