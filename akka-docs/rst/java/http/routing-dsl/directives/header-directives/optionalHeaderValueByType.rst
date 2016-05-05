.. _-optionalHeaderValueByType-java-:

optionalHeaderValueByType
=========================

Description
-----------
Optionally extracts the value of the HTTP request header of the given type.

The ``optionalHeaderValueByType`` directive is similar to the :ref:`-headerValueByType-java-` directive but always extracts
an ``Option`` value instead of rejecting the request if no matching header could be found.

.. note::
  Custom headers will only be matched by this directive if they extend ``ModeledCustomHeader``
  and provide a companion extending ``ModeledCustomHeaderCompanion``, otherwise the routing
  infrastructure does now know where to search for the needed companion and header name.

  To learn more about defining custom headers, read: :ref:`custom-headers-java`.

Example
-------
TODO: Add example snippet.
.. 
.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala
   :snippet: optionalHeaderValueByType-0
