# Introduction

The Akka HTTP modules implement a full server- and client-side HTTP stack on top of *akka-actor* and *akka-stream*. It's
not a web-framework but rather a more general toolkit for providing and consuming HTTP-based services. While interaction
with a browser is of course also in scope it is not the primary focus of Akka HTTP.

Akka HTTP follows a rather open design and many times offers several different API levels for "doing the same thing".
You get to pick the API level of abstraction that is most suitable for your application.
This means that, if you have trouble achieving something using a high-level API, there's a good chance that you can get
it done with a low-level API, which offers more flexibility but might require you to write more application code.

## Philosophy

Akka HTTP has been driven with a clear focus on providing tools for building integration layers rather than application cores. As such it regards itself as a suite of libraries rather than a framework.

A framework, as we’d like to think of the term, gives you a “frame”, in which you build your application. It comes with a lot of decisions already pre-made and provides a foundation including support structures that lets you get started and deliver results quickly. In a way a framework is like a skeleton onto which you put the “flesh” of your application in order to have it come alive. As such frameworks work best if you choose them before you start application development and try to stick to the frameworks “way of doing things” as you go along.

For example, if you are building a browser-facing web application it makes sense to choose a web framework and build your application on top of it because the “core” of the application is the interaction of a browser with your code on the web-server. The framework makers have chosen one “proven” way of designing such applications and let you “fill in the blanks” of a more or less flexible “application-template”. Being able to rely on best-practice architecture like this can be a great asset for getting things done quickly.

However, if your application is not primarily a web application because its core is not browser-interaction but some specialized maybe complex business service and you are merely trying to connect it to the world via a REST/HTTP interface a web-framework might not be what you need. In this case the application architecture should be dictated by what makes sense for the core not the interface layer. Also, you probably won’t benefit from the possibly existing browser-specific framework components like view templating, asset management, JavaScript- and CSS generation/manipulation/minification, localization support, AJAX support, etc.

Akka HTTP was designed specifically as “not-a-framework”, not because we don’t like frameworks, but for use cases where a framework is not the right choice. Akka HTTP is made for building integration layers based on HTTP and as such tries to “stay on the sidelines”. Therefore you normally don’t build your application “on top of” Akka HTTP, but you build your application on top of whatever makes sense and use Akka HTTP merely for the HTTP integration needs.

On the other hand, if you prefer to build your applications with the guidance of a framework, you should give Play Framework or Lagom a try, which both use Akka internally.

## Using Akka HTTP

Akka HTTP is provided in a separate jar file, to use it make sure to include the following dependency:

@@@vars
```sbt
"com.typesafe.akka" %% "akka-http" % "$project.version$" $crossString$
```
@@@

Mind that `akka-http` comes in two modules: `akka-http` and `akka-http-core`. Because `akka-http`
depends on `akka-http-core` you don't need to bring the latter explicitly. Still you may need to this in case you rely
solely on low-level API; make sure the scala version should be a recent release of version `2.11`.


## Routing DSL for HTTP servers

The high-level, routing API of Akka HTTP provides a DSL to describe HTTP "routes" and how they should be handled.
Each route is composed of one or more level of `Directive` s that narrows down to handling one specific type of
request.

For example one route might start with matching the `path` of the request, only matching if it is "/hello", then
narrowing it down to only handle HTTP `get` requests and then `complete` those with a string literal, which
will be sent back as a HTTP OK with the string as response body.

Transforming request and response bodies between over-the-wire formats and objects to be used in your application is
done separately from the route declarations, in marshallers, which are pulled in implicitly using the "magnet" pattern.
This means that you can `complete` a request with any kind of object a as long as there is an implicit marshaller
available in scope.

