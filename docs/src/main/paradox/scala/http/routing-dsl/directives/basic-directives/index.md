<a id="basicdirectives"></a>
# BasicDirectives

Basic directives are building blocks for building @ref[Custom Directives](../custom-directives.md#custom-directives). As such they
usually aren't used in a route directly but rather in the definition of new directives.

<a id="providedirectives"></a>
## Providing Values to Inner Routes

These directives provide values to the inner routes with extractions. They can be distinguished
on two axes: a) provide a constant value or extract a value from the `RequestContext` b) provide
a single value or a tuple of values.

>
 * @ref[extract](extract.md#extract)
 * @ref[extractActorSystem](extractActorSystem.md#extractactorsystem)
 * @ref[extractDataBytes](extractDataBytes.md#extractdatabytes)
 * @ref[extractExecutionContext](extractExecutionContext.md#extractexecutioncontext)
 * @ref[extractLog](extractLog.md#extractlog)
 * @ref[extractMatchedPath](extractMatchedPath.md#extractmatchedpath-java)
 * @ref[extractMaterializer](extractMaterializer.md#extractmaterializer)
 * @ref[extractParserSettings](extractParserSettings.md)
 * @ref[extractRequestContext](extractRequestContext.md#extractrequestcontext)
 * @ref[extractRequestEntity](extractRequestEntity.md#extractrequestentity)
 * @ref[extractRequest](extractRequest.md#extractrequest)
 * @ref[extractSettings](extractSettings.md#extractsettings)
 * @ref[extractStrictEntity](extractStrictEntity.md#extractstrictentity)
 * @ref[extractUnmatchedPath](extractUnmatchedPath.md#extractunmatchedpath)
 * @ref[extractUri](extractUri.md#extracturi)
 * @ref[textract](textract.md#textract)
 * @ref[provide](provide.md#provide)
 * @ref[tprovide](tprovide.md#tprovide)

<a id="request-transforming-directives"></a>
## Transforming the Request(Context)

>
 * @ref[mapRequest](mapRequest.md#maprequest)
 * @ref[mapRequestContext](mapRequestContext.md#maprequestcontext)
 * @ref[mapSettings](mapSettings.md#mapsettings)
 * @ref[mapUnmatchedPath](mapUnmatchedPath.md#mapunmatchedpath)
 * @ref[withExecutionContext](withExecutionContext.md#withexecutioncontext)
 * @ref[withMaterializer](withMaterializer.md#withmaterializer)
 * @ref[withLog](withLog.md#withlog)
 * @ref[withSettings](withSettings.md#withsettings)
 * @ref[toStrictEntity](toStrictEntity.md#tostrictentity)

<a id="response-transforming-directives"></a>
## Transforming the Response

These directives allow to hook into the response path and transform the complete response or
the parts of a response or the list of rejections:

>
 * @ref[mapResponse](mapResponse.md#mapresponse)
 * @ref[mapResponseEntity](mapResponseEntity.md#mapresponseentity)
 * @ref[mapResponseHeaders](mapResponseHeaders.md#mapresponseheaders)

<a id="result-transformation-directives"></a>
## Transforming the RouteResult

These directives allow to transform the RouteResult of the inner route.

>
 * @ref[cancelRejection](cancelRejection.md#cancelrejection)
 * @ref[cancelRejections](cancelRejections.md#cancelrejections)
 * @ref[mapRejections](mapRejections.md#maprejections)
 * @ref[mapRouteResult](mapRouteResult.md#maprouteresult)
 * @ref[mapRouteResultFuture](mapRouteResultFuture.md#maprouteresultfuture)
 * @ref[mapRouteResultPF](mapRouteResultPF.md#maprouteresultpf)
 * @ref[mapRouteResultWith](mapRouteResultWith.md#maprouteresultwith)
 * @ref[mapRouteResultWithPF](mapRouteResultWithPF.md#maprouteresultwithpf)
 * @ref[recoverRejections](recoverRejections.md#recoverrejections)
 * @ref[recoverRejectionsWith](recoverRejectionsWith.md#recoverrejectionswith)

## Other

>
 * @ref[mapInnerRoute](mapInnerRoute.md#mapinnerroute)
 * @ref[pass](pass.md#pass)

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
* [extractParserSettings](extractParserSettings.md)
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
* [textract](textract.md)
* [toStrictEntity](toStrictEntity.md)
* [tprovide](tprovide.md)
* [withExecutionContext](withExecutionContext.md)
* [withLog](withLog.md)
* [withMaterializer](withMaterializer.md)
* [withSettings](withSettings.md)

@@@
