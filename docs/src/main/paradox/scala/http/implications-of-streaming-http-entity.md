# Implications of the streaming nature of Request/Response Entities

Akka HTTP is streaming *all the way through*, which means that the back-pressure mechanisms enabled by Akka Streams
are exposed through all layers–from the TCP layer, through the HTTP server, all the way up to the user-facing @unidoc[HttpRequest]
and @unidoc[HttpResponse] and their @unidoc[HttpEntity] APIs.

This has surprising implications if you are used to non-streaming / not-reactive HTTP clients.
Specifically it means that: "*lack of consumption of the HTTP Entity, is signaled as back-pressure to the other
side of the connection*". This is a feature, as it allows one only to consume the entity, and back-pressure servers/clients
from overwhelming our application, possibly causing un-necessary buffering of the entity in memory.

@@@ warning

Consuming (or discarding) the Entity of a request is mandatory!
If *accidentally* left neither consumed or discarded Akka HTTP will
assume the incoming data should remain back-pressured, and will stall the incoming data via TCP back-pressure mechanisms.
A client should consume the Entity regardless of the status of the @unidoc[HttpResponse].

@@@

## Client-Side handling of streaming HTTP Entities

### Consuming the HTTP Response Entity (Client)

The most common use-case of course is consuming the response entity, which can be done via
running the underlying `dataBytes` Source. This is as simple as running the dataBytes source,
(or on the server-side using directives such as `BasicDirectives.extractDataBytes`).

It is encouraged to use various streaming techniques to utilise the underlying infrastructure to its fullest,
for example by framing the incoming chunks, parsing them line-by-line and then connecting the flow into another
destination Sink, such as a File or other Akka Streams connector:

Scala
:   @@snip [HttpClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpClientExampleSpec.scala) { #manual-entity-consume-example-1 }

Java
:   @@snip [HttpClientExampleDocTest.java]($test$/java/docs/http/javadsl/HttpClientExampleDocTest.java) { #manual-entity-consume-example-1 }

however sometimes the need may arise to consume the entire entity as `Strict` entity (which means that it is
completely loaded into memory). Akka HTTP provides a special @scala[`toStrict(timeout)`]@java[`toStrict(timeout, materializer)`] method which can be used to
eagerly consume the entity and make it available in memory:

Scala
:   @@snip [HttpClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpClientExampleSpec.scala) { #manual-entity-consume-example-2 }

Java
:   @@snip [HttpClientExampleDocTest.java]($test$/java/docs/http/javadsl/HttpClientExampleDocTest.java) { #manual-entity-consume-example-2 }

### Discarding the HTTP Response Entity (Client)

Sometimes when calling HTTP services we do not care about their response payload (e.g. all we care about is the response code),
yet as explained above entity still has to be consumed in some way, otherwise we'll be exerting back-pressure on the
underlying TCP connection.

The `discardEntityBytes` convenience method serves the purpose of easily discarding the entity if it has no purpose for us.
It does so by piping the incoming bytes directly into an `Sink.ignore`.

The two snippets below are equivalent, and work the same way on the server-side for incoming HTTP Requests:

Scala
:   @@snip [HttpClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpClientExampleSpec.scala) { #manual-entity-discard-example-1 }

Java
:   @@snip [HttpClientExampleDocTest.java]($test$/java/docs/http/javadsl/HttpClientExampleDocTest.java) { #manual-entity-discard-example-1 }

Or the equivalent low-level code achieving the same result:

Scala
:   @@snip [HttpClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpClientExampleSpec.scala) { #manual-entity-discard-example-2 }

Java
:   @@snip [HttpClientExampleDocTest.java]($test$/java/docs/http/javadsl/HttpClientExampleDocTest.java) { #manual-entity-discard-example-2 }

## Server-Side handling of streaming HTTP Entities

Similarly as with the Client-side, HTTP Entities are directly linked to Streams which are fed by the underlying
TCP connection. Thus, if request entities remain not consumed, the server will back-pressure the connection, expecting
that the user-code will eventually decide what to do with the incoming data.

Note that some directives force an implicit `toStrict` operation, such as @scala[`entity(as[String])`]@java[`entity(exampleUnmarshaller, example -> {})`] and similar ones.

### Consuming the HTTP Request Entity (Server)

The simplest way of consuming the incoming request entity is to simply transform it into an actual domain object,
for example by using the @ref[entity](routing-dsl/directives/marshalling-directives/entity.md) directive:

Scala
:   @@snip [HttpServerExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #consume-entity-directive }

Java
:   @@snip [HttpServerExampleDocTest.java]($test$/java/docs/http/javadsl/server/HttpServerExampleDocTest.java) { #consume-entity-directive }

Of course you can access the raw dataBytes as well and run the underlying stream, for example piping it into an
FileIO Sink, that signals completion via a @scala[`Future[IoResult]`]@java[`CompletionStage<IoResult>`] once all the data has been written into the file:

Scala
:   @@snip [HttpServerExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #consume-raw-dataBytes }

Java
:   @@snip [HttpServerExampleDocTest.java]($test$/java/docs/http/javadsl/server/HttpServerExampleDocTest.java) { #consume-raw-dataBytes }

### Discarding the HTTP Request Entity (Server)

Sometimes, depending on some validation (e.g. checking if given user is allowed to perform uploads or not)
you may want to decide to discard the uploaded entity.

Please note that discarding means that the entire upload will proceed, even though you are not interested in the data
being streamed to the server - this may be useful if you are simply not interested in the given entity, however
you don't want to abort the entire connection (which we'll demonstrate as well), since there may be more requests
pending on the same connection still.

In order to discard the databytes explicitly you can invoke the `discardEntityBytes` bytes of the incoming `HTTPRequest`:

Scala
:   @@snip [HttpServerExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #discard-discardEntityBytes }

Java
:   @@snip [HttpServerExampleDocTest.java]($test$/java/docs/http/javadsl/server/HttpServerExampleDocTest.java) { #discard-discardEntityBytes }

A related concept is *cancelling* the incoming @scala[`entity.dataBytes`]@java[`entity.getDataBytes()`] stream, which results in Akka HTTP
*abruptly closing the connection from the Client*. This may be useful when you detect that the given user should not be allowed to make any
uploads at all, and you want to drop the connection (instead of reading and ignoring the incoming data).
This can be done by attaching the incoming @scala[`entity.dataBytes`]@java[`entity.getDataBytes()`] to a `Sink.cancelled()` which will cancel
the entity stream, which in turn will cause the underlying connection to be shut-down by the server –
effectively hard-aborting the incoming request:

Scala
:   @@snip [HttpServerExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #discard-close-connections }

Java
:   @@snip [HttpServerExampleDocTest.java]($test$/java/docs/http/javadsl/server/HttpServerExampleDocTest.java) { #discard-close-connections }

Closing connections is also explained in depth in the @ref[Closing a connection](server-side/low-level-api.md#http-closing-connection-low-level)
section of the docs.

### Pending: Automatic discarding of not used entities

Under certain conditions it is possible to detect an entity is very unlikely to be used by the user for a given request,
and issue warnings or discard the entity automatically. This advanced feature has not been implemented yet, see the below
note and issues for further discussion and ideas.

@@@ note

An advanced feature code named "auto draining" has been discussed and proposed for Akka HTTP, and we're hoping
to implement or help the community implement it.

You can read more about it in [issue #183](https://github.com/akka/akka-http/issues/183)
as well as [issue #117](https://github.com/akka/akka-http/issues/117) ; as always, contributions are very welcome!

@@@
