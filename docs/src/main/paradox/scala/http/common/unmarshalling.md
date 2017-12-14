# Unmarshalling

"Unmarshalling" is the process of converting some kind of a lower-level representation, often a "wire format", into a
higher-level (object) structure. Other popular names for it are "Deserialization" or "Unpickling".

In Akka HTTP "Unmarshalling" means the conversion of a lower-level source object, e.g. a `MessageEntity`
(which forms the "entity body" of an HTTP request or response) or a full @unidoc[HttpRequest] or @unidoc[HttpResponse],
into an instance of type `T`.

## Basic Design

Unmarshalling of instances of type `A` into instances of type `B` is performed by an @scala[@unidoc[Unmarshaller[A, B]`]@java[@unidoc[Unmarshaller[A, B]]].

@@@ div { .group-scala }
Akka HTTP also predefines a number of helpful aliases for the types of unmarshallers that you'll likely work with most:

@@snip [package.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/unmarshalling/package.scala) { #unmarshaller-aliases }

@@@

At its core an @scala[`Unmarshaller[A, B]]]@java[@unidoc[Unmarshaller[A, B]]] is very similar to a @scala[function `A => Future[B]`]@java[`Function<A, CompletionStage<B>>`] and as such quite a bit simpler
than its @ref[marshalling](marshalling.md) counterpart. The process of unmarshalling does not have to support
content negotiation which saves two additional layers of indirection that are required on the marshalling side.

## Predefined Unmarshallers

Akka HTTP already predefines a number of unmarshallers for the most common types.
Specifically these are:

 * @scala[@scaladoc[PredefinedFromStringUnmarshallers](akka.http.scaladsl.unmarshalling.PredefinedFromStringUnmarshallers)]
   @java[@javadoc[StringUnmarshallers](akka.http.javadsl.unmarshalling.StringUnmarshallers)]
    * `Byte`
    * `Short`
    * @scala[`Int`]@java[`Integer`]
    * `Long`
    * `Float`
    * `Double`
    * `Boolean`
 * @scala[@scaladoc[PredefinedFromEntityUnmarshallers](akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers)]
   @java[@unidoc[Unmarshaller]]
    * @scala[`Array[Byte]`]@java[`byte[]`]
    * @unidoc[ByteString]
    * @scala[`Array[Char]`]@java[`char[]`]
    * `String`
    * @scala[`akka.http.scaladsl.model.FormData`]@java[`akka.http.javadsl.model.FormData`]

@@@ div { .group-scala }
 * @scaladoc[GenericUnmarshallers](akka.http.scaladsl.unmarshalling.GenericUnmarshallers)
    * @unidoc[Unmarshaller[T, T]` (identity unmarshaller)
    * `Unmarshaller[Option[A], B]`, if an `Unmarshaller[A, B]` is available
    * `Unmarshaller[A, Option[B]]`, if an `Unmarshaller[A, B]] is available
@@@

Additional unmarshallers are available in separate modules for specific content types, such as
@ref[JSON](json-support.md)@scala[ and @ref[XML](xml-support.md)].

@@@ div { .group-scala }

## Implicit Resolution

The unmarshalling infrastructure of Akka HTTP relies on a type-class based approach, which means that @unidoc[Unmarshaller]
instances from a certain type `A` to a certain type `B` have to be available implicitly.

The implicits for most of the predefined unmarshallers in Akka HTTP are provided through the companion object of the
@unidoc[Unmarshaller] trait. This means that they are always available and never need to be explicitly imported.
Additionally, you can simply "override" them by bringing your own custom version into local scope.

@@@

## Custom Unmarshallers

Akka HTTP gives you a few convenience tools for constructing unmarshallers for your own types.
Usually you won't have to "manually" implement the @unidoc[Unmarshaller] @scala[trait]@java[class] directly.
Rather, it should be possible to use one of the convenience construction helpers defined on
@scala[the @unidoc[Unmarshaller] companion]@java[@unidoc[Unmarshaller]]:

Scala
:  @@snip [Unmarshaller.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/unmarshalling/Unmarshaller.scala) { #unmarshaller-creation }

Java
:  @@snip [Unmarshallers.scala]($akka-http$/akka-http/src/main/java/akka/http/javadsl/unmarshalling/Unmarshallers.java) { #unmarshaller-creation }

## Deriving Unmarshallers

Sometimes you can save yourself some work by reusing existing unmarshallers for your custom ones.
The idea is to "wrap" an existing unmarshaller with some logic to "re-target" it to your type.

Usually what you want to do is to transform the output of some existing unmarshaller and convert it to your type.
For this type of unmarshaller transformation Akka HTTP defines these methods:

@@@ div { .group-scala }
 * `baseUnmarshaller.transform`
 * `baseUnmarshaller.map`
 * `baseUnmarshaller.mapWithInput`
 * `baseUnmarshaller.flatMap`
 * `baseUnmarshaller.flatMapWithInput`
 * `baseUnmarshaller.recover`
 * `baseUnmarshaller.withDefaultValue`
 * `baseUnmarshaller.mapWithCharset` (only available for FromEntityUnmarshallers)
 * `baseUnmarshaller.forContentTypes` (only available for FromEntityUnmarshallers)
@@@

@@@ div { .group-java }
 * `baseMarshaller.thenApply`
 * `baseMarshaller.flatMap`
 * `Unmarshaller.forMediaType` (to derive from a @unidoc[HttpEntity] unmarshaller)
 * `Unmarshaller.forMediaTypes` (to derive from a @unidoc[HttpEntity] unmarshaller)
@@@

The method signatures should make their semantics relatively clear.

## Using Unmarshallers

In many places throughout Akka HTTP unmarshallers are used implicitly, e.g. when you want to access the @ref[entity](../routing-dsl/directives/marshalling-directives/entity.md)
of a request using the @ref[Routing DSL](../routing-dsl/index.md).

However, you can also use the unmarshalling infrastructure directly if you wish, which can be useful for example in tests.
The best entry point for this is the @scala[`akka.http.scaladsl.unmarshalling.Unmarshal` object]@java[`akka.http.javadsl.unmarshalling.StringUnmarshallers` class], which you can use like this:

Scala
:  @@snip [UnmarshalSpec.scala]($test$/scala/docs/http/scaladsl/UnmarshalSpec.scala) { #use-unmarshal }

Java
:  @@snip [UnmarshalTest.scala]($test$/java/docs/http/javadsl/UnmarshalTest.java) { #imports #use-unmarshal }
