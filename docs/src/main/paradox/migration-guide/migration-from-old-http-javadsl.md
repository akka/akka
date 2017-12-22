# Migration Guide from "old" HTTP JavaDSL

The so-called "old" JavaDSL for Akka HTTP was initially developed during the project's experimental phase,
and thanks to multiple user comments and contributions we were able to come up with a more Java 8 "feel",
which at the same time is also closer to the existing ScalaDSL.

The previous DSL has been entirely removed and replaced with the the so-called "new" one.
Upgrading to the new DSL is **highly encouraged** since the old one not only was rather hard to work with,
it actually was not possible to express many typical use-cases using it.

The most major changes include:

## HttpApp is gone

@unidoc[HttpApp] (a helper class containing a `main()` implementation) is gone, as we would like to encourage understanding
how the various elements of the API fit together.

Instead developers should start applications "manually", by converting a @unidoc[Route] to a @unidoc[Flow[HttpRequest, HttpResponse, ?]]
using the `Route.flow` method. For examples of full apps refer to @ref[Route Testkit](../routing-dsl/testkit.md).

## `RequestVal` is gone

The old API heavily relied on the concept of "request values" which could be used to extract a value from a request context.

Based on community feedback and our own experience we found them too hard to work with in more complex settings.
The concept of a request value has been completely removed, and replaced with proper "directives", exactly like in the ScalaDSL.

**Previously**:

```java
RequestVal<Host> host = Headers.byClass(Host.class).instance();

final Route route =
  route(
    handleWith1(host, (ctx, h) ->
      ctx.complete(String.format("Host header was: %s", h.host()))
    )
  );
```

**Now**:

```java
final Route route =
  headerValueByType(Host.class, host -> complete("Host was: " + host));
```

## All of ScalaDSL routing has corresponding JavaDSL

Both @unidoc[Route], @unidoc[RouteResult] and other important core concepts such as `Rejections` are now modeled 1:1 with Scala,
making is much simpler to understand one API based on the other one – tremendously useful when learning about some nice
pattern from blogs which used Scala, yet need to apply it in Java and the other way around.

It is now possible to implement marshallers using Java. Refer to @ref[Marshalling](../common/marshalling.md) & @ref[Unmarshalling](../common/unmarshalling.md) for details.

## Some complete* overloads changed to completeOK*

In JavaDSL when complete is called with only an entity, the `OK` response code is *assumed*,
to make this more explicit these methods contain the word `OK` in them.

This has been made more consistent than previously, across all overloads and Future-versions of these APIs.

## Migration help

As always, feel free to reach out via the [akka-user](https://groups.google.com/forum/#!searchin/akka-user/) mailing list or gitter channels,
to seek help or guidance when migrating from the old APIs. 

For Lightbend subscription owners it is possible to reach out to the core team for help in the migration by asking specific 
questions via the [Lightbend customer portal](https://portal.lightbend.com/).
