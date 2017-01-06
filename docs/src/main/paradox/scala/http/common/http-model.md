<a id="http-model-scala"></a>
# HTTP Model

Akka HTTP model contains a deeply structured, fully immutable, case-class based model of all the major HTTP data
structures, like HTTP requests, responses and common headers.
It lives in the *akka-http-core* module and forms the basis for most of Akka HTTP's APIs.

## Overview

Since akka-http-core provides the central HTTP data structures you will find the following import in quite a
few places around the code base (and probably your own code as well):

@@snip [ModelSpec.scala](../../../../../test/scala/docs/http/scaladsl/ModelSpec.scala) { #import-model }

This brings all of the most relevant types in scope, mainly:

 * `HttpRequest` and `HttpResponse`, the central message model
 * `headers`, the package containing all the predefined HTTP header models and supporting types
 * Supporting types like `Uri`, `HttpMethods`, `MediaTypes`, `StatusCodes`, etc.

A common pattern is that the model of a certain entity is represented by an immutable type (class or trait),
while the actual instances of the entity defined by the HTTP spec live in an accompanying object carrying the name of
the type plus a trailing plural 's'.

For example:

 * Defined `HttpMethod` instances live in the `HttpMethods` object.
 * Defined `HttpCharset` instances live in the `HttpCharsets` object.
 * Defined `HttpEncoding` instances live in the `HttpEncodings` object.
 * Defined `HttpProtocol` instances live in the `HttpProtocols` object.
 * Defined `MediaType` instances live in the `MediaTypes` object.
 * Defined `StatusCode` instances live in the `StatusCodes` object.

## HttpRequest

`HttpRequest` and `HttpResponse` are the basic case classes representing HTTP messages.

An `HttpRequest` consists of

 * a method (GET, POST, etc.)
 * a URI (see @ref[URI model](uri-model.md) for more information)
 * a seq of headers
 * an entity (body data)
 * a protocol

Here are some examples how to construct an `HttpRequest`:

@@snip [ModelSpec.scala](../../../../../test/scala/docs/http/scaladsl/ModelSpec.scala) { #construct-request }

All parameters of `HttpRequest.apply` have default values set, so `headers` for example don't need to be specified
if there are none. Many of the parameters types (like `HttpEntity` and `Uri`) define implicit conversions
for common use cases to simplify the creation of request and response instances.

<a id="synthetic-headers-scala"></a>
### Synthetic Headers

In some cases it may be necessary to deviate from fully RFC-Compliant behavior. For instance, Amazon S3 treats 
the + character in the path part of the URL as a string, even though the RFC specifies that this behavior should
be limited exclusively to the query portion of the URI.

In order to work around these types of edge cases, Akka HTTP provides for the ability to provide extra, 
non-standard information to the request via synthetic headers. These headers are not passed to the client
but are instead consumed by the request engine and used to override default behavior.

For instance, in order to provide a raw request uri, bypassing the default url normalization, you could do the
following:

    import akka.http.scaladsl.model.headers.`Raw-Request-URI`
    val req = HttpRequest(uri = "/ignored", headers=List(`Raw-Request-URI`("/a/b%2Bc")))

## HttpResponse

An `HttpResponse` consists of

>
 * a status code
 * a seq of headers
 * an entity (body data)
 * a protocol

Here are some examples how to construct an `HttpResponse`:

@@snip [ModelSpec.scala](../../../../../test/scala/docs/http/scaladsl/ModelSpec.scala) { #construct-response }

In addition to the simple `HttpEntity` constructors which create an entity from a fixed `String` or `ByteString`
as shown here the Akka HTTP model defines a number of subclasses of `HttpEntity` which allow body data to be specified as a
stream of bytes.

<a id="httpentity-scala"></a>
## HttpEntity

An `HttpEntity` carries the data bytes of a message together with its Content-Type and, if known, its Content-Length.
In Akka HTTP there are five different kinds of entities which model the various ways that message content can be
received or sent:

HttpEntity.Strict
: The simplest entity, which is used when all the entity are already available in memory.
It wraps a plain `ByteString` and  represents a standard, unchunked entity with a known `Content-Length`.

HttpEntity.Default
: The general, unchunked HTTP/1.1 message entity.
It has a known length and presents its data as a `Source[ByteString]` which can be only materialized once.
It is an error if the provided source doesn't produce exactly as many bytes as specified.
The distinction of `Strict` and `Default` is an API-only one. One the wire, both kinds of entities look the same.

HttpEntity.Chunked
: The model for HTTP/1.1 [chunked content](http://tools.ietf.org/html/rfc7230#section-4.1) (i.e. sent with `Transfer-Encoding: chunked`).
The content length is unknown and the individual chunks are presented as a `Source[HttpEntity.ChunkStreamPart]`.
A `ChunkStreamPart` is either a non-empty `Chunk` or a `LastChunk` containing optional trailer headers.
The stream consists of zero or more `Chunked` parts and can be terminated by an optional `LastChunk` part.

HttpEntity.CloseDelimited
: An unchunked entity of unknown length that is implicitly delimited by closing the connection (`Connection: close`).
The content data are presented as a `Source[ByteString]`.
Since the connection must be closed after sending an entity of this type it can only be used on the server-side for
sending a response.
Also, the main purpose of `CloseDelimited` entities is compatibility with HTTP/1.0 peers, which do not support
chunked transfer encoding. If you are building a new application and are not constrained by legacy requirements you
shouldn't rely on `CloseDelimited` entities, since implicit terminate-by-connection-close is not a robust way of
signaling response end, especially in the presence of proxies. Additionally this type of entity prevents connection
reuse which can seriously degrade performance. Use `HttpEntity.Chunked` instead!

HttpEntity.IndefiniteLength
: A streaming entity of unspecified length for use in a `Multipart.BodyPart`.


Entity types `Strict`, `Default`, and `Chunked` are a subtype of `HttpEntity.Regular` which allows to use them
for requests and responses. In contrast, `HttpEntity.CloseDelimited` can only be used for responses.

Streaming entity types (i.e. all but `Strict`) cannot be shared or serialized. To create a strict, shareable copy of an
entity or message use `HttpEntity.toStrict` or `HttpMessage.toStrict` which returns a `Future` of the object with
the body data collected into a `ByteString`.

The `HttpEntity` companion object contains several helper constructors to create entities from common types easily.

You can pattern match over the subtypes of `HttpEntity` if you want to provide special handling for each of the
subtypes. However, in many cases a recipient of an `HttpEntity` doesn't care about of which subtype an entity is
(and how data is transported exactly on the HTTP layer). Therefore, the general method `HttpEntity.dataBytes` is
provided which returns a `Source[ByteString, Any]` that allows access to the data of an entity regardless of its
concrete subtype.

@@@ note { title='When to use which subtype?' }

 * Use `Strict` if the amount of data is "small" and already available in memory (e.g. as a `String` or `ByteString`)
 * Use `Default` if the data is generated by a streaming data source and the size of the data is known
 * Use `Chunked` for an entity of unknown length
 * Use `CloseDelimited` for a response as a legacy alternative to `Chunked` if the client doesn't support
chunked transfer encoding. Otherwise use `Chunked`!
 * In a `Multipart.Bodypart` use `IndefiniteLength` for content of unknown length.

@@@

@@@ warning { title="Caution" }

When you receive a non-strict message from a connection then additional data are only read from the network when you
request them by consuming the entity data stream. This means that, if you *don't* consume the entity stream then the
connection will effectively be stalled. In particular no subsequent message (request or response) will be read from
the connection as the entity of the current message "blocks" the stream.
Therefore you must make sure that you always consume the entity data, even in the case that you are not actually
interested in it!

@@@

### Limiting message entity length

All message entities that Akka HTTP reads from the network automatically get a length verification check attached to
them. This check makes sure that the total entity size is less than or equal to the configured
`max-content-length` <a id="^1" href="#1">[1]</a>, which is an important defense against certain Denial-of-Service attacks.
However, a single global limit for all requests (or responses) is often too inflexible for applications that need to
allow large limits for *some* requests (or responses) but want to clamp down on all messages not belonging into that
group.

In order to give you maximum flexibility in defining entity size limits according to your needs the `HttpEntity`
features a `withSizeLimit` method, which lets you adjust the globally configured maximum size for this particular
entity, be it to increase or decrease any previously set value.
This means that your application will receive all requests (or responses) from the HTTP layer, even the ones whose
`Content-Length` exceeds the configured limit (because you might want to increase the limit yourself).
Only when the actual data stream `Source` contained in the entity is materialized will the boundary checks be
actually applied. In case the length verification fails the respective stream will be terminated with an
`EntityStreamSizeException` either directly at materialization time (if the `Content-Length` is known) or whenever more
data bytes than allowed have been read.

When called on `Strict` entities the `withSizeLimit` method will return the entity itself if the length is within
the bound, otherwise a `Default` entity with a single element data stream. This allows for potential refinement of the
entity size limit at a later point (before materialization of the data stream).

By default all message entities produced by the HTTP layer automatically carry the limit that is defined in the
application's `max-content-length` config setting. If the entity is transformed in a way that changes the
content-length and then another limit is applied then this new limit will be evaluated against the new
content-length. If the entity is transformed in a way that changes the content-length and no new limit is applied
then the previous limit will be applied against the previous content-length.
Generally this behavior should be in line with your expectations.

> <a id="1" href="#^1">[1]</a> *akka.http.parsing.max-content-length* (applying to server- as well as client-side),
*akka.http.server.parsing.max-content-length* (server-side only),
*akka.http.client.parsing.max-content-length* (client-side only) or
*akka.http.host-connection-pool.client.parsing.max-content-length* (only host-connection-pools)

### Special processing for HEAD requests

[RFC 7230](http://tools.ietf.org/html/rfc7230#section-3.3.3) defines very clear rules for the entity length of HTTP messages.

Especially this rule requires special treatment in Akka HTTP:

>
Any response to a HEAD request and any response with a 1xx
(Informational), 204 (No Content), or 304 (Not Modified) status
code is always terminated by the first empty line after the
header fields, regardless of the header fields present in the
message, and thus cannot contain a message body.

Responses to HEAD requests introduce the complexity that *Content-Length* or *Transfer-Encoding* headers
can be present but the entity is empty. This is modeled by allowing *HttpEntity.Default* and *HttpEntity.Chunked*
to be used for HEAD responses with an empty data stream.

Also, when a HEAD response has an *HttpEntity.CloseDelimited* entity the Akka HTTP implementation will *not* close the
connection after the response has been sent. This allows the sending of HEAD responses without *Content-Length*
header across persistent HTTP connections.

<a id="header-model-scala"></a>
## Header Model

Akka HTTP contains a rich model of the most common HTTP headers. Parsing and rendering is done automatically so that
applications don't need to care for the actual syntax of headers. Headers not modelled explicitly are represented
as a `RawHeader` (which is essentially a String/String name/value pair).

See these examples of how to deal with headers:

@@snip [ModelSpec.scala](../../../../../test/scala/docs/http/scaladsl/ModelSpec.scala) { #headers }

## HTTP Headers

When the Akka HTTP server receives an HTTP request it tries to parse all its headers into their respective
model classes. Independently of whether this succeeds or not, the HTTP layer will
always pass on all received headers to the application. Unknown headers as well as ones with invalid syntax (according
to the header parser) will be made available as `RawHeader` instances. For the ones exhibiting parsing errors a
warning message is logged depending on the value of the `illegal-header-warnings` config setting.

Some headers have special status in HTTP and are therefore treated differently from "regular" headers:

Content-Type
: The Content-Type of an HTTP message is modeled as the `contentType` field of the `HttpEntity`.
The `Content-Type` header therefore doesn't appear in the `headers` sequence of a message.
Also, a `Content-Type` header instance that is explicitly added to the `headers` of a request or response will
not be rendered onto the wire and trigger a warning being logged instead!

Transfer-Encoding
: Messages with `Transfer-Encoding: chunked` are represented via the `HttpEntity.Chunked` entity.
As such chunked messages that do not have another deeper nested transfer encoding will not have a `Transfer-Encoding`
header in their `headers` sequence.
Similarly, a `Transfer-Encoding` header instance that is explicitly added to the `headers` of a request or
response will not be rendered onto the wire and trigger a warning being logged instead!

Content-Length
: The content length of a message is modelled via its [HttpEntity](#httpentity-scala). As such no `Content-Length` header will ever
be part of a message's `header` sequence.
Similarly, a `Content-Length` header instance that is explicitly added to the `headers` of a request or
response will not be rendered onto the wire and trigger a warning being logged instead!

Server
: A `Server` header is usually added automatically to any response and its value can be configured via the
`akka.http.server.server-header` setting. Additionally an application can override the configured header with a
custom one by adding it to the response's `header` sequence.

User-Agent
: A `User-Agent` header is usually added automatically to any request and its value can be configured via the
`akka.http.client.user-agent-header` setting. Additionally an application can override the configured header with a
custom one by adding it to the request's `header` sequence.

Date
: The `Date` response header is added automatically but can be overridden by supplying it manually.

Connection
: On the server-side Akka HTTP watches for explicitly added `Connection: close` response headers and as such honors
the potential wish of the application to close the connection after the respective response has been sent out.
The actual logic for determining whether to close the connection is quite involved. It takes into account the
request's method, protocol and potential `Connection` header as well as the response's protocol, entity and
potential `Connection` header. See @github[this test](/akka-http-core/src/test/scala/akka/http/impl/engine/rendering/ResponseRendererSpec.scala#L422) for a full table of what happens when.

Strict-Transport-Security
: HTTP Strict Transport Security (HSTS) is a web security policy mechanism which is communicated by the
`Strict-Transport-Security` header. The most important security vulnerability that HSTS can fix is SSL-stripping
man-in-the-middle attacks. The SSL-stripping attack works by transparently converting a secure HTTPS connection into a
plain HTTP connection. The user can see that the connection is insecure, but crucially there is no way of knowing
whether the connection should be secure. HSTS addresses this problem by informing the browser that connections to the
site should always use TLS/SSL. See also [RFC 6797](http://tools.ietf.org/html/rfc6797).


<a id="custom-headers-scala"></a>
## Custom Headers

Sometimes you may need to model a custom header type which is not part of HTTP and still be able to use it
as convenient as is possible with the built-in types.

Because of the number of ways one may interact with headers (i.e. try to match a `CustomHeader` against a `RawHeader`
or the other way around etc), a helper trait for custom Header types and their companions classes are provided by Akka HTTP.
Thanks to extending `ModeledCustomHeader` instead of the plain `CustomHeader` such header can be matched

@@snip [ModeledCustomHeaderSpec.scala](../../../../../../../akka-http-tests/src/test/scala/akka/http/scaladsl/server/ModeledCustomHeaderSpec.scala) { #modeled-api-key-custom-header }

Which allows the this CustomHeader to be used in the following scenarios:

@@snip [ModeledCustomHeaderSpec.scala](../../../../../../../akka-http-tests/src/test/scala/akka/http/scaladsl/server/ModeledCustomHeaderSpec.scala) { #matching-examples }

Including usage within the header directives like in the following @ref[headerValuePF](../routing-dsl/directives/header-directives/headerValuePF.md#headervaluepf) example:

@@snip [ModeledCustomHeaderSpec.scala](../../../../../../../akka-http-tests/src/test/scala/akka/http/scaladsl/server/ModeledCustomHeaderSpec.scala) { #matching-in-routes }

One can also directly extend `CustomHeader` which requires less boilerplate, however that has the downside of
matching against `RawHeader` instances not working out-of-the-box, thus limiting its usefulness in the routing layer
of Akka HTTP. For only rendering such header however it would be enough.

@@@ note
When defining custom headers, prefer to extend `ModeledCustomHeader` instead of `CustomHeader` directly
as it will automatically make your header abide all the expected pattern matching semantics one is accustomed to
when using built-in types (such as matching a custom header against a `RawHeader` as is often the case in routing
layers of Akka HTTP applications).
@@@

## Parsing / Rendering

Parsing and rendering of HTTP data structures is heavily optimized and for most types there's currently no public API
provided to parse (or render to) Strings or byte arrays.

@@@ note
Various parsing and rendering settings are available to tweak in the configuration under `akka.http.client[.parsing]`,
`akka.http.server[.parsing]` and `akka.http.host-connection-pool[.client.parsing]`, with defaults for all of these
being defined in the `akka.http.parsing` configuration section.

For example, if you want to change a parsing setting for all components, you can set the `akka.http.parsing.illegal-header-warnings = off`
value. However this setting can be still overridden by the more specific sections, like for example `akka.http.server.parsing.illegal-header-warnings = on`.

In this case both `client` and `host-connection-pool` APIs will see the setting `off`, however the server will see `on`.

In the case of `akka.http.host-connection-pool.client` settings, they default to settings set in `akka.http.client`,
and can override them if needed. This is useful, since both `client` and `host-connection-pool` APIs,
such as the Client API `Http().outgoingConnection` or the Host Connection Pool APIs `Http().singleRequest` or `Http().superPool`,
usually need the same settings, however the `server` most likely has a very different set of settings.
@@@

<a id="registeringcustommediatypes"></a>
## Registering Custom Media Types

Akka HTTP [predefines](https://github.com/akka/akka-http/blob/master/akka-http-core/src/main/scala/akka/http/scaladsl/model/MediaType.scala#L297) most commonly encountered media types and emits them in their well-typed form while parsing http messages.
Sometimes you may want to define a custom media type and inform the parser infrastructure about how to handle these custom
media types, e.g. that `application/custom` is to be treated as `NonBinary` with `WithFixedCharset`. To achieve this you
need to register the custom media type in the server's settings by configuring `ParserSettings` like this:

@@snip [CustomMediaTypesSpec.scala](../../../../../../../akka-http-tests/src/test/scala/akka/http/scaladsl/CustomMediaTypesSpec.scala) { #application-custom }

You may also want to read about MediaType [Registration trees](https://en.wikipedia.org/wiki/Media_type#Registration_trees), in order to register your vendor specific media types
in the right style / place.

<a id="registeringcustomstatuscodes"></a>
## Registering Custom Status Codes

Similarily to media types, Akka HTTP @scaladoc:[predefines](akka.http.scaladsl.model.StatusCodes$)
well-known status codes, however sometimes you may need to use a custom one (or are forced to use an API which returns custom status codes).
Similarily to the media types registration, you can register custom status codes by configuring `ParserSettings` like this:

@@snip [CustomHttpMethodSpec.scala](../../../../../../../docs/http/scaladsl/server/directives/CustomHttpStatusCodeSpec.scala) { #application-custom }

## The URI model

Akka HTTP offers its own specialised URI model class which is tuned for both performance and idiomatic usage within
other types of the HTTP model. For example, an `HTTPRequest`'s target URI is parsed into this type, where all character
escaping and other URI specific semantics are applied.

### Obtaining the Raw Request URI

Sometimes it may be needed to obtain the "raw" value of an incoming URI, without applying any escaping or parsing to it.
While this use-case is rare, it comes up every once in a while. It is possible to obtain the "raw" request URI in Akka
HTTP Server side by turning on the `akka.http.server.raw-request-uri-header` flag.
When enabled, a `Raw-Request-URI` header will be added to each request. This header will hold the original raw request's
URI that was used. For an example check the reference configuration.
