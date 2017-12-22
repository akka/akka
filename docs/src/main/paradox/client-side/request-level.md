# Request-Level Client-Side API

The request-level API is the recommended and most convenient way of using Akka HTTP's client-side functionality. It internally builds upon the
@ref[Host-Level Client-Side API](host-level.md) to provide you with a simple and easy-to-use way of retrieving HTTP responses from remote servers.
Depending on your preference you can pick the flow-based or the future-based variant.

@@@ note
It is recommended to first read the @ref[Implications of the streaming nature of Request/Response Entities](../implications-of-streaming-http-entity.md) section,
as it explains the underlying full-stack streaming concepts, which may be unexpected when coming
from a background with non-"streaming first" HTTP Clients.
@@@

@@@ note
The request-level API is implemented on top of a connection pool that is shared inside the actor system. A consequence of
using a pool is that long-running requests block a connection while running and starve other requests. Make sure not to use
the request-level API for long-running requests like long-polling GET requests. Use the @ref[Connection-Level Client-Side API](connection-level.md)
or an extra pool just for the long-running connection instead.
@@@

## Future-Based Variant

Most often, your HTTP client needs are very basic. You simply need the HTTP response for a certain request and don't
want to bother with setting up a full-blown streaming infrastructure.

For these cases Akka HTTP offers the @scala[`Http().singleRequest(...)`]@java[`Http.get(system).singleRequest(...)`] method, which simply turns an @unidoc[HttpRequest] instance
into @scala[`Future[HttpResponse]`]@java[`CompletionStage<HttpResponse>`]. Internally the request is dispatched across the (cached) host connection pool for the
request's effective URI.

Just like in the case of the super-pool flow described above the request must have either an absolute URI or a valid
`Host` header, otherwise the returned future will be completed with an error.

### Example

Scala
:   @@snip [HttpClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpClientExampleSpec.scala) { #single-request-example }

Java
:   @@snip [HttpClientExampleDocTest.java]($test$/java/docs/http/javadsl/HttpClientExampleDocTest.java) { #single-request-example }

### Using the Future-Based API in Actors

When using the @scala[`Future`]@java[`CompletionStage`] based API from inside an @unidoc[Actor], all the usual caveats apply to how one should deal
with the futures completion. For example you should not access the actor's state from within the @scala[`Future`]@java[`CompletionStage`]'s callbacks
(such as `map`, `onComplete`, ...) and instead you should use the @scala[`pipeTo`]@java[`pipe`] pattern to pipe the result back
to the actor as a message:

Scala
:   @@snip [HttpClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpClientExampleSpec.scala) { #single-request-in-actor-example }

Java
:   

        import akka.actor.AbstractActor;
        import akka.http.javadsl.Http;
        import akka.http.javadsl.model.HttpRequest;
        import akka.http.javadsl.model.HttpResponse;
        import akka.japi.pf.ReceiveBuilder;
        import akka.stream.ActorMaterializer;
        import akka.stream.Materializer;
        import scala.concurrent.ExecutionContextExecutor;
        
        import java.util.concurrent.CompletionStage;
        
        import static akka.pattern.PatternsCS.pipe;
        
        public class SingleRequestInActorExample extends AbstractActor {
            final Http http = Http.get(context().system());
            final ExecutionContextExecutor dispatcher = context().dispatcher();
            final Materializer materializer = ActorMaterializer.create(context());
        
            public SingleRequestInActorExample() { // syntax changes slightly in Akka 2.5, see the migration guide
                receive(ReceiveBuilder
                        .match(String.class, url -> pipe(fetch(url), dispatcher).to(self()))
                        .build());
            }
        
            CompletionStage<HttpResponse> fetch(String url) {
                return http.singleRequest(HttpRequest.create(url), materializer);
            }
        }
       

@@@ warning

Always make sure you consume the response entity streams (of type @scala[@unidoc[Source[ByteString,Unit]]]@java[@unidoc[Source[ByteString, Object]]]) by for example connecting it to a @unidoc[Sink] (for example @scala[`response.discardEntityBytes()`]@java[`response.discardEntityBytes(Materializer)`] if you don't care about the
response entity), since otherwise Akka HTTP (and the underlying Streams infrastructure) will understand the
lack of entity consumption as a back-pressure signal and stop reading from the underlying TCP connection!

This is a feature of Akka HTTP that allows consuming entities (and pulling them through the network) in
a streaming fashion, and only *on demand* when the client is ready to consume the bytes -
it may be a bit surprising at first though.

There are tickets open about automatically dropping entities if not consumed ([#183](https://github.com/akka/akka-http/issues/183) and [#117](https://github.com/akka/akka-http/issues/117)),
so these may be implemented in the near future.

@@@

## Flow-Based Variant

The flow-based variant of the request-level client-side API is presented by the @scala[`Http().superPool(...)`]@java[`Http.get(system).superPool(...)`] method.
It creates a new "super connection pool flow", which routes incoming requests to a (cached) host connection pool
depending on their respective effective URIs.

The @unidoc[Flow] returned by @scala[`Http().superPool(...)`]@java[`Http.get(system).superPool(...)`] is very similar to the one from the @ref[Host-Level Client-Side API](host-level.md), so the
@ref[Using a Host Connection Pool](host-level.md#using-a-host-connection-pool) section also applies here.

However, there is one notable difference between a "host connection pool client flow" for the host-level API and a
"super-pool flow":
Since in the former case the flow has an implicit target host context the requests it takes don't need to have absolute
URIs or a valid `Host` header. The host connection pool will automatically add a `Host` header if required.

For a super-pool flow this is not the case. All requests to a super-pool must either have an absolute URI or a valid
`Host` header, because otherwise it'd be impossible to find out which target endpoint to direct the request to.
