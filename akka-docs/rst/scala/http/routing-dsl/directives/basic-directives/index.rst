.. _BasicDirectives:

BasicDirectives
===============

Basic directives are building blocks for building :ref:`Custom Directives`. As such they
usually aren't used in a route directly but rather in the definition of new directives.


.. _ProvideDirectives:

Providing Values to Inner Routes
--------------------------------

These directives provide values to the inner routes with extractions. They can be distinguished
on two axes: a) provide a constant value or extract a value from the ``RequestContext`` b) provide
a single value or a tuple of values.

  * :ref:`-extract-`
  * :ref:`-extractDataBytes-`
  * :ref:`-extractExecutionContext-`
  * :ref:`-extractMaterializer-`
  * :ref:`-extractLog-`
  * :ref:`-extractRequest-`
  * :ref:`-extractRequestContext-`
  * :ref:`-extractRequestEntity-`
  * :ref:`-extractSettings-`
  * :ref:`-extractUnmatchedPath-`
  * :ref:`-extractUri-`
  * :ref:`-textract-`
  * :ref:`-provide-`
  * :ref:`-tprovide-`


.. _Request Transforming Directives:

Transforming the Request(Context)
---------------------------------

  * :ref:`-mapRequest-`
  * :ref:`-mapRequestContext-`
  * :ref:`-mapSettings-`
  * :ref:`-mapUnmatchedPath-`
  * :ref:`-withExecutionContext-`
  * :ref:`-withMaterializer-`
  * :ref:`-withLog-`
  * :ref:`-withSettings-`


.. _Response Transforming Directives:

Transforming the Response
-------------------------

These directives allow to hook into the response path and transform the complete response or
the parts of a response or the list of rejections:

  * :ref:`-mapResponse-`
  * :ref:`-mapResponseEntity-`
  * :ref:`-mapResponseHeaders-`


.. _Result Transformation Directives:

Transforming the RouteResult
----------------------------

These directives allow to transform the RouteResult of the inner route.

  * :ref:`-cancelRejection-`
  * :ref:`-cancelRejections-`
  * :ref:`-mapRejections-`
  * :ref:`-mapRouteResult-`
  * :ref:`-mapRouteResultFuture-`
  * :ref:`-mapRouteResultPF-`
  * :ref:`-mapRouteResultWith-`
  * :ref:`-mapRouteResultWithPF-`
  * :ref:`-recoverRejections-`
  * :ref:`-recoverRejectionsWith-`


Other
-----

  * :ref:`-mapInnerRoute-`
  * :ref:`-pass-`


Alphabetically
--------------

.. toctree::
   :maxdepth: 1

   cancelRejection
   cancelRejections
   extract
   extractExecutionContext
   extractDataBytes
   extractMaterializer
   extractLog
   extractRequest
   extractRequestContext
   extractRequestEntity
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
   textract
   tprovide
   withExecutionContext
   withMaterializer
   withLog
   withSettings
