.. _Predefined Directives:

Predefined Directives (alphabetically)
======================================

=========================================== ============================================================================
Directive                                   Description
=========================================== ============================================================================
:ref:`-authenticateBasic-`                  Wraps the inner route with Http Basic authentication support using a given
                                            ``Authenticator[T]``
:ref:`-authenticateBasicAsync-`             Wraps the inner route with Http Basic authentication support using a given
                                            ``AsyncAuthenticator[T]``
:ref:`-authenticateBasicPF-`                Wraps the inner route with Http Basic authentication support using a given
                                            ``AuthenticatorPF[T]``
:ref:`-authenticateBasicPFAsync-`           Wraps the inner route with Http Basic authentication support using a given
                                            ``AsyncAuthenticatorPF[T]``
:ref:`-authenticateOAuth2-`                 Wraps the inner route with OAuth Bearer Token authentication support using
                                            a given ``AuthenticatorPF[T]``
:ref:`-authenticateOAuth2Async-`            Wraps the inner route with OAuth Bearer Token authentication support using
                                            a given ``AsyncAuthenticator[T]``
:ref:`-authenticateOAuth2PF-`               Wraps the inner route with OAuth Bearer Token authentication support using
                                            a given ``AuthenticatorPF[T]``
:ref:`-authenticateOAuth2PFAsync-`          Wraps the inner route with OAuth Bearer Token authentication support using
                                            a given ``AsyncAuthenticatorPF[T]``
:ref:`-authenticateOrRejectWithChallenge-`  Lifts an authenticator function into a directive
:ref:`-authorize-`                          Applies the given authorization check to the request
:ref:`-authorizeAsync-`                     Applies the given asynchronous authorization check to the request
:ref:`-cancelRejection-`                    Adds a ``TransformationRejection`` cancelling all rejections equal to the
                                            given one to the rejections potentially coming back from the inner route.
:ref:`-cancelRejections-`                   Adds a ``TransformationRejection`` cancelling all matching rejections
                                            to the rejections potentially coming back from the inner route
:ref:`-checkSameOrigin-`                    Checks that the request comes from the same origin
:ref:`-complete-`                           Completes the request using the given arguments
:ref:`-completeOrRecoverWith-`              "Unwraps" a ``Future[T]`` and runs the inner route when the future has
                                            failed with the error as an extraction of type ``Throwable``
:ref:`-completeWith-`                       Uses the marshaller for a given type to extract a completion function
:ref:`-conditional-`                        Wraps its inner route with support for conditional requests as defined
                                            by http://tools.ietf.org/html/rfc7232
:ref:`-cookie-`                             Extracts the ``HttpCookie`` with the given name
:ref:`-decodeRequest-`                      Decompresses the  request if it is ``gzip`` or ``deflate`` compressed
:ref:`-decodeRequestWith-`                  Decodes the incoming request using one of the given decoders
:ref:`-delete-`                             Rejects all non-DELETE requests
:ref:`-deleteCookie-`                       Adds a ``Set-Cookie`` response header expiring the given cookies
:ref:`-encodeResponse-`                     Encodes the response with the encoding that is requested by the client
                                            via the ``Accept-Encoding`` header (``NoCoding``, ``Gzip`` and ``Deflate``)
:ref:`-encodeResponseWith-`                 Encodes the response with the encoding that is requested by the client
                                            via the ``Accept-Encoding`` header (from a user-defined set)
:ref:`-entity-`                             Extracts the request entity unmarshalled to a given type
:ref:`-extract-`                            Extracts a single value using a ``RequestContext ⇒ T`` function
:ref:`-extractClientIP-`                    Extracts the client's IP from either the ``X-Forwarded-``,
                                            ``Remote-Address`` or ``X-Real-IP`` header
:ref:`-extractCredentials-`                 Extracts the potentially present ``HttpCredentials`` provided with the
                                            request's ``Authorization`` header
