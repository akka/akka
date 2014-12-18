.. _BasicDirectives:

BasicDirectives
===============

Basic directives are building blocks for building :ref:`Custom Directives`. As such they
usually aren't used in a route directly but rather in the definition of new directives.

.. _ProvideDirectives:

Directives to provide values to inner routes
--------------------------------------------

These directives allow to provide the inner routes with extractions. They can be distinguished
on two axes: a) provide a constant value or extract a value from the ``RequestContext`` b) provide
a single value or an HList of values.

  * :ref:`-extract-`
  * :ref:`-textract-`
  * :ref:`-provide-`
  * :ref:`-tprovide-`

.. _Request Transforming Directives:

Directives transforming the request
-----------------------------------

  * :ref:`-mapRequestContext-`
  * :ref:`-mapRequest-`

.. _Response Transforming Directives:

Directives transforming the response
------------------------------------

These directives allow to hook into the response path and transform the complete response or
the parts of a response or the list of rejections:

  * :ref:`-mapResponse-`
  * :ref:`-mapResponseEntity-`
  * :ref:`-mapResponseHeaders-`
  * :ref:`-mapRejections-`


.. _Result Transformation Directives:

Directives transforming the RouteResult
---------------------------------------

These directives allow to transform the RouteResult of the inner route.

  * :ref:`-mapRouteResult-`
  * :ref:`-mapRouteResponsePF-`

Directives changing the execution of the inner route
----------------------------------------------------

  * :ref:`-mapInnerRoute-`

Directives alphabetically
-------------------------

.. toctree::
   :maxdepth: 1

   extract
   mapInnerRoute
   mapRejections
   mapRequest
   mapRequestContext
   mapResponse
   mapResponseEntity
   mapResponseHeaders
   mapRouteResult
   mapRouteResultPF
   noop
   pass
   provide
   textract
   tprovide