Default marshallers are provided for simple objects like String or ByteString, and you can define your own for example
for JSON. An additional module provides JSON serialization using the spray-json library (see @ref[JSON Support](common/json-support.md#akka-http-spray-json)
for details).

The `Route` created using the Route DSL is then "bound" to a port to start serving HTTP requests:

@@snip [HttpServerExampleSpec.scala](../../../../test/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #minimal-routing-example }

A common use case is to reply to a request using a model object having the marshaller transform it into JSON. In
this case shown by two separate routes. The first route queries an asynchronous database and marshalls the
`Future[Option[Item]]` result into a JSON response. The second unmarshalls an `Order` from the incoming request
saves it to the database and replies with an OK when done.

@@snip [SprayJsonExampleSpec.scala](../../../../test/scala/docs/http/scaladsl/SprayJsonExampleSpec.scala) { #second-spray-json-example }

The logic for the marshalling and unmarshalling JSON in this example is provided by the "spray-json" library
(details on how to use that here: @ref[JSON Support](common/json-support.md#akka-http-spray-json)).

One of the strengths of Akka HTTP is that streaming data is at its heart meaning that both request and response bodies
can be streamed through the server achieving constant memory usage even for very large requests or responses. Streaming
responses will be backpressured by the remote client so that the server will not push data faster than the client can
handle, streaming requests means that the server decides how fast the remote client can push the data of the request
body.

Example that streams random numbers as long as the client accepts them:

@@snip [HttpServerExampleSpec.scala](../../../../test/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #stream-random-numbers }

Connecting to this service with a slow HTTP client would backpressure so that the next random number is produced on
demand with constant memory usage on the server. This can be seen using curl and limiting the rate
`curl --limit-rate 50b 127.0.0.1:8080/random`

Akka HTTP routes easily interacts with actors. In this example one route allows for placing bids in a fire-and-forget
style while the second route contains a request-response interaction with an actor. The resulting response is rendered
as json and returned when the response arrives from the actor.

@@snip [HttpServerExampleSpec.scala](../../../../test/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #actor-interaction }

Again the logic for the marshalling and unmarshalling JSON in this example is provided by the "spray-json" library
(details on how to use that here: @ref[JSON Support](common/json-support.md#akka-http-spray-json))

Read more about the details of the high level APIs in the section @ref[High-level Server-Side API](routing-dsl/index.md#http-high-level-server-side-api).

## Low-level HTTP server APIs

The low-level Akka HTTP server APIs allows for handling connections or individual requests by accepting
`HttpRequest` s and answering them by producing `HttpResponse` s. This is provided by the `akka-http-core` module.
APIs for handling such request-responses as function calls and as a `Flow[HttpRequest, HttpResponse, _]` are available.

@@snip [HttpServerExampleSpec.scala](../../../../test/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #low-level-server-example }

Read more details about the low level APIs in the section @ref[Low-Level Server-Side API](low-level-server-side-api.md#http-low-level-server-side-api).

## HTTP client API

The client APIs provide methods for calling a HTTP server using the same `HttpRequest` and `HttpResponse` abstractions
that Akka HTTP server uses but adds the concept of connection pools to allow multiple requests to the same server to be
handled more performantly by re-using TCP connections to the server.

Example simple request:

@@snip [HttpClientExampleSpec.scala](../../../../test/scala/docs/http/scaladsl/HttpClientExampleSpec.scala) { #single-request-example }

Read more about the details of the client APIs in the section @ref[Consuming HTTP-based Services (Client-Side)](client-side/index.md#http-client-side).

## The modules that make up Akka HTTP

Akka HTTP is structured into several modules:

akka-http
: Higher-level functionality, like (un)marshalling, (de)compression as well as a powerful DSL
for defining HTTP-based APIs on the server-side, this is the recommended way to write HTTP servers
with Akka HTTP. Details can be found in the section @ref[High-level Server-Side API](routing-dsl/index.md#http-high-level-server-side-api)

akka-http-core
: A complete, mostly low-level, server- and client-side implementation of HTTP (incl. WebSockets)
Details can be found in sections @ref[Low-Level Server-Side API](low-level-server-side-api.md#http-low-level-server-side-api) and @ref[Consuming HTTP-based Services (Client-Side)](client-side/index.md#http-client-side)

akka-http-testkit
: A test harness and set of utilities for verifying server-side service implementations

akka-http-spray-json
: Predefined glue-code for (de)serializing custom types from/to JSON with [spray-json](https://github.com/spray/spray-json)
Details can be found here: @ref[JSON Support](common/json-support.md#akka-http-spray-json)

akka-http-xml
: Predefined glue-code for (de)serializing custom types from/to XML with [scala-xml](https://github.com/scala/scala-xml)
Details can be found here: @ref[XML Support](common/xml-support.md#akka-http-xml-marshalling)
