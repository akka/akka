<a id="http-model-java"></a>
# HTTP Model

Akka HTTP model contains a deeply structured, fully immutable, case-class based model of all the major HTTP data
structures, like HTTP requests, responses and common headers.
It lives in the *akka-http-core* module and forms the basis for most of Akka HTTP's APIs.

## Overview

Since akka-http-core provides the central HTTP data structures you will find the following import in quite a
few places around the code base (and probably your own code as well):

@@snip [ModelDocTest.java](../../../../../test/java/docs/http/javadsl/ModelDocTest.java) { #import-model }

This brings all of the most relevant types in scope, mainly:

 * `HttpRequest` and `HttpResponse`, the central message model
 * `headers`, the package containing all the predefined HTTP header models and supporting types
 * Supporting types like `Uri`, `HttpMethods`, `MediaTypes`, `StatusCodes`, etc.

A common pattern is that the model of a certain entity is represented by an immutable type (class or trait),
while the actual instances of the entity defined by the HTTP spec live in an accompanying object carrying the name of
the type plus a trailing plural 's'.

For example:

 * Defined `HttpMethod` instances are defined as static fields of the `HttpMethods` class.
 * Defined `HttpCharset` instances are defined as static fields of the `HttpCharsets` class.
 * Defined `HttpEncoding` instances are defined as static fields of the `HttpEncodings` class.
 * Defined `HttpProtocol` instances are defined as static fields of the `HttpProtocols` class.
 * Defined `MediaType` instances are defined as static fields of the `MediaTypes` class.
 * Defined `StatusCode` instances are defined as static fields of the `StatusCodes` class.

## HttpRequest

`HttpRequest` and `HttpResponse` are the basic immutable classes representing HTTP messages.

An `HttpRequest` consists of

 * a method (GET, POST, etc.)
 * a URI (see @ref[URI model](uri-model.md) for more information)
 * a seq of headers
 * an entity (body data)
 * a protocol

Here are some examples how to construct an `HttpRequest`:

@@snip [ModelDocTest.java](../../../../../test/java/docs/http/javadsl/ModelDocTest.java) { #construct-request }

In its basic form `HttpRequest.create` creates an empty default GET request without headers which can then be
transformed using one of the `withX` methods, `addHeader`, or `addHeaders`. Each of those will create a
new immutable instance, so instances can be shared freely. There exist some overloads for `HttpRequest.create` that
simplify creating requests for common cases. Also, to aid readability, there are predefined alternatives for `create`
named after HTTP methods to create a request with a given method and URI directly.

## HttpResponse

An `HttpResponse` consists of

 * a status code
 * a list of headers
 * an entity (body data)
 * a protocol

Here are some examples how to construct an `HttpResponse`:

@@snip [ModelDocTest.java](../../../../../test/java/docs/http/javadsl/ModelDocTest.java) { #construct-response }

In addition to the simple `HttpEntities.create` methods which create an entity from a fixed `String` or `ByteString`
as shown here the Akka HTTP model defines a number of subclasses of `HttpEntity` which allow body data to be specified as a
stream of bytes. All of these types can be created using the method on `HttpEntites`.

<a id="httpentity-java"></a>
## HttpEntity

An `HttpEntity` carries the data bytes of a message together with its Content-Type and, if known, its Content-Length.
In Akka HTTP there are five different kinds of entities which model the various ways that message content can be
received or sent:

HttpEntityStrict
: The simplest entity, which is used when all the entity are already available in memory.
It wraps a plain `ByteString` and  represents a standard, unchunked entity with a known `Content-Length`.

HttpEntityDefault
: The general, unchunked HTTP/1.1 message entity.
It has a known length and presents its data as a `Source<ByteString, ?>` which can be only materialized once.
It is an error if the provided source doesn't produce exactly as many bytes as specified.
The distinction of `HttpEntityStrict` and `HttpEntityDefault` is an API-only one. One the wire,
both kinds of entities look the same.

HttpEntityChunked
: The model for HTTP/1.1 [chunked content](http://tools.ietf.org/html/rfc7230#section-4.1) (i.e. sent with `Transfer-Encoding: chunked`).
The content length is unknown and the individual chunks are presented as a `Source<ChunkStreamPart, ?>`.
A `ChunkStreamPart` is either a non-empty chunk or the empty last chunk containing optional trailer headers.
The stream consists of zero or more non-empty chunks parts and can be terminated by an optional last chunk.

HttpEntityCloseDelimited
: An unchunked entity of unknown length that is implicitly delimited by closing the connection (`Connection: close`).
Content data is presented as a `Source<ByteString, ?>`.
Since the connection must be closed after sending an entity of this type it can only be used on the server-side for
sending a response.
Also, the main purpose of `CloseDelimited` entities is compatibility with HTTP/1.0 peers, which do not support
chunked transfer encoding. If you are building a new application and are not constrained by legacy requirements you
shouldn't rely on `CloseDelimited` entities, since implicit terminate-by-connection-close is not a robust way of
signaling response end, especially in the presence of proxies. Additionally this type of entity prevents connection
reuse which can seriously degrade performance. Use `HttpEntityChunked` instead!

HttpEntityIndefiniteLength
: A streaming entity of unspecified length for use in a `Multipart.BodyPart`.


Entity types `HttpEntityStrict`, `HttpEntityDefault`, and `HttpEntityChunked` are a subtype of `RequestEntity`
which allows to use them for requests and responses. In contrast, `HttpEntityCloseDelimited` can only be used for responses.

Streaming entity types (i.e. all but `HttpEntityStrict`) cannot be shared or serialized. To create a strict, shareable copy of an
entity or message use `HttpEntity.toStrict` or `HttpMessage.toStrict` which returns a `CompletionStage` of the object with
the body data collected into a `ByteString`.

The class `HttpEntities` contains static methods to create entities from common types easily.

You can use the `isX` methods of `HttpEntity` to find out of which subclass an entity is if you want to provide
special handling for each of the subtypes. However, in many cases a recipient of an `HttpEntity` doesn't care about
of which subtype an entity is (and how data is transported exactly on the HTTP layer). Therefore, the general method
`HttpEntity.getDataBytes()` is provided which returns a `Source<ByteString, ?>` that allows access to the data of an
entity regardless of its concrete subtype.

@@@ note { title='When to use which subtype?' }

 * Use `HttpEntityStrict` if the amount of data is "small" and already available in memory (e.g. as a `String` or `ByteString`)
 * Use `HttpEntityDefault` if the data is generated by a streaming data source and the size of the data is known
 * Use `HttpEntityChunked` for an entity of unknown length
 * Use `HttpEntityCloseDelimited` for a response as a legacy alternative to `HttpEntityChunked` if the client
doesn't support chunked transfer encoding. Otherwise use `HttpEntityChunked`!
 * In a `Multipart.Bodypart` use `HttpEntityIndefiniteLength` for content of unknown length.

@@@


@@@ warning { title="Caution" }

When you receive a non-strict message from a connection then additional data is only read from the network when you
request it by consuming the entity data stream. This means that, if you *don't* consume the entity stream then the
connection will effectively be stalled. In particular, no subsequent message (request or response) will be read from
the connection as the entity of the current message "blocks" the stream.
Therefore you must make sure that you always consume the entity data, even in the case that you are not actually
interested in it!

@@@

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
can be present but the entity is empty. This is modeled by allowing *HttpEntityDefault* and *HttpEntityChunked*
to be used for HEAD responses with an empty data stream.

Also, when a HEAD response has an *HttpEntityCloseDelimited* entity the Akka HTTP implementation will *not* close the
connection after the response has been sent. This allows the sending of HEAD responses without *Content-Length*
header across persistent HTTP connections.

## Header Model

Akka HTTP contains a rich model of the most common HTTP headers. Parsing and rendering is done automatically so that
applications don't need to care for the actual syntax of headers. Headers not modelled explicitly are represented
as a `RawHeader` (which is essentially a String/String name/value pair).

See these examples of how to deal with headers:

@@snip [ModelDocTest.java](../../../../../test/java/docs/http/javadsl/ModelDocTest.java) { #headers }

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
: Messages with `Transfer-Encoding: chunked` are represented as a `HttpEntityChunked` entity.
As such chunked messages that do not have another deeper nested transfer encoding will not have a `Transfer-Encoding`
header in their `headers` list.
Similarly, a `Transfer-Encoding` header instance that is explicitly added to the `headers` of a request or
response will not be rendered onto the wire and trigger a warning being logged instead!

Content-Length
: The content length of a message is modelled via its [HttpEntity](#httpentity-java). As such no `Content-Length` header will ever
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
such as the Client API `Http.get(sys).outgoingConnection` or the Host Connection Pool APIs `Http.get(sys).singleRequest`
or `Http.get(sys).superPool`, usually need the same settings, however the `server` most likely has a very different set of settings.

@@@