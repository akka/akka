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

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java#withMaterializer
