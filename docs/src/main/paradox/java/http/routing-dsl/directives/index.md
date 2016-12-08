<a id="directives-java"></a>
# Directives

A "Directive" is a small building block used for creating arbitrarily complex @ref[route structures](../routes.md#routes).
Akka HTTP already pre-defines a large number of directives and you can easily construct your own:

@@toc { depth=1 }

@@@ index

* [alphabetically](alphabetically.md)
* [by-trait](by-trait.md)
* [custom-directives](custom-directives.md)

@@@

## Basics

@ref[Routes](../routes.md#routes-java) effectively are simply highly specialised functions that take a `RequestContext` and eventually `complete` it, 
which could (and often should) happen asynchronously.

With the @ref[complete](route-directives/complete.md#complete-java) directive this becomes even shorter:

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
In the example below we use the `get()` (one of the @ref[MethodDirectives](method-directives/index.md#methoddirectives-java)) to match all incoming `GET`
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

It has a name, zero or more arguments and optionally an inner route (The @ref[RouteDirectives](route-directives/index.md#routedirectives-java) are special in that they
are always used at the leaf-level and as such cannot have inner routes).

Additionally directives can "extract" a number of values and make them available to their inner routes as function
arguments. When seen "from the outside" a directive with its inner route form an expression of type `Route`.

## What Directives do

A directive can do one or more of the following:

 * Transform the incoming `RequestContext` before passing it on to its inner route (i.e. modify the request)
 * Filter the `RequestContext` according to some logic, i.e. only pass on certain requests and reject others
 * Extract values from the `RequestContext` and make them available to its inner route as "extractions"
 * Chain some logic into the `RouteResult` future transformation chain (i.e. modify the response or rejection)
 * Complete the request

This means a `Directive` completely wraps the functionality of its inner route and can apply arbitrarily complex
transformations, both (or either) on the request and on the response side.

## Composing Directives


As you have seen from the examples presented so far the "normal" way of composing directives is nesting.
Let's take a look at this concrete example:

@@snip [DirectiveExamplesTest.java](../../../../../../test/java/docs/http/javadsl/server/DirectiveExamplesTest.java) { #example1 }

Here the `get` and `put` routes are chained together with using the `orElse` method to form a higher-level route that
serves as the inner route of the `path` directive. Let's rewrite it in the following way:

@@snip [DirectiveExamplesTest.java](../../../../../../test/java/docs/http/javadsl/server/DirectiveExamplesTest.java) { #getOrPut }

In this previous example, we combined the `get` and `put` directives into one composed directive and extracted it to its own method, which could be reused anywhere else in our code.

In case you are constantly nesting the same directives several times in you code, you could factor them out in their own method and use it everywhere:

@@snip [DirectiveExamplesTest.java](../../../../../../test/java/docs/http/javadsl/server/DirectiveExamplesTest.java) { #composeNesting }

Here we simple created our own combined directive that accepts either `GET` or `PUT` requests, then extracts the method and completes it with an inner route that takes this HTTP method as a parameter.

As you have already seen in the previous section, you can also use the `route` method defined in `RouteDirectives` as an alternative to `orElse` chaining. Here you can see the first example again, rewritten using `route`:

@@snip [DirectiveExamplesTest.java](../../../../../../test/java/docs/http/javadsl/server/DirectiveExamplesTest.java) { #usingRoute }

The `route` combinator comes handy when you want to avoid nesting. Here you can see an illustrative example:
 
@@snip [DirectiveExamplesTest.java](../../../../../../test/java/docs/http/javadsl/server/DirectiveExamplesTest.java) { #usingRouteBig }

Notice how you could adjust the indentation in these last two examples to have a more readable code.

Note that going too far with "compressing" several directives into a single one probably doesn't result in the most
readable and therefore maintainable routing code. It might even be that the very first of this series of examples, or the analogous one using `route`,
is in fact the most readable one.

Still, the purpose of the exercise presented here is to show you how flexible directives can be and how you can
use their power to define your web service behavior at the level of abstraction that is right for **your** application.
