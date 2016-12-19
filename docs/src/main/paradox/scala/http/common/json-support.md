<a id="akka-http-spray-json"></a>
# JSON Support

Akka HTTP's @ref[marshalling](marshalling.md#http-marshalling-scala) and @ref[unmarshalling](unmarshalling.md#http-unmarshalling-scala) infrastructure makes it rather easy to seamlessly support specific wire representations of your data objects, like JSON, XML or even binary encodings.

For JSON Akka HTTP currently provides support for [spray-json] right out of the box through its `akka-http-spray-json` module.

Other JSON libraries are supported by the community. See [the list of current community extensions for Akka HTTP](http://akka.io/community/#extensions-to-akka-http).

## spray-json Support

The @github[SprayJsonSupport](/akka-http-marshallers-scala/akka-http-spray-json/src/main/scala/akka/http/scaladsl/marshallers/sprayjson/SprayJsonSupport.scala) trait provides a `FromEntityUnmarshaller[T]` and `ToEntityMarshaller[T]` for every type `T`
that an implicit `spray.json.RootJsonReader` and/or `spray.json.RootJsonWriter` (respectively) is available for.

To enable automatic support for (un)marshalling from and to JSON with [spray-json], add a library dependency onto:

@@@vars
```sbt
"com.typesafe.akka" %% "akka-http-spray-json" % "$project.version$"
```
@@@

Next, provide a `RootJsonFormat[T]` for your type and bring it into scope. Check out the [spray-json] documentation for more info on how to do this.

Finally, import the `FromEntityUnmarshaller[T]` and `ToEntityMarshaller[T]` implicits directly from `SprayJsonSupport` as shown in the example below or mix the `akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport` trait into your JSON support module.

Once you have done this (un)marshalling between JSON and your type `T` should work nicely and transparently.

@@snip [SprayJsonExampleSpec.scala](../../../../../test/scala/docs/http/scaladsl/SprayJsonExampleSpec.scala) { #minimal-spray-json-example }

### Pretty printing

By default, spray-json marshals your types to compact printed JSON by implicit conversion using `CompactPrinter`, as defined in:

@@snip [SprayJsonSupport.scala](../../../../../../../akka-http-marshallers-scala/akka-http-spray-json/src/main/scala/akka/http/scaladsl/marshallers/sprayjson/SprayJsonSupport.scala) { #sprayJsonMarshallerConverter }

Alternatively to marshal your types to pretty printed JSON, bring a `PrettyPrinter` in scope to perform implicit conversion.

@@snip [SprayJsonPrettyMarshalSpec.scala](../../../../../test/scala/docs/http/scaladsl/SprayJsonPrettyMarshalSpec.scala) { #example }

To learn more about how spray-json works please refer to its [documentation][spray-json].

[spray-json]: https://github.com/spray/spray-json
