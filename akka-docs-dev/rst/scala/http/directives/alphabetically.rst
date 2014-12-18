.. _Predefined Directives:

Predefined Directives (alphabetically)
======================================

.. rst-class:: table table-striped

====================================== =================================================================================
Directive                              Description
====================================== =================================================================================
:ref:`-cancelAllRejections-`           Adds a ``TransformationRejection`` to rejections from its inner Route, which
                                       cancels other rejections according to a predicate function
:ref:`-cancelRejection-`               Adds a ``TransformationRejection`` cancelling all rejections equal to a given one
:ref:`-clientIP-`                      Extracts the IP address of the client from either the ``X-Forwarded-For``,
                                       ``Remote-Address`` or ``X-Real-IP`` request header
:ref:`-complete-`                      Completes the request with a given response, several overloads
:ref:`-compressResponse-`              Compresses responses coming back from its inner Route using either ``Gzip`` or
                                       ``Deflate`` unless the request explicitly sets ``Accept-Encoding`` to ``identity``.
:ref:`-compressResponseIfRequested-`   Compresses responses coming back from its inner Route using either ``Gzip`` or
                                       ``Deflate``, but only when the request explicitly accepts one of them.
:ref:`-conditional-`                   Depending on the given ETag and Last-Modified values responds with
                                       ``304 Not Modified`` if the request comes with the respective conditional headers.
:ref:`-cookie-`                        Extracts an ``HttpCookie`` with a given name or rejects if no such cookie is
                                       present in the request
:ref:`-decodeRequest-`                 Decompresses incoming requests using a given Decoder
:ref:`-decompressRequest-`             Decompresses incoming requests using either ``Gzip``, ``Deflate``, or ``NoEncoding``
:ref:`-delete-`                        Rejects all non-DELETE requests
:ref:`-deleteCookie-`                  Adds a ``Set-Cookie`` header expiring the given cookie to all ``HttpResponse``
                                       replies of its inner Route
:ref:`-encodeResponse-`                Compresses responses coming back from its inner Route using a given Encoder
:ref:`-entity-`                        Unmarshalls the requests entity according to a given definition, rejects in
                                       case of problems
:ref:`-extract-`                       Extracts a single value from the ``RequestContext`` using a function
                                       ``RequestContext => T``
:ref:`-extractRequest-`                Extracts the complete request
:ref:`-failWith-`                      Bubbles the given error up the response chain, where it is dealt with by the
                                       closest :ref:`-handleExceptions-` directive and its ExceptionHandler
:ref:`-formField-`                     Extracts the value of an HTTP form field, rejects if the request doesn't come
                                       with a field matching the definition
:ref:`-formFields-`                    Same as :ref:`-formField-`, except for several fields at once
:ref:`-get-`                           Rejects all non-GET requests
:ref:`-getFromBrowseableDirectories-`  Same as :ref:`-getFromBrowseableDirectory-`, but allows for serving the "union"
                                       of several directories as one single "virtual" one
:ref:`-getFromBrowseableDirectory-`    Completes GET requests with the content of a file underneath a given directory,
                                       renders directory contents as browsable listings
:ref:`-getFromDirectory-`              Completes GET requests with the content of a file underneath a given directory
:ref:`-getFromFile-`                   Completes GET requests with the content of a given file
:ref:`-getFromResource-`               Completes GET requests with the content of a given resource
:ref:`-getFromResourceDirectory-`      Same as :ref:`-getFromDirectory-` except that the file is not fetched from the
                                       file system but rather from a "resource directory"
:ref:`-handleExceptions-`              Converts exceptions thrown during evaluation of its inner Route into
                                       ``HttpResponse`` replies using a given ExceptionHandler
:ref:`-handleRejections-`              Converts rejections produced by its inner Route into ``HttpResponse`` replies
                                       using a given RejectionHandler
:ref:`-handleWith-`                    Completes the request using a given function. Uses the in-scope ``Unmarshaller``
                                       and ``Marshaller`` for converting to and from the function
:ref:`-head-`                          Rejects all non-HEAD requests
:ref:`-headerValue-`                   Extracts an HTTP header value using a given function, rejects if no value can
                                       be extracted
