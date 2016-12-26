<a id="basicdirectives-java"></a>
# BasicDirectives

Basic directives are building blocks for building @ref[Custom Directives](../custom-directives.md#custom-directives). As such they
usually aren't used in a route directly but rather in the definition of new directives.

<a id="providedirectives-java"></a>
## Providing Values to Inner Routes

These directives provide values to the inner routes with extractions. They can be distinguished
on two axes: a) provide a constant value or extract a value from the `RequestContext` b) provide
a single value or a tuple of values.

>
 * @ref[extract](extract.md#extract-java)
 * @ref[extractActorSystem](extractActorSystem.md#extractactorsystem-java)
 * @ref[extractDataBytes](extractDataBytes.md#extractdatabytes-java)
 * @ref[extractExecutionContext](extractExecutionContext.md#extractexecutioncontext-java)
 * @ref[extractLog](extractLog.md#extractlog-java)
 * @ref[extractMatchedPath](extractMatchedPath.md#extractmatchedpath-java)
 * @ref[extractMaterializer](extractMaterializer.md#extractmaterializer-java)
 * @ref[extractRequestContext](extractRequestContext.md#extractrequestcontext-java)
 * @ref[extractRequestEntity](extractRequestEntity.md#extractrequestentity-java)
 * @ref[extractRequest](extractRequest.md#extractrequest-java)
 * @ref[extractSettings](extractSettings.md#extractsettings-java)
 * @ref[extractStrictEntity](extractStrictEntity.md#extractstrictentity-java)
 * @ref[extractUnmatchedPath](extractUnmatchedPath.md#extractunmatchedpath-java)
 * @ref[extractUri](extractUri.md#extracturi-java)
 * @ref[provide](provide.md#provide-java)

<a id="request-transforming-directives-java"></a>
## Transforming the Request(Context)

>
 * @ref[mapRequest](mapRequest.md#maprequest-java)
 * @ref[mapRequestContext](mapRequestContext.md#maprequestcontext-java)
 * @ref[mapSettings](mapSettings.md#mapsettings-java)
 * @ref[mapUnmatchedPath](mapUnmatchedPath.md#mapunmatchedpath-java)
 * @ref[withExecutionContext](withExecutionContext.md#withexecutioncontext-java)
 * @ref[withMaterializer](withMaterializer.md#withmaterializer-java)
 * @ref[withLog](withLog.md#withlog-java)
 * @ref[withSettings](withSettings.md#withsettings-java)
 * @ref[toStrictEntity](toStrictEntity.md#tostrictentity-java)

<a id="response-transforming-directives-java"></a>
## Transforming the Response

These directives allow to hook into the response path and transform the complete response or
the parts of a response or the list of rejections:

>
 * @ref[mapResponse](mapResponse.md#mapresponse-java)
 * @ref[mapResponseEntity](mapResponseEntity.md#mapresponseentity-java)
 * @ref[mapResponseHeaders](mapResponseHeaders.md#mapresponseheaders-java)

<a id="result-transformation-directives-java"></a>
## Transforming the RouteResult

These directives allow to transform the RouteResult of the inner route.

>
 * @ref[cancelRejection](cancelRejection.md#cancelrejection-java)
 * @ref[cancelRejections](cancelRejections.md#cancelrejections-java)
 * @ref[mapRejections](mapRejections.md#maprejections-java)
 * @ref[mapRouteResult](mapRouteResult.md#maprouteresult-java)
 * @ref[mapRouteResultFuture](mapRouteResultFuture.md#maprouteresultfuture-java)
 * @ref[mapRouteResultPF](mapRouteResultPF.md#maprouteresultpf-java)
 * @ref[mapRouteResultWith](mapRouteResultWith.md#maprouteresultwith-java)
 * @ref[mapRouteResultWithPF](mapRouteResultWithPF.md#maprouteresultwithpf-java)
 * @ref[recoverRejections](recoverRejections.md#recoverrejections-java)
 * @ref[recoverRejectionsWith](recoverRejectionsWith.md#recoverrejectionswith-java)

## Other

>
 * @ref[mapInnerRoute](mapInnerRoute.md#mapinnerroute-java)
 * @ref[pass](pass.md#pass-java)

## Alphabetically

@@toc { depth=1 }

@@@ index

* [cancelRejection](cancelRejection.md)
* [cancelRejections](cancelRejections.md)
* [extract](extract.md)
* [extractActorSystem](extractActorSystem.md)
* [extractDataBytes](extractDataBytes.md)
* [extractExecutionContext](extractExecutionContext.md)
* [extractLog](extractLog.md)
* [extractMatchedPath](extractMatchedPath.md)
* [extractMaterializer](extractMaterializer.md)
* [extractRequestContext](extractRequestContext.md)
* [extractRequestEntity](extractRequestEntity.md)
* [extractRequest](extractRequest.md)
* [extractSettings](extractSettings.md)
* [extractStrictEntity](extractStrictEntity.md)
* [extractUnmatchedPath](extractUnmatchedPath.md)
* [extractUri](extractUri.md)
* [mapInnerRoute](mapInnerRoute.md)
* [mapRejections](mapRejections.md)
* [mapRequestContext](mapRequestContext.md)
* [mapRequest](mapRequest.md)
* [mapResponseEntity](mapResponseEntity.md)
* [mapResponseHeaders](mapResponseHeaders.md)
* [mapResponse](mapResponse.md)
* [mapRouteResultFuture](mapRouteResultFuture.md)
* [mapRouteResultPF](mapRouteResultPF.md)
* [mapRouteResultWithPF](mapRouteResultWithPF.md)
* [mapRouteResultWith](mapRouteResultWith.md)
* [mapRouteResult](mapRouteResult.md)
* [mapSettings](mapSettings.md)
* [mapUnmatchedPath](mapUnmatchedPath.md)
* [pass](pass.md)
* [provide](provide.md)
* [recoverRejectionsWith](recoverRejectionsWith.md)
* [recoverRejections](recoverRejections.md)
* [toStrictEntity](toStrictEntity.md)
* [withExecutionContext](withExecutionContext.md)
* [withLog](withLog.md)
* [withMaterializer](withMaterializer.md)
* [withSettings](withSettings.md)

@@@