:ref:`-extractExecutionContext-`            Extracts the ``ExecutionContext`` from the ``RequestContext``
:ref:`-extractMaterializer-`                Extracts the ``Materializer`` from the ``RequestContext``
:ref:`-extractHost-`                        Extracts the hostname part of the Host request header value
:ref:`-extractLog-`                         Extracts the ``LoggingAdapter`` from the ``RequestContext``
:ref:`-extractMethod-`                      Extracts the request method
:ref:`-extractRequest-`                     Extracts the current ``HttpRequest`` instance
:ref:`-extractRequestContext-`              Extracts the ``RequestContext`` itself
:ref:`-extractScheme-`                      Extracts the URI scheme from the request
:ref:`-extractSettings-`                    Extracts the ``RoutingSettings`` from the ``RequestContext``
:ref:`-extractUnmatchedPath-`               Extracts the yet unmatched path from the ``RequestContext``
:ref:`-extractUri-`                         Extracts the complete request URI
:ref:`-failWith-`                           Bubbles the given error up the response chain where it is dealt with by the
                                            closest :ref:`-handleExceptions-` directive and its ``ExceptionHandler``
:ref:`-fileUpload-`                         Provides a stream of an uploaded file from a multipart request
:ref:`-formField-scala-`                    Extracts an HTTP form field from the request
:ref:`-formFieldMap-`                       Extracts a number of HTTP form field from the request as
                                            a ``Map[String, String]``
