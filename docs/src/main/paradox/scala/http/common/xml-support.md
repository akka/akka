<a id="akka-http-xml-marshalling"></a>
# XML Support

Akka HTTP's @ref[marshalling](marshalling.md#http-marshalling-scala) and @ref[unmarshalling](unmarshalling.md#http-unmarshalling-scala)
infrastructure makes it rather easy to seamlessly support specific wire representations of your data objects, like JSON,
XML or even binary encodings.

For XML Akka HTTP currently provides support for [Scala XML](https://github.com/scala/scala-xml) right out of the box through it's
`akka-http-xml` module.

## Scala XML Support

The @github[ScalaXmlSupport](/akka-http-marshallers-scala/akka-http-xml/src/main/scala/akka/http/scaladsl/marshallers/xml/ScalaXmlSupport.scala) trait provides a `FromEntityUnmarshaller[NodeSeq]` and `ToEntityMarshaller[NodeSeq]` that
you can use directly or build upon.

In order to enable support for (un)marshalling from and to XML with [Scala XML](https://github.com/scala/scala-xml) `NodeSeq` you must add
the following dependency:

@@@vars
```sbt
"com.typesafe.akka" %% "akka-http-xml" % "$project.version$"
```
@@@

Once you have done this (un)marshalling between XML and `NodeSeq` instances should work nicely and transparently,
by either using `import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._` or mixing in the
`akka.http.scaladsl.marshallers.xml.ScalaXmlSupport` trait.
