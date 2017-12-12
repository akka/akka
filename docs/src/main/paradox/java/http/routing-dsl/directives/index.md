# Directives

A "Directive" is a small building block used for creating arbitrarily complex @ref[route structures](../routes.md).
Akka HTTP already pre-defines a large number of directives and you can easily construct your own:

@@toc { depth=1 }

@@@ index

* [alphabetically](alphabetically.md)
* [by-trait](by-trait.md)
* [custom-directives](custom-directives.md)

@@@

## Basics

@ref[Routes](../routes.md) effectively are simply highly specialised functions that take a @unidoc[RequestContext] and eventually `complete` it, 
which could (and often should) happen asynchronously.

With the @ref[complete](route-directives/complete.md) directive this becomes even shorter:

```java
Route route = complete("yeah");
```

Writing multiple routes that are tried as alternatives (in-order of definition), is as simple as using the `route(route1, route2)`,
method:

```java
Route routes = route(
  pathSingleSlash(() ->
    getFromResource("web/calculator.html")
  ),
  path("hello", () -> complete("World!))
);
```

You could also simply define a "catch all" completion by providing it as the last route to attempt to match.
In the example below we use the `get()` (one of the @ref[MethodDirectives](method-directives/index.md)) to match all incoming `GET`
requests for that route, and all other requests will be routed towards the other "catch all" route, that completes the route:

```java
Route route =
  get(
    () -> complete("Received GET")
  ).orElse(
    () -> complete("Received something else")
  )
```

If no route matches a given request, a default `404 Not Found` response will be returned as response.

## Structure

The general anatomy of a directive is as follows:

```java
directiveName(arguments [, ...], (extractions [, ...]) -> {
  ... // inner route
})
```

It has a name, zero or more arguments and optionally an inner route (The @ref[RouteDirectives](route-directives/index.md) are special in that they
are always used at the leaf-level and as such cannot have inner routes).

Additionally directives can "extract" a number of values and make them available to their inner routes as function
arguments. When seen "from the outside" a directive with its inner route form an expression of type @unidoc[Route].

## What Directives do

A directive can do one or more of the following:

 * Transform the incoming @unidoc[RequestContext] before passing it on to its inner route (i.e. modify the request)
 * Filter the @unidoc[RequestContext] according to some logic, i.e. only pass on certain requests and reject others
 * Extract values from the @unidoc[RequestContext] and make them available to its inner route as "extractions"
 * Chain some logic into the @unidoc[RouteResult] future transformation chain (i.e. modify the response or rejection)
 * Complete the request

This means a `Directive` completely wraps the functionality of its inner route and can apply arbitrarily complex
transformations, both (or either) on the request and on the response side.

## Composing Directives


As you have seen from the examples presented so far the "normal" way of composing directives is nesting.
Let's take a look at this concrete example:

@@snip [DirectiveExamplesTest.java]($test$/java/docs/http/javadsl/server/DirectiveExamplesTest.java) { #example1 }

Here the `get` and `put` routes are chained together with using the `orElse` method to form a higher-level route that
serves as the inner route of the `path` directive. Let's rewrite it in the following way:

@@snip [DirectiveExamplesTest.java]($test$/java/docs/http/javadsl/server/DirectiveExamplesTest.java) { #getOrPut }

In this previous example, we combined the `get` and `put` directives into one composed directive and extracted it to its own method, which could be reused anywhere else in our code.

Instead of extracting the composed directives to its own method, we can also use the available `anyOf` combinator. The following code is equivalent to the previous one:

@@snip [DirectiveExamplesTest.java]($test$/java/docs/http/javadsl/server/DirectiveExamplesTest.java) { #getOrPutUsingAnyOf }

The previous example, tries to complete the route first with a `GET` or with a `PUT` if the first one was rejected. 

In case you are constantly nesting the same directives several times in you code, you could factor them out in their own method and use it everywhere:

@@snip [DirectiveExamplesTest.java]($test$/java/docs/http/javadsl/server/DirectiveExamplesTest.java) { #composeNesting }

Here we simple created our own combined directive that accepts `GET` requests, then extracts the method and completes it with an inner route that takes this HTTP method as a parameter.

Again, instead of extracting own combined directives to its own method, we can make use of the `allOf` combinator. The following code is equivalent to the previous one:

@@snip [DirectiveExamplesTest.java]($test$/java/docs/http/javadsl/server/DirectiveExamplesTest.java) { #composeNestingAllOf }

In this previous example, the the inner route function provided to `allOf` will be called when the request is a `GET` and with the extracted client IP obtained from the second directive.

As you have already seen in the previous section, you can also use the `route` method defined in @unidoc[RouteDirectives] as an alternative to `orElse` chaining. Here you can see the first example again, rewritten using `route`:

@@snip [DirectiveExamplesTest.java]($test$/java/docs/http/javadsl/server/DirectiveExamplesTest.java) { #usingRoute }

The `route` combinator comes handy when you want to avoid nesting. Here you can see an illustrative example:
 
@@snip [DirectiveExamplesTest.java]($test$/java/docs/http/javadsl/server/DirectiveExamplesTest.java) { #usingRouteBig }

Notice how you could adjust the indentation in these last two examples to have a more readable code.

Note that going too far with "compressing" several directives into a single one probably doesn't result in the most
readable and therefore maintainable routing code. It might even be that the very first of this series of examples, or the analogous one using `route`,
is in fact the most readable one.

Still, the purpose of the exercise presented here is to show you how flexible directives can be and how you can
use their power to define your web service behavior at the level of abstraction that is right for **your** application.

## Type Safety of Directives

When you combine directives with `anyOf` and `allOf` methods the routing DSL makes sure that all extractions work as
expected and logical constraints are enforced at compile-time.

For example you cannot call `anyOf`  with a directive producing an extraction with one that doesn't:

```java
anyOf(this::get, this::extractClientIP, routeProvider) // doesn't compile
```

Also the number of extractions and their types have to match up:

```java
anyOf(this::extractClientIP, this::extractMethod, routeProvider) // doesn't compile
anyOf(bindParameter(this::parameter, "foo"), bindParameter(this::parameter, "bar"), routeProvider) // ok
```
In this previous example we make use of the `bindParameter` function located in `akka-http/akka.http.javadsl.common.PartialApplication`.
In order to be able to call `anyOf`, we need to convert our directive that takes 2 parameters to a function that takes only 1.
In this particular case we want to use the `parameter` directive that takes a `String` and a function from `String` to @unidoc[Route],
so to be able to use it in combination with `anyOf`, we need to bind the first parameter to `foo` and to `bar` in the second one. `bindParameter(this::parameter, "foo")` is equivalent 
to define your own function like this:
```java
Route parameterFoo(Function<String, Route> inner) {
  return parameter("foo", inner);
}
```

When you combine directives producing extractions with the `allOf` method all extractions will be properly gathered up:

```java
allOf(this::extractScheme, this::extractMethod, (scheme, method) -> ...) 
```

Directives offer a great way of constructing your web service logic from small building blocks in a plug and play
fashion while maintaining DRYness and full type-safety. If the large range of @ref[Predefined Directives](alphabetically.md) does not
fully satisfy your needs you can also easily create @ref[Custom Directives](custom-directives.md).