:ref:`-formFieldMultiMap-`                  Extracts a number of HTTP form field from the request as
                                            a ``Map[String, List[String]``
:ref:`-formFields-`                         Extracts a number of HTTP form field from the request
:ref:`-formFieldSeq-`                       Extracts a number of HTTP form field from the request as
                                            a ``Seq[(String, String)]``
:ref:`-get-`                                Rejects all non-GET requests
:ref:`-getFromBrowseableDirectories-`       Serves the content of the given directories as a file-system browser, i.e.
                                            files are sent and directories served as browseable listings
:ref:`-getFromBrowseableDirectory-`         Serves the content of the given directory as a file-system browser, i.e.
                                            files are sent and directories served as browseable listings
:ref:`-getFromDirectory-`                   Completes GET requests with the content of a file underneath a given
                                            file-system directory
:ref:`-getFromFile-`                        Completes GET requests with the content of a given file
:ref:`-getFromResource-`                    Completes GET requests with the content of a given class-path resource
:ref:`-getFromResourceDirectory-`           Completes GET requests with the content of a file underneath a given
                                            "class-path resource directory"
:ref:`-handleExceptions-`                   Transforms exceptions thrown during evaluation of the inner route using the
                                            given ``ExceptionHandler``
:ref:`-handleRejections-`                   Transforms rejections produced by the inner route using the given
                                            ``RejectionHandler``
:ref:`-handleWebSocketMessages-`            Handles websocket requests with the given handler and rejects other requests
                                            with an ``ExpectedWebSocketRequestRejection``
:ref:`-handleWebSocketMessagesForProtocol-` Handles websocket requests with the given handler if the subprotocol matches
                                            and rejects other requests with an ``ExpectedWebSocketRequestRejection`` or
                                            an ``UnsupportedWebSocketSubprotocolRejection``.
:ref:`-handleWith-`                         Completes the request using a given function
:ref:`-head-`                               Rejects all non-HEAD requests
:ref:`-headerValue-`                        Extracts an HTTP header value using a given ``HttpHeader ⇒ Option[T]``
                                            function
:ref:`-headerValueByName-`                  Extracts the value of the first HTTP request header with a given name
:ref:`-headerValueByType-`                  Extracts the first HTTP request header of the given type
:ref:`-headerValuePF-`                      Extracts an HTTP header value using a given
                                            ``PartialFunction[HttpHeader, T]``
:ref:`-host-`                               Rejects all requests with a non-matching host name
:ref:`-listDirectoryContents-`              Completes GET requests with a unified listing of the contents of all given
                                            file-system directories
:ref:`-logRequest-`                         Produces a log entry for every incoming request
:ref:`-logRequestResult-`                   Produces a log entry for every incoming request and ``RouteResult``
:ref:`-logResult-`                          Produces a log entry for every ``RouteResult``
:ref:`-mapInnerRoute-`                      Transforms its inner ``Route`` with a ``Route => Route`` function
:ref:`-mapRejections-`                      Transforms rejections from a previous route with an
                                            ``immutable.Seq[Rejection] ⇒ immutable.Seq[Rejection]`` function
:ref:`-mapRequest-`                         Transforms the request with an ``HttpRequest => HttpRequest`` function
:ref:`-mapRequestContext-`                  Transforms the ``RequestContext`` with a
                                            ``RequestContext => RequestContext`` function
:ref:`-mapResponse-`                        Transforms the response with an ``HttpResponse => HttpResponse`` function
:ref:`-mapResponseEntity-`                  Transforms the response entity with an ``ResponseEntity ⇒ ResponseEntity``
                                            function
:ref:`-mapResponseHeaders-`                 Transforms the response headers with an
                                            ``immutable.Seq[HttpHeader] ⇒ immutable.Seq[HttpHeader]`` function
:ref:`-mapRouteResult-`                     Transforms the ``RouteResult`` with a ``RouteResult ⇒ RouteResult``
                                            function
:ref:`-mapRouteResultFuture-`               Transforms the ``RouteResult`` future with a
                                            ``Future[RouteResult] ⇒ Future[RouteResult]`` function
:ref:`-mapRouteResultPF-`                   Transforms the ``RouteResult`` with a
                                            ``PartialFunction[RouteResult, RouteResult]``
:ref:`-mapRouteResultWith-`                 Transforms the ``RouteResult`` with a
                                            ``RouteResult ⇒ Future[RouteResult]`` function
:ref:`-mapRouteResultWithPF-`               Transforms the ``RouteResult`` with a
                                            ``PartialFunction[RouteResult, Future[RouteResult]]``
:ref:`-mapSettings-`                        Transforms the ``RoutingSettings`` with a
                                            ``RoutingSettings ⇒ RoutingSettings`` function
:ref:`-mapUnmatchedPath-`                   Transforms the ``unmatchedPath`` of the ``RequestContext`` using a
                                            ``Uri.Path ⇒ Uri.Path`` function
:ref:`-method-`                             Rejects all requests whose HTTP method does not match the given one
:ref:`-onComplete-`                         "Unwraps" a ``Future[T]`` and runs the inner route after future completion
                                            with the future's value as an extraction of type ``Try[T]``
:ref:`-onCompleteWithBreaker-`              "Unwraps" a ``Future[T]`` inside a ``CircuitBreaker`` and runs the inner
                                            route after future completion with the future's value as an extraction of
                                            type ``Try[T]``
:ref:`-onSuccess-`                          "Unwraps" a ``Future[T]`` and runs the inner route after future completion
                                            with the future's value as an extraction of type ``T``
:ref:`-optionalCookie-`                     Extracts the ``HttpCookiePair`` with the given name as an
                                            ``Option[HttpCookiePair]``
:ref:`-optionalHeaderValue-`                Extracts an optional HTTP header value using a given
                                            ``HttpHeader ⇒ Option[T]`` function
:ref:`-optionalHeaderValueByName-`          Extracts the value of the first optional HTTP request header with a given
                                            name
:ref:`-optionalHeaderValueByType-`          Extracts the first optional HTTP request header of the given type
:ref:`-optionalHeaderValuePF-`              Extracts an optional HTTP header value using a given
                                            ``PartialFunction[HttpHeader, T]``
:ref:`-options-`                            Rejects all non-OPTIONS requests
:ref:`-overrideMethodWithParameter-`        Changes the request method to the value of the specified query parameter
:ref:`-parameter-`                          Extracts a query parameter value from the request
:ref:`-parameterMap-`                       Extracts the request's query parameters as a ``Map[String, String]``
:ref:`-parameterMultiMap-`                  Extracts the request's query parameters as a ``Map[String, List[String]]``
:ref:`-parameters-scala-`                   Extracts a number of query parameter values from the request
:ref:`-parameterSeq-`                       Extracts the request's query parameters as a ``Seq[(String, String)]``
:ref:`-pass-`                               Always simply passes the request on to its inner route, i.e. doesn't do
                                            anything, neither with the request nor the response
:ref:`-patch-`                              Rejects all non-PATCH requests
:ref:`-path-`                               Applies the given ``PathMatcher`` to the remaining unmatched path after
                                            consuming a leading slash
:ref:`-pathEnd-`                            Only passes on the request to its inner route if the request path has been
                                            matched completely
:ref:`-pathEndOrSingleSlash-`               Only passes on the request to its inner route if the request path has been
                                            matched completely or only consists of exactly one remaining slash
:ref:`-pathPrefix-`                         Applies the given ``PathMatcher`` to a prefix of the remaining unmatched
                                            path after consuming a leading slash
:ref:`-pathPrefixTest-`                     Checks whether the unmatchedPath has a prefix matched by the given
                                            ``PathMatcher`` after implicitly consuming a leading slash
:ref:`-pathSingleSlash-`                    Only passes on the request to its inner route if the request path
                                            consists of exactly one remaining slash
:ref:`-pathSuffix-`                         Applies the given ``PathMatcher`` to a suffix of the remaining unmatched
                                            path (Caution: check scaladoc!)
:ref:`-pathSuffixTest-`                     Checks whether the unmatched path has a suffix matched by the given
                                            ``PathMatcher`` (Caution: check scaladoc!)
:ref:`-post-`                               Rejects all non-POST requests
:ref:`-provide-`                            Injects a given value into a directive
:ref:`-put-`                                Rejects all non-PUT requests
:ref:`-rawPathPrefix-`                      Applies the given matcher directly to a prefix of the unmatched path of the
                                            ``RequestContext``, without implicitly consuming a leading slash
:ref:`-rawPathPrefixTest-`                  Checks whether the unmatchedPath has a prefix matched by the given
                                            ``PathMatcher``
:ref:`-recoverRejections-`                  Transforms rejections from the inner route with an
                                            ``immutable.Seq[Rejection] ⇒ RouteResult`` function
:ref:`-recoverRejectionsWith-`              Transforms rejections from the inner route with an
                                            ``immutable.Seq[Rejection] ⇒ Future[RouteResult]`` function
:ref:`-redirect-`                           Completes the request with redirection response of the given type to the
                                            given URI
:ref:`-redirectToNoTrailingSlashIfPresent-` If the request path ends with a slash, redirects to the same uri without
                                            trailing slash in the path
:ref:`-redirectToTrailingSlashIfMissing-`   If the request path doesn't end with a slash, redirects to the same uri with
                                            trailing slash in the path
:ref:`-reject-`                             Rejects the request with the given rejections
:ref:`-rejectEmptyResponse-`                Converts responses with an empty entity into (empty) rejections
:ref:`-requestEncodedWith-`                 Rejects the request with an ``UnsupportedRequestEncodingRejection`` if its
                                            encoding doesn't match the given one
:ref:`-requestEntityEmpty-`                 Rejects if the request entity is non-empty
:ref:`-requestEntityPresent-`               Rejects with a ``RequestEntityExpectedRejection`` if the request entity is
                                            empty
:ref:`-respondWithDefaultHeader-`           Adds a given response header if the response doesn't already contain a
                                            header with the same name
:ref:`-respondWithDefaultHeaders-`          Adds the subset of the given headers to the response which doesn't already
                                            have a header with the respective name present in the response
:ref:`-respondWithHeader-`                  Unconditionally adds a given header to the outgoing response
:ref:`-respondWithHeaders-`                 Unconditionally adds the given headers to the outgoing response
:ref:`-responseEncodingAccepted-`           Rejects the request with an ``UnacceptedResponseEncodingRejection`` if the
                                            given response encoding is not accepted by the client
:ref:`-scheme-`                             Rejects all requests whose URI scheme doesn't match the given one
:ref:`-selectPreferredLanguage-`            Inspects the request's ``Accept-Language`` header and determines, which of
                                            a given set of language alternatives is preferred by the client
:ref:`-setCookie-`                          Adds a ``Set-Cookie`` response header with the given cookies
:ref:`-textract-`                           Extracts a number of values using a ``RequestContext ⇒ Tuple`` function
:ref:`-tprovide-`                           Injects a given tuple of values into a directive
:ref:`-uploadedFile-`                       Streams one uploaded file from a multipart request to a file on disk
:ref:`-validate-`                           Checks a given condition before running its inner route
:ref:`-withoutRequestTimeout-`              Disables :ref:`request timeouts <request-timeout-scala>` for a given route.
:ref:`-withExecutionContext-`               Runs its inner route with the given alternative ``ExecutionContext``
:ref:`-withMaterializer-`                   Runs its inner route with the given alternative ``Materializer``
:ref:`-withLog-`                            Runs its inner route with the given alternative ``LoggingAdapter``
:ref:`-withRangeSupport-`                   Adds ``Accept-Ranges: bytes`` to responses to GET requests, produces partial
                                            responses if the initial request contained a valid ``Range`` header
:ref:`-withRequestTimeout-`                 Configures the :ref:`request timeouts <request-timeout-scala>` for a given route.
:ref:`-withRequestTimeoutResponse-`         Prepares the ``HttpResponse`` that is emitted if a request timeout is triggered.
                                            ``RequestContext => RequestContext`` function
:ref:`-withSettings-`                       Runs its inner route with the given alternative ``RoutingSettings``
=========================================== ============================================================================
