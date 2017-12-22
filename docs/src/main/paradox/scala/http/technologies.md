# 7. Supported Technologies

This page gives an overview over the technologies that Akka HTTP implements, supports, and integrates with. The page is
still quite new. If you are looking for support of some technology and found information somewhere else, please help us fill
out this page using the link at the bottom.

## HTTP

Akka HTTP implements HTTP/1.1 including these features (non-exclusive list):

 * Persistent connections
 * HTTP Pipelining (currently not supported on the client-side)
 * 100-Continue
 * @ref[Client Connection Pooling](client-side/request-level.md)

## HTTPS

HTTPS is supported through the facilities that Java provides. See @ref[Server HTTPS Support](server-side/server-https-support.md)
and @ref[Client HTTPS Support](client-side/client-https-support.md) for more information.

## WebSocket

Akka HTTP implements WebSocket on both the server side and the client side. See @ref[Server Websocket Support](server-side/websocket-support.md)
and @ref[Client Websocket Support](client-side/websocket-support.md) for more information.

## HTTP/2

Akka HTTP provides server-side HTTP/2 support currently in a preview version. See @ref[Server HTTP/2 Support](server-side/http2.md)
for more information.

## Multipart

Akka HTTP has modeled multipart/* payloads. It provides streaming multipart parsers and renderers e.g. for parsing
file uploads and provides a typed model to access details of such a payload.

## Server-sent Events (SSE)

Server-sent Events (SSE) are supported through marshalling that will provide or consume an (Akka Stream based) stream of
events. See @ref[SSE Support](sse-support.md) for more information.

## JSON

Marshalling to and from JSON is supported out of the box for spray-json-based model in Scala and Jackson-based models in
Java. See @ref[JSON Support](common/json-support.md) for more information.

## XML

Marshalling to and from XML is supported Scala XML literals. See @ref[XML Support](common/xml-support.md) for more information.


## Gzip and Deflate Content-Encoding

GZIP and Deflate content-encodings for automatic encoding / decoding of HTTP payloads are supported through directives.
See @ref[CodingDirectives](routing-dsl/directives/coding-directives/index.md) for more information.