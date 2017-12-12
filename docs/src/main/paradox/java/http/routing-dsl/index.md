# High-level Server-Side API

In addition to the @ref[Low-Level Server-Side API](../server-side/low-level-api.md) Akka HTTP provides a very flexible "Routing DSL" for elegantly
defining RESTful web services. It picks up where the low-level API leaves off and offers much of the higher-level
functionality of typical web servers or frameworks, like deconstruction of URIs, content negotiation or
static content serving.

To use the high-level API you need to add a dependency to the `akka-http` module.

@@toc { depth=1 }

@@@ index

* [overview](overview.md)
* [routes](routes.md)
* [directives/index](directives/index.md)
* [exception-handling](exception-handling.md)
* [source-streaming-support](source-streaming-support.md)
* [rejections](rejections.md)
* [testkit](testkit.md)
* [http-app](HttpApp.md)

@@@

## Minimal Example

This is a complete, very basic Akka HTTP application relying on the Routing DSL:

@@snip [HttpServerMinimalExampleTest.java]($test$/java/docs/http/javadsl/HttpServerMinimalExampleTest.java) { #minimal-routing-example }

It starts an HTTP Server on localhost and replies to GET requests to `/hello` with a simple response.

@@@ warning { title="API may change" }
The following example uses an experimental feature and its API is subjected to change in future releases of Akka HTTP.
For further information about this marker, see @extref:[The @DoNotInherit and @ApiMayChange markers](akka-docs:common/binary-compatibility-rules.html#The_@DoNotInherit_and_@ApiMayChange_markers)
in the Akka documentation.
@@@

To help start a server Akka HTTP provides an experimental helper class called @unidoc[HttpApp].
This is the same example as before rewritten using @unidoc[HttpApp]:

@@snip [HttpAppExampleTest.java]($test$/java/docs/http/javadsl/server/HttpAppExampleTest.java) { #minimal-routing-example }

See @ref[HttpApp Bootstrap](HttpApp.md) for more details about setting up a server using this approach.

## Handling HTTP Server failures in the High-Level API

There are various situations when failure may occur while initialising or running an Akka HTTP server.
Akka by default will log all these failures, however sometimes one may want to react to failures in addition
to them just being logged, for example by shutting down the actor system, or notifying some external monitoring
end-point explicitly.

### Bind failures

For example the server might be unable to bind to the given port. For example when the port
is already taken by another application, or if the port is privileged (i.e. only usable by `root`).
In this case the "binding future" will fail immediately, and we can react to it by listening on the CompletionStage's completion:

@@snip [HighLevelServerBindFailureExample.java]($test$/java/docs/http/javadsl/server/HighLevelServerBindFailureExample.java) { #binding-failure-high-level-example }

@@@ note
For a more low-level overview of the kinds of failures that can happen and also more fine-grained control over them
refer to the @ref[Handling HTTP Server failures in the Low-Level API](../server-side/low-level-api.md#handling-http-server-failures-low-level) documentation.
@@@

### Failures and exceptions inside the Routing DSL

Exception handling within the Routing DSL is done by providing @unidoc[ExceptionHandler] s which are documented in-depth
in the @ref[Exception Handling](exception-handling.md) section of the documentation. You can use them to transform exceptions into
@unidoc[HttpResponse] s with appropriate error codes and human-readable failure descriptions.

### File uploads

For high level directives to handle uploads see the @ref[FileUploadDirectives](directives/file-upload-directives/index.md).

Handling a simple file upload from for example a browser form with a *file* input can be done
by accepting a *Multipart.FormData* entity, note that the body parts are *Source* rather than
all available right away, and so is the individual body part payload so you will need to consume
those streams both for the file and for the form fields.

Here is a simple example which just dumps the uploaded file into a temporary file on disk, collects
some form fields and saves an entry to a fictive database:

@@snip [FileUploadExamplesTest.java]($test$/java/docs/http/javadsl/server/FileUploadExamplesTest.java) { #simple-upload }

You can transform the uploaded files as they arrive rather than storing then in a temporary file as
in the previous example. In this example we accept any number of `.csv` files, parse those into lines
and split each line before we send it to an actor for further processing:

@@snip [FileUploadExamplesTest.java]($test$/java/docs/http/javadsl/server/FileUploadExamplesTest.java) { #stream-csv-upload }
