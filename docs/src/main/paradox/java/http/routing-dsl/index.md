<a id="http-high-level-server-side-api-java"></a>
# High-level Server-Side API

In addition to the @ref[Low-Level Server-Side API](../server-side/low-level-server-side-api.md#http-low-level-server-side-api-java) Akka HTTP provides a very flexible "Routing DSL" for elegantly
defining RESTful web services. It picks up where the low-level API leaves off and offers much of the higher-level
functionality of typical web servers or frameworks, like deconstruction of URIs, content negotiation or
static content serving.

To use the high-level API you need to add a dependency to the `akka-http` module.

@@toc { depth=1 }

@@@ index

* [overview](overview.md)
* [routes](routes.md)
* [directives/index](directives/index.md)
* [marshalling](marshalling.md)
* [exception-handling](exception-handling.md)
* [source-streaming-support](source-streaming-support.md)
* [rejections](rejections.md)
* [testkit](testkit.md)

@@@

## Handling HTTP Server failures in the High-Level API

There are various situations when failure may occur while initialising or running an Akka HTTP server.
Akka by default will log all these failures, however sometimes one may want to react to failures in addition
to them just being logged, for example by shutting down the actor system, or notifying some external monitoring
end-point explicitly.

### Bind failures

For example the server might be unable to bind to the given port. For example when the port
is already taken by another application, or if the port is privileged (i.e. only usable by `root`).
In this case the "binding future" will fail immediately, and we can react to it by listening on the CompletionStage's completion:

@@snip [HighLevelServerBindFailureExample.java](../../../../../test/java/docs/http/javadsl/server/HighLevelServerBindFailureExample.java) { #binding-failure-high-level-example }

@@@ note
For a more low-level overview of the kinds of failures that can happen and also more fine-grained control over them
refer to the @ref[Handling HTTP Server failures in the Low-Level API](../server-side/low-level-server-side-api.md#handling-http-server-failures-low-level-java) documentation.
@@@

### Failures and exceptions inside the Routing DSL

Exception handling within the Routing DSL is done by providing `ExceptionHandler` s which are documented in-depth
in the @ref[Exception Handling](exception-handling.md#exception-handling-java) section of the documentation. You can use them to transform exceptions into
`HttpResponse` s with appropriate error codes and human-readable failure descriptions.

### File uploads

For high level directives to handle uploads see the @ref[FileUploadDirectives](directives/file-upload-directives/index.md#fileuploaddirectives-java).

Handling a simple file upload from for example a browser form with a *file* input can be done
by accepting a *Multipart.FormData* entity, note that the body parts are *Source* rather than
all available right away, and so is the individual body part payload so you will need to consume
those streams both for the file and for the form fields.

Here is a simple example which just dumps the uploaded file into a temporary file on disk, collects
some form fields and saves an entry to a fictive database:

@@snip [FileUploadExamplesTest.java](../../../../../test/java/docs/http/javadsl/server/FileUploadExamplesTest.java) { #simple-upload }

You can transform the uploaded files as they arrive rather than storing then in a temporary file as
in the previous example. In this example we accept any number of `.csv` files, parse those into lines
and split each line before we send it to an actor for further processing:

@@snip [FileUploadExamplesTest.java](../../../../../test/java/docs/http/javadsl/server/FileUploadExamplesTest.java) { #stream-csv-upload }
