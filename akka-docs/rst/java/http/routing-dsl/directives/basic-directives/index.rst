.. _BasicDirectives-java:

BasicDirectives
===============

Basic directives are building blocks for building :ref:`Custom Directives`. As such they
usually aren't used in a route directly but rather in the definition of new directives.


.. _ProvideDirectives-java:

Providing Values to Inner Routes
--------------------------------

These directives provide values to the inner routes with extractions. They can be distinguished
on two axes: a) provide a constant value or extract a value from the ``RequestContext`` b) provide
a single value or a tuple of values.

  * :ref:`-extract-java-`
  * :ref:`-extractExecutionContext-java-`
  * :ref:`-extractMaterializer-java-`
  * :ref:`-extractLog-java-`
  * :ref:`-extractRequest-java-`
  * :ref:`-extractRequestContext-java-`
  * :ref:`-extractSettings-java-`
  * :ref:`-extractUnmatchedPath-java-`
  * :ref:`-extractUri-java-`
  * :ref:`-provide-java-`


.. _Request Transforming Directives-java:

Transforming the Request(Context)
---------------------------------

  * :ref:`-mapRequest-java-`
  * :ref:`-mapRequestContext-java-`
  * :ref:`-mapSettings-java-`
  * :ref:`-mapUnmatchedPath-java-`
  * :ref:`-withExecutionContext-java-`
  * :ref:`-withMaterializer-java-`
  * :ref:`-withLog-java-`
  * :ref:`-withSettings-java-`


.. _Response Transforming Directives-java:

Transforming the Response
-------------------------

These directives allow to hook into the response path and transform the complete response or
the parts of a response or the list of rejections:

  * :ref:`-mapResponse-java-`
  * :ref:`-mapResponseEntity-java-`
  * :ref:`-mapResponseHeaders-java-`


.. _Result Transformation Directives-java:

Transforming the RouteResult
----------------------------

These directives allow to transform the RouteResult of the inner route.

  * :ref:`-cancelRejection-java-`
  * :ref:`-cancelRejections-java-`
  * :ref:`-mapRejections-java-`
  * :ref:`-mapRouteResult-java-`
  * :ref:`-mapRouteResultFuture-java-`
  * :ref:`-mapRouteResultPF-java-`
  * :ref:`-mapRouteResultWith-java-`
  * :ref:`-mapRouteResultWithPF-java-`
  * :ref:`-recoverRejections-java-`
  * :ref:`-recoverRejectionsWith-java-`


Other
-----

  * :ref:`-mapInnerRoute-java-`
  * :ref:`-pass-java-`


Alphabetically
--------------

.. toctree::
   :maxdepth: 1

   cancelRejection
   cancelRejections
   extract
   extractExecutionContext
   extractMaterializer
   extractLog
   extractRequest
   extractRequestContext
   extractSettings
   extractUnmatchedPath
   extractUri
   mapInnerRoute
   mapRejections
   mapRequest
   mapRequestContext
   mapResponse
   mapResponseEntity
   mapResponseHeaders
   mapRouteResult
   mapRouteResultFuture
   mapRouteResultPF
   mapRouteResultWith
   mapRouteResultWithPF
   mapSettings
   mapUnmatchedPath
   pass
   provide
   recoverRejections
   recoverRejectionsWith
   withExecutionContext
   withMaterializer
   withLog
   withSettings
