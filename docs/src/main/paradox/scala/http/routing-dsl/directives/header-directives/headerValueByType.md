# headerValueByType

## Signature

```scala
def headerValueByType[T <: HttpHeader: ClassTag](): Directive1[T]
```

The signature shown is simplified, the real signature uses magnets. <a id="^1" href="#1">[1]</a>

> <a id="1" href="#^1">[1]</a> See [The Magnet Pattern](http://spray.io/blog/2012-12-13-the-magnet-pattern/) for an explanation of magnet-based overloading.

## Description

Traverses the list of request headers and extracts the first header of the given type.

The `headerValueByType` directive finds a header of the given type in the list of request header. If no header of
the given type is found the request is rejected with a @unidoc[MissingHeaderRejection].

If the header is expected to be missing in some cases or to customize handling when the header
is missing use the @ref[optionalHeaderValueByType](optionalHeaderValueByType.md) directive instead.

@@@ note
Custom headers will only be matched by this directive if they extend @unidoc[ModeledCustomHeader]
and provide a companion extending `ModeledCustomHeaderCompanion`, otherwise the routing
infrastructure does now know where to search for the needed companion and header name.

To learn more about defining custom headers, read: @ref[Custom Headers](../../../common/http-model.md#custom-headers).
@@@

## Example

Scala
:  @@snip [HeaderDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala) { #headerValueByType-0 }

Java
:  @@snip [HeaderDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/HeaderDirectivesExamplesTest.java) { #headerValueByType }
