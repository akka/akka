Predefined Directives (by trait)
================================

All predefined directives are organized into traits that form one part of the overarching ``Directives`` trait.

.. _Request Directives:

Directives filtering or extracting from the request
---------------------------------------------------

:ref:`MethodDirectives`
  Filter and extract based on the request method.

:ref:`HeaderDirectives`
  Filter and extract based on request headers.

:ref:`PathDirectives`
  Filter and extract from the request URI path.

:ref:`HostDirectives`
  Filter and extract based on the target host.

:ref:`ParameterDirectives`, :ref:`FormFieldDirectives`
  Filter and extract based on query parameters or form fields.

:ref:`CodingDirectives`
  Filter and decode compressed request content.

:ref:`MarshallingDirectives`
  Extract the request entity.

:ref:`SchemeDirectives`
  Filter and extract based on the request scheme.

:ref:`SecurityDirectives`
  Handle authentication data from the request.

:ref:`CookieDirectives`
  Filter and extract cookies.

:ref:`BasicDirectives` and :ref:`MiscDirectives`
  Directives handling request properties.

:ref:`FileUploadDirectives`
  Handle file uploads.


.. _Response Directives:

Directives creating or transforming the response
------------------------------------------------

:ref:`CacheConditionDirectives`
  Support for conditional requests (``304 Not Modified`` responses).

:ref:`CookieDirectives`
  Set, modify, or delete cookies.

:ref:`CodingDirectives`
  Compress responses.

:ref:`FileAndResourceDirectives`
  Deliver responses from files and resources.

:ref:`RangeDirectives`
  Support for range requests (``206 Partial Content`` responses).

:ref:`RespondWithDirectives`
  Change response properties.

:ref:`RouteDirectives`
  Complete or reject a request with a response.

:ref:`BasicDirectives` and :ref:`MiscDirectives`
  Directives handling or transforming response properties.

:ref:`TimeoutDirectives`
  Configure request timeouts and automatic timeout responses.


List of predefined directives by trait
--------------------------------------

.. toctree::
   :maxdepth: 1

   basic-directives/index
   cache-condition-directives/index
   coding-directives/index
   cookie-directives/index
   debugging-directives/index
   execution-directives/index
   file-and-resource-directives/index
   file-upload-directives/index
   form-field-directives/index
   future-directives/index
   header-directives/index
   host-directives/index
   marshalling-directives/index
   method-directives/index
   misc-directives/index
   parameter-directives/index
   path-directives/index
   range-directives/index
   respond-with-directives/index
   route-directives/index
   scheme-directives/index
   security-directives/index
   websocket-directives/index
   timeout-directives/index
