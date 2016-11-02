# Migration Guide from Spray

## General notes

Features which are not ported to the akka-http:

 * `respondWithStatus` also known as `overrideStatusCode` has not been forward ported to Akka HTTP,
as it has been seen mostly as an anti-pattern. More information here: <https://github.com/akka/akka/issues/18626>
 * `respondWithMediaType` was considered an anti-pattern in spray and is not ported to Akka HTTP.
Instead users should rely on content type negotiation as Akka HTTP implements it.
More information here: [#190](https://github.com/akka/akka-http/issues/190)
 * @ref[Registering Custom Media Types](../common/http-model.md#registeringcustommediatypes) changed from Spray in order not to rely on global state.

## Removed HttpService

Spray’s `HttpService` was removed. This means that scala code like this:

```scala
val service = system.actorOf(Props(new HttpServiceActor(routes)))
IO(Http)(system) ! Http.Bind(service, "0.0.0.0", port = 8080)
```

needs to be changed into:

```scala
Http().bindAndHandle(routes, "0.0.0.0", port = 8080)
```

## Changes in Marshalling

Marshaller.of can be replaced with `Marshaller.withFixedContentType`.

Was:

```scala
Marshaller.of[JsonApiObject](`application/json`) { (value, contentType, ctx) =>
  ctx.marshalTo(HttpEntity(contentType, value.toJson.toString))
}
```

Replace with:

```scala
Marshaller.withFixedContentType(`application/json`) { obj =>
  HttpEntity(`application/json`, obj.toJson.compactPrint)
}
```

Akka HTTP marshallers support content negotiation, now it's not necessary to specify content type
when creating one “super” marshaller from other marshallers:

Before:

```scala
ToResponseMarshaller.oneOf(
  `application/vnd.api+json`,
  `application/json`
)(
  jsonApiMarshaller,
  jsonMarshaller
}
```

After:

```scala
Marshaller.oneOf(
  jsonApiMarshaller,
  jsonMarshaller
)
```

## Changes in Unmarshalling

Akka Http contains a set of predefined unmarshallers. This means that scala code like this:

```scala
Unmarshaller[Entity](`application/json`) {
  case HttpEntity.NonEmpty(contentType, data) =>
    data.asString.parseJson.convertTo[Entity]
}
```

needs to be changed into:

```scala
Unmarshaller
  .stringUnmarshaller
  .forContentTypes(`application/json`)
  .map(_.parseJson.convertTo[Entity])
```

## Changes in MediaTypes

`MediaType.custom` can be replaced with specific methods in `MediaType` object.

Was:

```scala
MediaType.custom("application/vnd.acme+json")
```

Replace with:

```scala
MediaType.applicationWithFixedCharset("vnd.acme+json", HttpCharsets.`UTF-8`)
```

## Changes in Rejection Handling

`RejectionHandler` now uses a builder pattern – see the example:

Before:

```scala
def rootRejectionHandler = RejectionHandler {
  case Nil =>
    requestUri { uri =>
      logger.error("Route: {} does not exist.", uri)
      complete((NotFound, mapErrorToRootObject(notFoundError)))
    }
  case AuthenticationFailedRejection(cause, challengeHeaders) :: _ => {
    logger.error(s"Request is rejected with cause: $cause")
    complete((Unauthorized, mapErrorToRootObject(unauthenticatedError)))
  }
}
```

After:

```scala
RejectionHandler
.newBuilder()
.handle {
  case AuthenticationFailedRejection(cause, challengeHeaders) =>
    logger.error(s"Request is rejected with cause: $cause")
    complete((Unauthorized, mapErrorToRootObject(unauthenticatedError)))
.handleNotFound { ctx =>
  logger.error("Route: {} does not exist.", ctx.request.uri.toString())
  ctx.complete((NotFound, mapErrorToRootObject(notFoundError)))
}
.result()
.withFallback(RejectionHandler.default)
```

## Changes in HTTP Client

The Spray-client pipeline was removed. Http’s `singleRequest` should be used instead of `sendReceive`:

```scala
//this will not longer work
val token = Authorization(OAuth2BearerToken(accessToken))
val pipeline: HttpRequest => Future[HttpResponse] = (addHeader(token) ~> sendReceive)
val patch: HttpRequest = Patch(uri, object))

pipeline(patch).map { response ⇒
    …
}
```

needs to be changed into:

```scala
val request = HttpRequest(
  method = PATCH,
  uri = Uri(uri),
  headers = List(Authorization(OAuth2BearerToken(accessToken))),
  entity = HttpEntity(MediaTypes.`application/json`, object)
)

http.singleRequest(request).map {
  case … => …
}
```

## Changes in Headers

All HTTP headers have been moved to the `akka.http.scaladsl.model.headers._` package.

## Changes in form fields and file upload directives

With the streaming nature of http entity, it’s important to have a strict http entity before accessing
multiple form fields or use file upload directives.
One solution might be using next directive before working with form fields:

```scala
val toStrict: Directive0 = extractRequest flatMap { request =>
  onComplete(request.entity.toStrict(5.seconds)) flatMap {
    case Success(strict) =>
      mapRequest( req => req.copy(entity = strict))
    case _ => reject
  }
}
```

And one can use it like this:

```scala
toStrict {
  formFields("name".as[String]) { name =>
  ...
  }
}
```
