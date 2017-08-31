# XML Support

Akka HTTP's @ref[marshalling](marshalling.md) and @ref[unmarshalling](unmarshalling.md)
infrastructure makes it rather easy to seamlessly support specific wire representations of your data objects, like JSON,
XML or even binary encodings.

@@@ div { .group-java }

Akka HTTP does not currently provide a Java API for XML support. If you need to
produce and consume XML, you can write a @ref[custom marshaller](marshalling.md#custom-marshallers)
using [Jackson], which is also the library used for providing @ref[JSON support](json-support.md#json-jackson-support-java).

@@ snip [#jackson-xml-support] (../../../../../test/java/docs/http/javadsl/JacksonXmlSupport.java) { #jackson-xml-support }

The custom XML (un)marshalling code shown above requires that you depend on the `jackson-dataformat-xml` library.

sbt
:   @@@vars
    ```
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-xml" % "$jackson.version$"
    ```
    @@@

Gradle
:   @@@vars
    ```
    compile group: 'com.fasterxml.jackson.dataformat', name: 'jackson-dataformat-xml', version: '$jackson.version$'
    ```
    @@@

Maven
:   @@@vars
    ```xml
    <dependency>
      <groupId>com.fasterxml.jackson.dataformat</groupId>
      <artifactId>jackson-dataformat-xml</artifactId>
      <version>$jackson.version$</version>
    </dependency>
    ```
    @@@

@@@

@@@ div { .group-scala }

For XML Akka HTTP currently provides support for [Scala XML][scala-xml] right out of the box through it's
`akka-http-xml` module.

## Scala XML Support

The @scaladoc[ScalaXmlSupport](akka.http.scaladsl.marshallers.xml.ScalaXmlSupport) trait provides a `FromEntityUnmarshaller[NodeSeq]` and `ToEntityMarshaller[NodeSeq]` that
you can use directly or build upon.

In order to enable support for (un)marshalling from and to XML with [Scala XML][scala-xml] `NodeSeq` you must add
the following dependency:

sbt
:   @@@vars
    ```
    "com.typesafe.akka" %% "akka-http-xml" % "$project.version$" $crossString$
    ```
    @@@

Gradle
:   @@@vars
    ```
    compile group: 'com.typesafe.akka', name: 'akka-http-xml_$scala.binary_version$', version: '$project.version$'
    ```
    @@@

Maven
:   @@@vars
    ```
    <dependency>
      <groupId>com.typesafe.akka</groupId>
      <artifactId>akka-http-xml_$scala.binary_version$</artifactId>
      <version>$project.version$</version>
    </dependency>
    ```
    @@@

Once you have done this (un)marshalling between XML and `NodeSeq` instances should work nicely and transparently,
by either using `import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._` or mixing in the
`akka.http.scaladsl.marshallers.xml.ScalaXmlSupport` trait.

@@@

 [scala-xml]: https://github.com/scala/scala-xml
 [jackson]: https://github.com/FasterXML/jackson
