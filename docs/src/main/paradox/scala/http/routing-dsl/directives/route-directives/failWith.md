# failWith

@@@ div { .group-scala }

## Signature

@@signature [RouteDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/RouteDirectives.scala) { #failWith }

@@@

## Description

Bubbles up the given error through the route structure where it is dealt with by the closest `handleExceptions`
directive and its @unidoc[ExceptionHandler].

`failWith` explicitly raises an exception that gets bubbled up through the route structure to be picked up by the
nearest `handleExceptions` directive. Using `failWith` rather than simply throwing an exception enables the route
structure's @ref[Exception Handling](../../exception-handling.md) mechanism to deal with the exception even if the current route is executed
asynchronously on another thread (e.g. in a `Future` or separate actor).

If no `handleExceptions` is present above the respective location in the
route structure the top-level routing logic will handle the exception and translate it into a corresponding
@unidoc[HttpResponse] using the in-scope @unidoc[ExceptionHandler] (see also the @ref[Exception Handling](../../exception-handling.md) chapter).

There is one notable special case: If the given exception is a @unidoc[RejectionError] exception it is *not* bubbled up,
but rather the wrapped exception is unpacked and "executed". This allows the "tunneling" of a rejection via an
exception.

## Example

Scala
:  @@snip [RouteDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/RouteDirectivesExamplesSpec.scala) { #failwith-examples }

Java
:  @@snip [RouteDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/RouteDirectivesExamplesTest.java) { #failWith }
