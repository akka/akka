.. _-withMaterializer-java-:

withMaterializer
================

Description
-----------

Allows running an inner route using an alternative ``Materializer`` in place of the default one.

The materializer can be extracted in an inner route using :ref:`-extractMaterializer-java-` directly,
or used by directives which internally extract the materializer without sufracing this fact in the API
(e.g. responding with a Chunked entity).

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
