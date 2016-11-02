<a id="json-support-java"></a>
# Json Support

akka-http provides support to convert application-domain objects from and to JSON using [jackson](https://github.com/FasterXML/jackson) in an
extra artifact.

Integration with other JSON libraries may be supported by the community.
See [the list of current community extensions for Akka HTTP](http://akka.io/community/#extensions-to-akka-http).

<a id="json-jackson-support-java"></a>
## Json Support via Jackson

To make use of the support module, you need to add a dependency on *akka-http-jackson*.

Use `akka.http.javadsl.marshallers.jackson.Jackson.unmarshaller(T.class)` to create an `Unmarshaller<HttpEntity,T>` which expects the request
body (HttpEntity) to be of type `application/json` and converts it to `T` using Jackson.

See @github[this example](/akka-http-tests/src/main/java/akka/http/javadsl/server/examples/petstore/PetStoreExample.java) in the sources for an example.

Use `akka.http.javadsl.marshallers.jackson.Jackson.marshaller(T.class)` to create a `Marshaller<T,RequestEntity>` which can be used with
`RequestContext.complete` or `RouteDirectives.complete` to convert a POJO to an HttpResponse.
