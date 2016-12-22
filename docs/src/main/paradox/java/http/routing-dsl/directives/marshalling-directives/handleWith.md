<a id="handlewith-java"></a>
# handleWith

## Description

Completes the request using the given function. The input to the function is produced with
the in-scope entity unmarshaller and the result value of the function is marshalled with
the in-scope marshaller.  `handleWith` can be a convenient method combining `entity` with
`complete`.

The `handleWith` directive is used when you want to handle a route with a given function of
type A -> B.  `handleWith` will use both an in-scope unmarshaller to convert a request into 
type A and an in-scope marshaller to convert type B into a response. This is helpful when your 
core business logic resides in some other class or you want your business logic to be independent
of the REST interface written with akka-http. You can use `handleWith` to "hand off" processing
to a given function without requiring any akka-http-specific functionality.

`handleWith` is similar to `produce`.  The main difference is `handleWith` automatically
calls `complete` when the function passed to `handleWith` returns. Using `produce` you
must explicity call the completion function passed from the `produce` function.

See @ref[marshalling](../../../common/marshalling.md#http-marshalling-java) and @ref[unmarshalling](../../../common/unmarshalling.md#http-unmarshalling-java) for guidance
on marshalling entities with akka-http.

## Examples

The following example uses an `updatePerson` function with a `Person` class as an input and output. We plug this function into our route using `handleWith`.

@@snip [MarshallingDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/MarshallingDirectivesExamplesTest.java) { #person }

@@snip [MarshallingDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/MarshallingDirectivesExamplesTest.java) { #example-handleWith-with-json }

The following example uses also @ref[Json Support via Jackson](../../../common/json-support.md#json-jackson-support-java) to handle both marshalling and unmarshalling of the Person class.

@@snip [MarshallingDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/MarshallingDirectivesExamplesTest.java) { #person }