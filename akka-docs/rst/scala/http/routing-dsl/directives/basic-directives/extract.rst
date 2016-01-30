.. _-extract-:

extract
=======

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: extract

Description
-----------

The ``extract`` directive is used as a building block for :ref:`Custom Directives` to extract data from the
``RequestContext`` and provide it to the inner route. It is a special case for extracting one value of the more
general :ref:`-textract-` directive that can be used to extract more than one value.

See :ref:`ProvideDirectives` for an overview of similar directives.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: 0extract
