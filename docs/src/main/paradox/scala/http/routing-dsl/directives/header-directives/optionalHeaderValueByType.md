# optionalHeaderValueByType

## Signature

```scala
def optionalHeaderValueByType[T <: HttpHeader: ClassTag](): Directive1[Option[T]]
```

The signature shown is simplified, the real signature uses magnets. <a id="^1" href="#1">[1]</a>

> <a id="1" href="#^1">[1]</a> See [The Magnet Pattern](http://spray.io/blog/2012-12-13-the-magnet-pattern/) for an explanation of magnet-based overloading.

## Description

Optionally extracts the value of the HTTP request header of the given type.

The `optionalHeaderValueByType` directive is similar to the @ref[headerValueByType](headerValueByType.md) directive but always extracts
an `Option` value instead of rejecting the request if no matching header could be found.

@@@ note

Custom headers will only be matched by this directive if they extend @unidoc[ModeledCustomHeader]
and provide a companion extending `ModeledCustomHeaderCompanion`, otherwise the routing
infrastructure does now know where to search for the needed companion and header name.

To learn more about defining custom headers, read: @ref[Custom Headers](../../../common/http-model.md#custom-headers).

@@@

## Example

Scala
:  @@snip [HeaderDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala) { #optionalHeaderValueByType-0 }

Java
:  @@snip [HeaderDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/HeaderDirectivesExamplesTest.java) { #optionalHeaderValueByType }
