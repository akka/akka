.. _-extractMethod-java-:

extractMethod
=============

Description
-----------

Extracts the :class:`HttpMethod` from the request context and provides it for use for other directives explicitly.

Example
-------

In the below example our route first matches all ``GET`` requests, and if an incoming request wasn't a ``GET``,
the matching continues and the extractMethod route will be applied which we can use to programatically
print what type of request it was - independent of what actual HttpMethod it was:

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/MethodDirectivesExamplesTest.java#extractMethod

