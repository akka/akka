.. _Predefined Directives-java:

Predefined Directives (alphabetically)
======================================

================================================ ============================================================================
Directive                                        Description
================================================ ============================================================================
:ref:`-authenticateBasic-java-`                  Wraps the inner route with Http Basic authentication support using a given ``Authenticator<T>``
:ref:`-authenticateBasicAsync-java-`             Wraps the inner route with Http Basic authentication support using a given ``AsyncAuthenticator<T>``
:ref:`-authenticateBasicPF-java-`                Wraps the inner route with Http Basic authentication support using a given ``AuthenticatorPF<T>``
:ref:`-authenticateBasicPFAsync-java-`           Wraps the inner route with Http Basic authentication support using a given ``AsyncAuthenticatorPF<T>``
:ref:`-authenticateOAuth2-java-`                 Wraps the inner route with OAuth Bearer Token authentication support using a given ``AuthenticatorPF<T>``
:ref:`-authenticateOAuth2Async-java-`            Wraps the inner route with OAuth Bearer Token authentication support using a given ``AsyncAuthenticator<T>``
:ref:`-authenticateOAuth2PF-java-`               Wraps the inner route with OAuth Bearer Token authentication support using a given ``AuthenticatorPF<T>``
:ref:`-authenticateOAuth2PFAsync-java-`          Wraps the inner route with OAuth Bearer Token authentication support using a given ``AsyncAuthenticatorPF<T>``
:ref:`-authenticateOrRejectWithChallenge-java-`  Lifts an authenticator function into a directive 
:ref:`-authorize-java-`                          Applies the given authorization check to the request
:ref:`-authorizeAsync-java-`                     Applies the given asynchronous authorization check to the request 
:ref:`-cancelRejection-java-`                    Adds a ``TransformationRejection`` cancelling all rejections equal to the given one to the rejections potentially coming back from the inner route. 
:ref:`-cancelRejections-java-`                   Adds a ``TransformationRejection`` cancelling all matching rejections to the rejections potentially coming back from the inner route
:ref:`-complete-java-`                           Completes the request using the given arguments
:ref:`-completeOrRecoverWith-java-`              "Unwraps" a ``CompletionStage<T>`` and runs the inner route when the future has failed with the error as an extraction of type ``Throwable``
:ref:`-completeWith-java-`                       Uses the marshaller for a given type to extract a completion function
:ref:`-conditional-java-`                        Wraps its inner route with support for conditional requests as defined by http://tools.ietf.org/html/rfc7232
:ref:`-cookie-java-`                             Extracts the ``HttpCookie`` with the given name
:ref:`-decodeRequest-java-`                      Decompresses the  request if it is ``gzip`` or ``deflate`` compressed
:ref:`-decodeRequestWith-java-`                  Decodes the incoming request using one of the given decoders
:ref:`-delete-java-`                             Rejects all non-DELETE requests
:ref:`-deleteCookie-java-`                       Adds a ``Set-Cookie`` response header expiring the given cookies
:ref:`-encodeResponse-java-`                     Encodes the response with the encoding that is requested by the client via the ``Accept-Encoding`` header (``NoCoding``, ``Gzip`` and ``Deflate``)
:ref:`-encodeResponseWith-java-`                 Encodes the response with the encoding that is requested by the client via the ``Accept-Encoding`` header (from a user-defined set)
:ref:`-entity-java-`                             Extracts the request entity unmarshalled to a given type
:ref:`-extract-java-`                            Extracts a single value using a ``RequestContext ⇒ T`` function
:ref:`-extractClientIP-java-`                    Extracts the client's IP from either the ``X-Forwarded-``, ``Remote-Address`` or ``X-Real-IP`` header
:ref:`-extractCredentials-java-`                 Extracts the potentially present ``HttpCredentials`` provided with the request's ``Authorization`` header
:ref:`-extractExecutionContext-java-`            Extracts the ``ExecutionContext`` from the ``RequestContext``
:ref:`-extractMaterializer-java-`                Extracts the ``Materializer`` from the ``RequestContext``
:ref:`-extractHost-java-`                        Extracts the hostname part of the Host request header value
:ref:`-extractLog-java-`                         Extracts the ``LoggingAdapter`` from the ``RequestContext``
:ref:`-extractMethod-java-`                      Extracts the request method
:ref:`-extractRequest-java-`                     Extracts the current ``HttpRequest`` instance
:ref:`-extractRequestContext-java-`              Extracts the ``RequestContext`` itself
:ref:`-extractScheme-java-`                      Extracts the URI scheme from the request
:ref:`-extractSettings-java-`                    Extracts the ``RoutingSettings`` from the ``RequestContext``
:ref:`-extractUnmatchedPath-java-`               Extracts the yet unmatched path from the ``RequestContext``
:ref:`-extractUri-java-`                         Extracts the complete request URI
:ref:`-failWith-java-`                           Bubbles the given error up the response chain where it is dealt with by the closest :ref:`-handleExceptions-java-` directive and its ``ExceptionHandler``
:ref:`-fileUpload-java-`                         Provides a stream of an uploaded file from a multipart request
:ref:`-formField-java-`                          Extracts an HTTP form field from the request
:ref:`-formFieldMap-java-`                       Extracts a number of HTTP form field from the request as a ``Map<String, String>``
:ref:`-formFieldMultiMap-java-`                  Extracts a number of HTTP form field from the request as a ``Map<String, List<String>``
:ref:`-formFieldList-java-`                      Extracts a number of HTTP form field from the request as a ``List<Pair<String, String>>``
:ref:`-get-java-`                                Rejects all non-GET requests
:ref:`-getFromBrowseableDirectories-java-`       Serves the content of the given directories as a file-system browser, i.e. files are sent and directories served as browseable listings
:ref:`-getFromBrowseableDirectory-java-`         Serves the content of the given directory as a file-system browser, i.e. files are sent and directories served as browseable listings
:ref:`-getFromDirectory-java-`                   Completes GET requests with the content of a file underneath a given file-system directory
:ref:`-getFromFile-java-`                        Completes GET requests with the content of a given file
:ref:`-getFromResource-java-`                    Completes GET requests with the content of a given class-path resource
:ref:`-getFromResourceDirectory-java-`           Completes GET requests with the content of a file underneath a given "class-path resource directory"
:ref:`-handleExceptions-java-`                   Transforms exceptions thrown during evaluation of the inner route using the given ``ExceptionHandler``
:ref:`-handleRejections-java-`                   Transforms rejections produced by the inner route using the given ``RejectionHandler``
:ref:`-handleWebSocketMessages-java-`            Handles websocket requests with the given handler and rejects other requests with an ``ExpectedWebSocketRequestRejection``
:ref:`-handleWebSocketMessagesForProtocol-java-` Handles websocket requests with the given handler if the subprotocol matches and rejects other requests with an ``ExpectedWebSocketRequestRejection`` or an ``UnsupportedWebSocketSubprotocolRejection``.
:ref:`-handleWith-java-`                         Completes the request using a given function
:ref:`-head-java-`                               Rejects all non-HEAD requests
:ref:`-headerValue-java-`                        Extracts an HTTP header value using a given ``HttpHeader ⇒ Option<T>`` function
:ref:`-headerValueByName-java-`                  Extracts the value of the first HTTP request header with a given name
:ref:`-headerValueByType-java-`                  Extracts the first HTTP request header of the given type
:ref:`-headerValuePF-java-`                      Extracts an HTTP header value using a given ``PartialFunction<HttpHeader, T>``
:ref:`-host-java-`                               Rejects all requests with a non-matching host name
:ref:`-listDirectoryContents-java-`              Completes GET requests with a unified listing of the contents of all given file-system directories
:ref:`-logRequest-java-`                         Produces a log entry for every incoming request
:ref:`-logRequestResult-java-`                   Produces a log entry for every incoming request and ``RouteResult``
:ref:`-logResult-java-`                          Produces a log entry for every ``RouteResult``
:ref:`-mapInnerRoute-java-`                      Transforms its inner ``Route`` with a ``Route => Route`` function
:ref:`-mapRejections-java-`                      Transforms rejections from a previous route with an ``List<Rejection] ⇒ List<Rejection>`` function
:ref:`-mapRequest-java-`                         Transforms the request with an ``HttpRequest => HttpRequest`` function
:ref:`-mapRequestContext-java-`                  Transforms the ``RequestContext`` with a ``RequestContext => RequestContext`` function
:ref:`-mapResponse-java-`                        Transforms the response with an ``HttpResponse => HttpResponse`` function
:ref:`-mapResponseEntity-java-`                  Transforms the response entity with an ``ResponseEntity ⇒ ResponseEntity`` function
:ref:`-mapResponseHeaders-java-`                 Transforms the response headers with an ``List<HttpHeader] ⇒ List<HttpHeader>`` function
:ref:`-mapRouteResult-java-`                     Transforms the ``RouteResult`` with a ``RouteResult ⇒ RouteResult`` function
:ref:`-mapRouteResultFuture-java-`               Transforms the ``RouteResult`` future with a ``CompletionStage<RouteResult] ⇒ CompletionStage<RouteResult>`` function
:ref:`-mapRouteResultPF-java-`                   Transforms the ``RouteResult`` with a ``PartialFunction<RouteResult, RouteResult>``
:ref:`-mapRouteResultWith-java-`                 Transforms the ``RouteResult`` with a ``RouteResult ⇒ CompletionStage<RouteResult>`` function
:ref:`-mapRouteResultWithPF-java-`               Transforms the ``RouteResult`` with a ``PartialFunction<RouteResult, CompletionStage<RouteResult]>``
:ref:`-mapSettings-java-`                        Transforms the ``RoutingSettings`` with a ``RoutingSettings ⇒ RoutingSettings`` function
:ref:`-mapUnmatchedPath-java-`                   Transforms the ``unmatchedPath`` of the ``RequestContext`` using a ``Uri.Path ⇒ Uri.Path`` function
:ref:`-method-java-`                             Rejects all requests whose HTTP method does not match the given one
:ref:`-onComplete-java-`                         "Unwraps" a ``CompletionStage<T>`` and runs the inner route after future completion with the future's value as an extraction of type ``Try<T>``
:ref:`-onSuccess-java-`                          "Unwraps" a ``CompletionStage<T>`` and runs the inner route after future completion with the future's value as an extraction of type ``T``
:ref:`-optionalCookie-java-`                     Extracts the ``HttpCookiePair`` with the given name as an ``Option<HttpCookiePair>``
:ref:`-optionalHeaderValue-java-`                Extracts an optional HTTP header value using a given ``HttpHeader ⇒ Option<T>`` function
:ref:`-optionalHeaderValueByName-java-`          Extracts the value of the first optional HTTP request header with a given name
:ref:`-optionalHeaderValueByType-java-`          Extracts the first optional HTTP request header of the given type
:ref:`-optionalHeaderValuePF-java-`              Extracts an optional HTTP header value using a given ``PartialFunction<HttpHeader, T>``
:ref:`-options-java-`                            Rejects all non-OPTIONS requests
:ref:`-overrideMethodWithParameter-java-`        Changes the request method to the value of the specified query parameter
:ref:`-parameter-java-`                          Extracts a query parameter value from the request
:ref:`-parameterMap-java-`                       Extracts the request's query parameters as a ``Map<String, String>``
:ref:`-parameterMultiMap-java-`                  Extracts the request's query parameters as a ``Map<String, List<String>>``
:ref:`-parameterList-java-`                      Extracts the request's query parameters as a ``Seq<Pair<String, String>>``
:ref:`-pass-java-`                               Always simply passes the request on to its inner route, i.e. doesn't do anything, neither with the request nor the response
:ref:`-patch-java-`                              Rejects all non-PATCH requests
:ref:`-path-java-`                               Applies the given ``PathMatcher`` to the remaining unmatched path after consuming a leading slash
:ref:`-pathEnd-java-`                            Only passes on the request to its inner route if the request path has been matched completely
:ref:`-pathEndOrSingleSlash-java-`               Only passes on the request to its inner route if the request path has been matched completely or only consists of exactly one remaining slash
:ref:`-pathPrefix-java-`                         Applies the given ``PathMatcher`` to a prefix of the remaining unmatched path after consuming a leading slash
:ref:`-pathPrefixTest-java-`                     Checks whether the unmatchedPath has a prefix matched by the given ``PathMatcher`` after implicitly consuming a leading slash
:ref:`-pathSingleSlash-java-`                    Only passes on the request to its inner route if the request path consists of exactly one remaining slash
:ref:`-pathSuffix-java-`                         Applies the given ``PathMatcher`` to a suffix of the remaining unmatched path (Caution: check java!)
:ref:`-pathSuffixTest-java-`                     Checks whether the unmatched path has a suffix matched by the given ``PathMatcher`` (Caution: check java!)
:ref:`-post-java-`                               Rejects all non-POST requests
:ref:`-provide-java-`                            Injects a given value into a directive
:ref:`-put-java-`                                Rejects all non-PUT requests
:ref:`-rawPathPrefix-java-`                      Applies the given matcher directly to a prefix of the unmatched path of the ``RequestContext``, without implicitly consuming a leading slash
:ref:`-rawPathPrefixTest-java-`                  Checks whether the unmatchedPath has a prefix matched by the given ``PathMatcher``
:ref:`-recoverRejections-java-`                  Transforms rejections from the inner route with an ``List<Rejection] ⇒ RouteResult`` function
:ref:`-recoverRejectionsWith-java-`              Transforms rejections from the inner route with an ``List<Rejection] ⇒ CompletionStage<RouteResult>`` function
:ref:`-redirect-java-`                           Completes the request with redirection response of the given type to the given URI
:ref:`-redirectToNoTrailingSlashIfPresent-java-` If the request path ends with a slash, redirects to the same uri without trailing slash in the path
:ref:`-redirectToTrailingSlashIfMissing-java-`   If the request path doesn't end with a slash, redirects to the same uri with trailing slash in the path
:ref:`-reject-java-`                             Rejects the request with the given rejections
:ref:`-rejectEmptyResponse-java-`                Converts responses with an empty entity into (empty) rejections
:ref:`-requestEncodedWith-java-`                 Rejects the request with an ``UnsupportedRequestEncodingRejection`` if its encoding doesn't match the given one
:ref:`-requestEntityEmpty-java-`                 Rejects if the request entity is non-empty
:ref:`-requestEntityPresent-java-`               Rejects with a ``RequestEntityExpectedRejection`` if the request entity is empty
:ref:`-respondWithDefaultHeader-java-`           Adds a given response header if the response doesn't already contain a header with the same name
:ref:`-respondWithDefaultHeaders-java-`          Adds the subset of the given headers to the response which doesn't already have a header with the respective name present in the response
:ref:`-respondWithHeader-java-`                  Unconditionally adds a given header to the outgoing response
:ref:`-respondWithHeaders-java-`                 Unconditionally adds the given headers to the outgoing response
:ref:`-responseEncodingAccepted-java-`           Rejects the request with an ``UnacceptedResponseEncodingRejection`` if the given response encoding is not accepted by the client
:ref:`-scheme-java-`                             Rejects all requests whose URI scheme doesn't match the given one
:ref:`-selectPreferredLanguage-java-`            Inspects the request's ``Accept-Language`` header and determines, which of a given set of language alternatives is preferred by the client
:ref:`-setCookie-java-`                          Adds a ``Set-Cookie`` response header with the given cookies
:ref:`-uploadedFile-java-`                       Streams one uploaded file from a multipart request to a file on disk
:ref:`-validate-java-`                           Checks a given condition before running its inner route
:ref:`-withoutRequestTimeout-java-`              Disables :ref:`request timeouts <request-timeout-java>` for a given route.
:ref:`-withExecutionContext-java-`               Runs its inner route with the given alternative ``ExecutionContext``
:ref:`-withMaterializer-java-`                   Runs its inner route with the given alternative ``Materializer``
:ref:`-withLog-java-`                            Runs its inner route with the given alternative ``LoggingAdapter``
:ref:`-withRangeSupport-java-`                   Adds ``Accept-Ranges: bytes`` to responses to GET requests, produces partial responses if the initial request contained a valid ``Range`` header
:ref:`-withRequestTimeout-java-`                 Configures the :ref:`request timeouts <request-timeout-java>` for a given route.
:ref:`-withRequestTimeoutResponse-java-`         Prepares the ``HttpResponse`` that is emitted if a request timeout is triggered. ``RequestContext => RequestContext`` function
:ref:`-withSettings-java-`                       Runs its inner route with the given alternative ``RoutingSettings``
================================================ ============================================================================

