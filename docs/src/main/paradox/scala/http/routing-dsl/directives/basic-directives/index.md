# BasicDirectives

Basic directives are building blocks for building @ref[Custom Directives](../custom-directives.md). As such they
usually aren't used in a route directly but rather in the definition of new directives.

<a id="providedirectives"></a>
## Providing Values to Inner Routes

These directives provide values to the inner routes with extractions. They can be distinguished
on two axes: a) provide a constant value or extract a value from the @unidoc[RequestContext] b) provide
a single value or a tuple of values.

>
 * @ref[extract](extract.md)
 * @ref[extractActorSystem](extractActorSystem.md)
 * @ref[extractDataBytes](extractDataBytes.md)
 * @ref[extractExecutionContext](extractExecutionContext.md)
 * @ref[extractLog](extractLog.md)
 * @ref[extractMatchedPath](extractMatchedPath.md)
 * @ref[extractMaterializer](extractMaterializer.md)
 * @ref[extractParserSettings](extractParserSettings.md)
 * @ref[extractRequestContext](extractRequestContext.md)
 * @ref[extractRequestEntity](extractRequestEntity.md)
 * @ref[extractRequest](extractRequest.md)
 * @ref[extractSettings](extractSettings.md)
 * @ref[extractStrictEntity](extractStrictEntity.md)
 * @ref[extractUnmatchedPath](extractUnmatchedPath.md)
 * @ref[extractUri](extractUri.md)
 * @ref[textract](textract.md)
 * @ref[provide](provide.md)
 * @ref[tprovide](tprovide.md)

<a id="request-transforming-directives"></a>
## Transforming the Request(Context)

>
 * @ref[mapRequest](mapRequest.md)
 * @ref[mapRequestContext](mapRequestContext.md)
 * @ref[mapSettings](mapSettings.md)
 * @ref[mapUnmatchedPath](mapUnmatchedPath.md)
 * @ref[withExecutionContext](withExecutionContext.md)
 * @ref[withLog](withLog.md)
 * @ref[withMaterializer](withMaterializer.md)
 * @ref[withSettings](withSettings.md)
 * @ref[toStrictEntity](toStrictEntity.md)

<a id="response-transforming-directives"></a>
## Transforming the Response

These directives allow to hook into the response path and transform the complete response or
the parts of a response or the list of rejections:

>
 * @ref[mapResponse](mapResponse.md)
 * @ref[mapResponseEntity](mapResponseEntity.md)
 * @ref[mapResponseHeaders](mapResponseHeaders.md)

<a id="result-transformation-directives"></a>
## Transforming the RouteResult

These directives allow to transform the RouteResult of the inner route.

>
 * @ref[cancelRejection](cancelRejection.md)
 * @ref[cancelRejections](cancelRejections.md)
 * @ref[mapRejections](mapRejections.md)
 * @ref[mapRouteResult](mapRouteResult.md)
 * @ref[mapRouteResultFuture](mapRouteResultFuture.md)
 * @ref[mapRouteResultPF](mapRouteResultPF.md)
 * @ref[mapRouteResultWith](mapRouteResultWith.md)
 * @ref[mapRouteResultWithPF](mapRouteResultWithPF.md)
 * @ref[recoverRejections](recoverRejections.md)
 * @ref[recoverRejectionsWith](recoverRejectionsWith.md)

## Other

>
 * @ref[mapInnerRoute](mapInnerRoute.md)
 * @ref[pass](pass.md)

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
* [extractRequest](extractRequest.md)
* [extractRequestContext](extractRequestContext.md)
* [extractRequestEntity](extractRequestEntity.md)
* [extractSettings](extractSettings.md)
* [extractStrictEntity](extractStrictEntity.md)
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
* [mapRouteResultWithPF](mapRouteResultWithPF.md)
* [mapRouteResultWith](mapRouteResultWith.md)
* [mapSettings](mapSettings.md)
* [mapUnmatchedPath](mapUnmatchedPath.md)
* [pass](pass.md)
* [provide](provide.md)
* [recoverRejections](recoverRejections.md)
* [recoverRejectionsWith](recoverRejectionsWith.md)
* [textract](textract.md)
* [toStrictEntity](toStrictEntity.md)
* [tprovide](tprovide.md)
* [withExecutionContext](withExecutionContext.md)
* [withLog](withLog.md)
* [withMaterializer](withMaterializer.md)
* [withSettings](withSettings.md)

@@@
