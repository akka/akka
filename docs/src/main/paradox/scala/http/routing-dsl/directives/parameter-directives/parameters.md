# parameters

## Signature

```scala
def parameters(param: <ParamDef[T]>): Directive1[T]
def parameters(params: <ParamDef[T_i]>*): Directive[T_0 :: ... T_i ... :: HNil]
def parameters(params: <ParamDef[T_0]> :: ... <ParamDef[T_i]> ... :: HNil): Directive[T_0 :: ... T_i ... :: HNil]
```

The signature shown is simplified and written in pseudo-syntax, the real signature uses magnets. <a id="^1" href="#1">[1]</a> The type
`<ParamDef>` doesn't really exist but consists of the syntactic variants as shown in the description and the examples.

> <a id="1" href="#^1">[1]</a> See [The Magnet Pattern](http://spray.io/blog/2012-12-13-the-magnet-pattern/) for an explanation of magnet-based overloading.

## Description

The parameters directive filters on the existence of several query parameters and extract their values.

Query parameters can be either extracted as a String or can be converted to another type. The parameter name
can be supplied either as a String or as a Symbol. Parameter extraction can be modified to mark a query parameter
as required, optional, or repeated, or to filter requests where a parameter has a certain value:

`"color"`
: extract value of parameter "color" as `String`

`"color".?`
: extract optional value of parameter "color" as `Option[String]`

`"color" ? "red"`
: extract optional value of parameter "color" as `String` with default value `"red"`

`"color" ! "blue"`
: require value of parameter "color" to be `"blue"` and extract nothing

`"amount".as[Int]`
: extract value of parameter "amount" as `Int`, you need a matching @unidoc[Unmarshaller] in scope for that to work
(see also @ref[Unmarshalling](../../../common/unmarshalling.md))

`"amount".as(deserializer)`
: extract value of parameter "amount" with an explicit @unidoc[Unmarshaller]
`"distance".*`
: extract multiple occurrences of parameter "distance" as `Iterable[String]`

`"distance".as[Int].*`
: extract multiple occurrences of parameter "distance" as `Iterable[Int]`, you need a matching @unidoc[Unmarshaller] in scope for that to work
(see also @ref[Unmarshalling](../../../common/unmarshalling.md))

`"distance".as(deserializer).*`
: extract multiple occurrences of parameter "distance" with an explicit @unidoc[Unmarshaller]

You can use @ref[Case Class Extraction](../../case-class-extraction.md) to group several extracted values together into a case-class
instance.

Requests missing a required parameter or parameter value will be rejected with an appropriate rejection. 

If an unmarshaller throws an exception while extracting the value of a parameter, the request will be rejected with a `MissingQueryParameterRejection` 
if the unmarshaller threw an `Unmarshaller.NoContentException` or a @unidoc[MalformedQueryParamRejection] in all other cases.
(see also @ref[Rejections](../../../routing-dsl/rejections.md))

There's also a singular version, @ref[parameter](parameter.md). Form fields can be handled in a similar way, see `formFields`. If
you want unified handling for both query parameters and form fields, see `anyParams`.

## Examples

### Required parameter

@@snip [ParameterDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala) { #required-1 }

### Optional parameter

@@snip [ParameterDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala) { #optional }

### Optional parameter with default value

@@snip [ParameterDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala) { #optional-with-default }

### Parameter with required value

@@snip [ParameterDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala) { #required-value }

### Deserialized parameter

@@snip [ParameterDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala) { #mapped-value }

### Repeated parameter

@@snip [ParameterDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala) { #repeated }

### CSV parameter

@@snip [ParameterDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala) { #csv }

### Repeated, deserialized parameter

Scala
:  @@snip [ParameterDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala) { #mapped-repeated }

Java
:  @@snip [ParameterDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/ParameterDirectivesExamplesTest.java) { #parameters }