:ref:`-headerValueByName-`             Extracts an HTTP header value by selecting a header by name
:ref:`-headerValueByType-`             Extracts an HTTP header value by selecting a header by type
:ref:`-headerValuePF-`                 Same as :ref:`-headerValue-`, but with a ``PartialFunction``
:ref:`-host-`                          Rejects all requests with a hostname different from a given definition,
                                       can extract the hostname using a regex pattern
:ref:`-hostName-`                      Extracts the hostname part of the requests ``Host`` header value
:ref:`-listDirectoryContents-`         Completes GET requests with a unified listing of the contents of one or more
                                       given directories
:ref:`-logRequest-`                    Produces a log entry for every incoming request
:ref:`-logRequestResult-`              Produces a log entry for every response or rejection coming back from its inner
                                       route, allowing for coalescing with the corresponding request
:ref:`-logResult-`                     Produces a log entry for every response or rejection coming back from its inner
                                       route
:ref:`-mapResponse-`                   Transforms the ``HttpResponse`` coming back from its inner Route
:ref:`-mapResponseEntity-`             Transforms the entity of the ``HttpResponse`` coming back from its inner Route
:ref:`-mapResponseHeaders-`            Transforms the headers of the ``HttpResponse`` coming back from its inner Route
:ref:`-mapInnerRoute-`                 Transforms its inner Route with a ``Route => Route`` function
:ref:`-mapRejections-`                 Transforms all rejections coming back from its inner Route
:ref:`-mapRequest-`                    Transforms the incoming ``HttpRequest``
:ref:`-mapRequestContext-`             Transforms the ``RequestContext``
:ref:`-mapRouteResult-`                Transforms all responses coming back from its inner Route with a ``Any => Any``
                                       function
:ref:`-mapRouteResultPF-`              Same as :ref:`-mapRouteResult-`, but with a ``PartialFunction``
:ref:`-method-`                        Rejects if the request method does not match a given one
:ref:`-overrideMethodWithParameter-`   Changes the HTTP method of the request to the value of the specified query string
                                       parameter
:ref:`-onComplete-`                    "Unwraps" a ``Future[T]`` and runs its inner route after future completion with
                                       the future's value as an extraction of type ``Try[T]``
:ref:`-onFailure-`                     "Unwraps" a ``Future[T]`` and runs its inner route when the future has failed
                                       with the future's failure exception as an extraction of type ``Throwable``
:ref:`-onSuccess-`                     "Unwraps" a ``Future[T]`` and runs its inner route after future completion with
                                       the future's value as an extraction of type ``T``
:ref:`-optionalCookie-`                Extracts an ``HttpCookie`` with a given name, if the cookie is not present in the
                                       request extracts ``None``
:ref:`-optionalHeaderValue-`           Extracts an optional HTTP header value using a given function
:ref:`-optionalHeaderValueByName-`     Extracts an optional HTTP header value by selecting a header by name
:ref:`-optionalHeaderValueByType-`     Extracts an optional HTTP header value by selecting a header by type
:ref:`-optionalHeaderValuePF-`         Extracts an optional HTTP header value using a given partial function
:ref:`-options-`                       Rejects all non-OPTIONS requests
:ref:`-parameter-`                     Extracts the value of a request query parameter, rejects if the request doesn't
                                       come with a parameter matching the definition
:ref:`-parameterMap-`                  Extracts the requests query parameters as a ``Map[String, String]``
:ref:`-parameterMultiMap-`             Extracts the requests query parameters as a ``Map[String, List[String]]``
:ref:`-parameters-`                    Same as :ref:`-parameter-`, except for several parameters at once
:ref:`-parameterSeq-`                  Extracts the requests query parameters as a ``Seq[(String, String)]``
:ref:`-pass-`                          Does nothing, i.e. passes the ``RequestContext`` unchanged to its inner Route
:ref:`-patch-`                         Rejects all non-PATCH requests
:ref:`-path-`                          Extracts zero+ values from the ``unmatchedPath`` of the ``RequestContext``
                                       according to a given ``PathMatcher``, rejects if no match
:ref:`-pathEnd-`                       Only passes on the request to its inner route if the request path has been
                                       matched completely, rejects otherwise
