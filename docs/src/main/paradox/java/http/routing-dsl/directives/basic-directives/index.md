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
 * @ref[extract-java](extract.md#extract-java)
 * @ref[extractActorSystem-java](extractActorSystem.md#extractactorsystem-java)
 * @ref[extractDataBytes-java](extractDataBytes.md#extractdatabytes-java)
 * @ref[extractExecutionContext-java](extractExecutionContext.md#extractexecutioncontext-java)
 * @ref[extractMaterializer-java](extractMaterializer.md#extractmaterializer-java)
 * @ref[extractStrictEntity-java](extractStrictEntity.md#extractstrictentity-java)
 * @ref[extractLog-java](extractLog.md#extractlog-java)
 * @ref[extractRequest-java](extractRequest.md#extractrequest-java)
 * @ref[extractRequestContext-java](extractRequestContext.md#extractrequestcontext-java)
 * @ref[extractRequestEntity-java](extractRequestEntity.md#extractrequestentity-java)
 * @ref[extractSettings-java](extractSettings.md#extractsettings-java)
 * @ref[extractUnmatchedPath-java](extractUnmatchedPath.md#extractunmatchedpath-java)
 * @ref[extractUri-java](extractUri.md#extracturi-java)
 * @ref[provide-java](provide.md#provide-java)

<a id="request-transforming-directives-java"></a>
## Transforming the Request(Context)

>
 * @ref[mapRequest-java](mapRequest.md#maprequest-java)
 * @ref[mapRequestContext-java](mapRequestContext.md#maprequestcontext-java)
 * @ref[mapSettings-java](mapSettings.md#mapsettings-java)
 * @ref[mapUnmatchedPath-java](mapUnmatchedPath.md#mapunmatchedpath-java)
 * @ref[withExecutionContext-java](withExecutionContext.md#withexecutioncontext-java)
 * @ref[withMaterializer-java](withMaterializer.md#withmaterializer-java)
 * @ref[withLog-java](withLog.md#withlog-java)
 * @ref[withSettings-java](withSettings.md#withsettings-java)
 * @ref[toStrictEntity-java](toStrictEntity.md#tostrictentity-java)

<a id="response-transforming-directives-java"></a>
## Transforming the Response

These directives allow to hook into the response path and transform the complete response or
the parts of a response or the list of rejections:

>
 * @ref[mapResponse-java](mapResponse.md#mapresponse-java)
 * @ref[mapResponseEntity-java](mapResponseEntity.md#mapresponseentity-java)
 * @ref[mapResponseHeaders-java](mapResponseHeaders.md#mapresponseheaders-java)

<a id="result-transformation-directives-java"></a>
## Transforming the RouteResult

These directives allow to transform the RouteResult of the inner route.

>
 * @ref[cancelRejection-java](cancelRejection.md#cancelrejection-java)
 * @ref[cancelRejections-java](cancelRejections.md#cancelrejections-java)
 * @ref[mapRejections-java](mapRejections.md#maprejections-java)
 * @ref[mapRouteResult-java](mapRouteResult.md#maprouteresult-java)
 * @ref[mapRouteResultFuture-java](mapRouteResultFuture.md#maprouteresultfuture-java)
 * @ref[mapRouteResultPF-java](mapRouteResultPF.md#maprouteresultpf-java)
 * @ref[mapRouteResultWith-java](mapRouteResultWith.md#maprouteresultwith-java)
 * @ref[mapRouteResultWithPF-java](mapRouteResultWithPF.md#maprouteresultwithpf-java)
 * @ref[recoverRejections-java](recoverRejections.md#recoverrejections-java)
 * @ref[recoverRejectionsWith-java](recoverRejectionsWith.md#recoverrejectionswith-java)

## Other

>
 * @ref[mapInnerRoute-java](mapInnerRoute.md#mapinnerroute-java)
 * @ref[pass-java](pass.md#pass-java)

## Alphabetically

@@toc { depth=1 }

@@@ index

* [cancelRejection](cancelRejection.md)
* [cancelRejections](cancelRejections.md)
* [extract](extract.md)
* [extractActorSystem](extractActorSystem.md)
* [extractDataBytes](extractDataBytes.md)
* [extractExecutionContext](extractExecutionContext.md)
* [extractMaterializer](extractMaterializer.md)
* [extractStrictEntity](extractStrictEntity.md)
* [extractLog](extractLog.md)
* [extractRequest](extractRequest.md)
* [extractRequestContext](extractRequestContext.md)
* [extractRequestEntity](extractRequestEntity.md)
* [extractSettings](extractSettings.md)
* [extractUnmatchedPath](extractUnmatchedPath.md)
* [extractUri](extractUri.md)
* [mapInnerRoute](mapInnerRoute.md)
* [mapRejections](mapRejections.md)
* [mapRequest](mapRequest.md)
* [mapRequestContext](mapRequestContext.md)
* [mapResponse](mapResponse.md)
* [mapResponseEntity](mapResponseEntity.md)
* [mapResponseHeaders](mapResponseHeaders.md)
* [mapRouteResult](mapRouteResult.md)
* [mapRouteResultFuture](mapRouteResultFuture.md)
* [mapRouteResultPF](mapRouteResultPF.md)
* [mapRouteResultWith](mapRouteResultWith.md)
* [mapRouteResultWithPF](mapRouteResultWithPF.md)
* [mapSettings](mapSettings.md)
* [mapUnmatchedPath](mapUnmatchedPath.md)
* [pass](pass.md)
* [provide](provide.md)
* [recoverRejections](recoverRejections.md)
* [recoverRejectionsWith](recoverRejectionsWith.md)
* [toStrictEntity](toStrictEntity.md)
* [withExecutionContext](withExecutionContext.md)
* [withMaterializer](withMaterializer.md)
* [withLog](withLog.md)
* [withSettings](withSettings.md)

@@@