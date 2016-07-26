.. _-extractRequestEntity-java-:

extractRequestEntity
====================

Description
-----------

Extracts the ``RequestEntity`` from the :class:`RequestContext`.

The directive returns a ``RequestEntity`` without unmarshalling the request. To extract domain entity,
:ref:`-entity-java-` should be used.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java#extractRequestEntity
