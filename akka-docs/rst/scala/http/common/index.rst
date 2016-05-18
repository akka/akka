.. _http-scala-common-scala:

Common Abstractions (Client- and Server-Side)
=============================================

HTTP and related specifications define a great number of concepts and functionality that is not specific to either
HTTP's client- or server-side since they are meaningful on both end of an HTTP connection.
The documentation for their counterparts in Akka HTTP lives in this section rather than in the ones for the
:ref:`Client-Side API <http-client-side>`, :ref:`http-low-level-server-side-api` or :ref:`http-high-level-server-side-api`,
which are specific to one side only.


.. toctree::
   :maxdepth: 2

   http-model
   marshalling
   unmarshalling
   de-coding
   json-support
   xml-support
   timeouts