:ref:`-pathEndOrSingleSlash-`          Only passes on the request to its inner route if the request path has been matched
                                       completely or only consists of exactly one remaining slash, rejects otherwise
:ref:`-pathPrefix-`                    Same as :ref:`-path-`, but also matches (and consumes) prefixes of the unmatched
                                       path (rather than only the complete unmatched path at once)
:ref:`-pathPrefixTest-`                Like :ref:`-pathPrefix-` but without "consumption" of the matched path (prefix).
:ref:`-pathSingleSlash-`               Only passes on the request to its inner route if the request path consists of
                                       exactly one remaining slash
:ref:`-pathSuffix-`                    Like as :ref:`-pathPrefix-`, but for suffixes rather than prefixed of the
                                       unmatched path
:ref:`-pathSuffixTest-`                Like :ref:`-pathSuffix-` but without "consumption" of the matched path (suffix).
:ref:`-post-`                          Rejects all non-POST requests
:ref:`-produce-`                       Uses the in-scope marshaller to extract a function that can be used for
                                       completing the request with an instance of a custom type
:ref:`-provide-`                       Injects a single value into a directive, which provides it as an extraction
:ref:`-put-`                           Rejects all non-PUT requests
:ref:`-rawPathPrefix-`                 Applies a given ``PathMatcher`` directly to the unmatched path of the
                                       ``RequestContext``, i.e. without implicitly consuming a leading slash
:ref:`-rawPathPrefixTest-`             Checks whether the unmatchedPath of the ``RequestContext`` has a prefix matched
                                       by a ``PathMatcher``
:ref:`-redirect-`                      Completes the request with redirection response of the given type to a given URI
:ref:`-reject-`                        Rejects the request with a given set of rejections
:ref:`-rejectEmptyResponse-`           Converts responses with an empty entity into a rejection
:ref:`-requestEncodedWith-`            Rejects the request if its encoding doesn't match a given one
:ref:`-requestEntityEmpty-`            Rejects the request if its entity is not empty
:ref:`-requestEntityPresent-`          Rejects the request if its entity is empty
:ref:`-requestUri-`                    Extracts the complete request URI
:ref:`-respondWithHeader-`             Adds a given response header to all ``HttpResponse`` replies from its inner
                                       Route
:ref:`-respondWithHeaders-`            Same as :ref:`-respondWithHeader-`, but for several headers at once
:ref:`-respondWithMediaType-`          Overrides the media-type of all ``HttpResponse`` replies from its inner Route,
                                       rejects if the media-type is not accepted by the client
:ref:`-respondWithSingletonHeader-`    Adds a given response header to all ``HttpResponse`` replies from its inner
                                       Route, if a header with the same name is not yet present
:ref:`-respondWithSingletonHeaders-`   Same as :ref:`-respondWithSingletonHeader-`, but for several headers at once
:ref:`-respondWithStatus-`             Overrides the response status of all ``HttpResponse`` replies coming back from
                                       its inner Route
:ref:`-responseEncodingAccepted-`      Rejects the request if the client doesn't accept a given encoding for the
                                       response
:ref:`-mapUnmatchedPath-`              Transforms the ``unmatchedPath`` of the ``RequestContext`` using a given function
:ref:`-scheme-`                        Rejects a request if its Uri scheme does not match a given one
:ref:`-schemeName-`                    Extracts the request Uri scheme
:ref:`-setCookie-`                     Adds a ``Set-Cookie`` header to all ``HttpResponse`` replies of its inner Route
:ref:`-textract-`                      Extracts a ``TupleN`` of values from the ``RequestContext`` using a function
:ref:`-hprovide-`                      Injects a ``TupleN`` of values into a directive, which provides them as
                                       extractions
:ref:`-unmatchedPath-`                 Extracts the unmatched path from the RequestContext
:ref:`-validate-`                      Passes or rejects the request depending on evaluation of a given conditional
                                       expression
:ref:`-withRangeSupport-`              Transforms the response from its inner route into a ``206 Partial Content``
                                       response if the client requested only part of the resource with a ``Range`` header.
====================================== =================================================================================
