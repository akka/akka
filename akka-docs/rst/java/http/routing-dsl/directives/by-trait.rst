Predefined Directives (by trait)
================================

All predefined directives are organized into traits that form one part of the overarching ``Directives`` trait.

.. _Request Directives-java:

Directives filtering or extracting from the request
---------------------------------------------------

:ref:`MethodDirectives-java`
  Filter and extract based on the request method.

:ref:`HeaderDirectives-java`
  Filter and extract based on request headers.

:ref:`PathDirectives-java`
  Filter and extract from the request URI path.

:ref:`HostDirectives-java`
  Filter and extract based on the target host.

:ref:`ParameterDirectives-java`, :ref:`FormFieldDirectives-java`
  Filter and extract based on query parameters or form fields.

:ref:`CodingDirectives-java`
  Filter and decode compressed request content.

:ref:`MarshallingDirectives-java`
  Extract the request entity.

:ref:`SchemeDirectives-java`
  Filter and extract based on the request scheme.

:ref:`SecurityDirectives-java`
  Handle authentication data from the request.

:ref:`CookieDirectives-java`
  Filter and extract cookies.

:ref:`BasicDirectives-java` and :ref:`MiscDirectives-java`
  Directives handling request properties.

:ref:`FileUploadDirectives-java`
  Handle file uploads.


.. _Response Directives-java:

Directives creating or transforming the response
------------------------------------------------

:ref:`CacheConditionDirectives-java`
  Support for conditional requests (``304 Not Modified`` responses).

:ref:`CookieDirectives-java`
  Set, modify, or delete cookies.

:ref:`CodingDirectives-java`
  Compress responses.

:ref:`FileAndResourceDirectives-java`
  Deliver responses from files and resources.

:ref:`RangeDirectives-java`
  Support for range requests (``206 Partial Content`` responses).

:ref:`RespondWithDirectives-java`
  Change response properties.

:ref:`RouteDirectives-java`
  Complete or reject a request with a response.

:ref:`BasicDirectives-java` and :ref:`MiscDirectives-java`
  Directives handling or transforming response properties.

:ref:`TimeoutDirectives-java`
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
