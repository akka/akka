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

Directives create @ref[Routes](../routes.md). To understand how directives work it is helpful to contrast them with the "primitive"
way of creating routes.

@ref[Routes](../routes.md) effectively are simply highly specialised functions that take a @unidoc[RequestContext] and eventually `complete` it, 
which could (and often should) happen asynchronously.

@@@ div { .group-java }

The @ref[complete](route-directives/complete.md) directive simply completes the request with a response:

@@@

@@@ div { .group-scala }


Since @unidoc[Route] is just a type alias for a function type @unidoc[Route] instances can be written in any way in which function
instances can be written, e.g. as a function literal:

```scala
val route: Route = { ctx => ctx.complete("yeah") }
```

or shorter:

```scala
val route: Route = _.complete("yeah")
```

With the @ref[complete](route-directives/complete.md) directive this becomes even shorter:

@@@

Scala
:  ```scala
val route = complete("yeah")
```

Java
:  ```java
Route route = complete("yeah");
```

@@@ div { .group-scala }

These three ways of writing this @unidoc[Route] are fully equivalent, the created `route` will behave identically in all
cases.

Let's look at a slightly more complicated example to highlight one important point in particular.
Consider these two routes:

```scala
val a: Route = {
  println("MARK")
  ctx => ctx.complete("yeah")
}

val b: Route = { ctx =>
  println("MARK")
  ctx.complete("yeah")
}
```

The difference between `a` and `b` is when the `println` statement is executed.
In the case of `a` it is executed *once*, when the route is constructed, whereas in the case of `b` it is executed
every time the route is *run*.

Using the @ref[complete](route-directives/complete.md) directive the same effects are achieved like this:

```scala
val a = {
  println("MARK")
  complete("yeah")
}

val b = complete {
  println("MARK")
  "yeah"
}
```

This works because the argument to the @ref[complete](route-directives/complete.md) directive is evaluated *by-name*, i.e. it is re-evaluated
every time the produced route is run.

Let's take things one step further:

```scala
val route: Route = { ctx =>
  if (ctx.request.method == HttpMethods.GET)
    ctx.complete("Received GET")
  else
    ctx.complete("Received something else")
}
```

Using the @ref[get](method-directives/get.md) and @ref[complete](route-directives/complete.md) directives we can write this route like this:

```scala
val route =
  get {
    complete("Received GET")
  } ~
  complete("Received something else")
```

Again, the produced routes will behave identically in all cases.

Note that, if you wish, you can also mix the two styles of route creation:

```scala
val route =
  get { ctx =>
    ctx.complete("Received GET")
  } ~
  complete("Received something else")
```

Here, the inner route of the @ref[get](method-directives/get.md) directive is written as an explicit function literal.

However, as you can see from these examples, building routes with directives rather than "manually" results in code that
is a lot more concise and as such more readable and maintainable. In addition it provides for better composability (as
you will see in the coming sections). So, when using Akka HTTP's Routing DSL you should almost never have to fall back
to creating routes via @unidoc[Route] function literals that directly manipulate the @ref[RequestContext](../routes.md#requestcontext).

@@@

@@@ div { .group-java }

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

@@@

If no route matches a given request, a default `404 Not Found` response will be returned as response.

## Structure

The general anatomy of a directive is as follows:

Scala
:  ```scala
name(arguments) { extractions =>
  ... // inner route
}
```

Java
:  ```java
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
 * Chain some logic into the @ref[RouteResult](../routes.md#routeresult) future transformation chain (i.e. modify the response or rejection)
 * Complete the request

This means a `Directive` completely wraps the functionality of its inner route and can apply arbitrarily complex
transformations, both (or either) on the request and on the response side.

## Composing Directives

@@@ note { .group-scala }
Gotcha: forgetting the `~` (tilde) character in between directives can result in perfectly valid
Scala code that compiles but does not work as expected. What would be intended as a single expression would actually be multiple expressions, and only the final one would be used as the result of the parent directive. Alternatively, you might choose to use the `concat` combinator. `concat(a, b, c)` is the same as `a ~ b ~ c`.
@@@

As you have seen from the examples presented so far the "normal" way of composing directives is nesting.
Let's take a look at this concrete example:

Scala
:  @@snip [DirectiveExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/DirectiveExamplesSpec.scala) { #example-1 }

Java
:  @@snip [DirectiveExamplesTest.java]($test$/java/docs/http/javadsl/server/DirectiveExamplesTest.java) { #example1 }

Here the `get` and `put` directives are chained together @scala[with the `~` operator]@java[using the `orElse` method] to form a higher-level route that
serves as the inner route of the `path` directive. Let's rewrite it in the following way:

Scala
:  @@snip [DirectiveExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/DirectiveExamplesSpec.scala) { #getOrPut }

Java
:  @@snip [DirectiveExamplesTest.java]($test$/java/docs/http/javadsl/server/DirectiveExamplesTest.java) { #getOrPut }

@@@ div { .group-java }

In this previous example, we combined the `get` and `put` directives into one composed directive and extracted it to its own method, which could be reused anywhere else in our code.

Instead of extracting the composed directives to its own method, we can also use the available `anyOf` combinator. The following code is equivalent to the previous one:

@@@

@@@ div { .group-scala }

What you can't see from this snippet is that directives are not implemented as simple methods but rather as stand-alone
objects of type `Directive`. This gives you more flexibility when composing directives. For example you can
also use the `|` operator on directives. Here is yet another way to write the example:

@@@

Scala
:  @@snip [DirectiveExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/DirectiveExamplesSpec.scala) { #getOrPutUsingPipe }

Java
:  @@snip [DirectiveExamplesTest.java]($test$/java/docs/http/javadsl/server/DirectiveExamplesTest.java) { #getOrPutUsingAnyOf }

@@@ div { .group-scala }

Or better (without dropping down to writing an explicit @unidoc[Route] function manually):

@@snip [DirectiveExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/DirectiveExamplesSpec.scala) { #getOrPutUsingPipeAndExtractMethod }

If you have a larger route structure where the `(get | put)` snippet appears several times you could also factor it
out like this:

@@snip [DirectiveExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/DirectiveExamplesSpec.scala) { #example-5 }

Note that, because `getOrPut` doesn't take any parameters, it can be a `val` here.

As an alternative to nesting you can also use the `&` operator:

@@snip [DirectiveExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/DirectiveExamplesSpec.scala) { #example-6 }

Here you can see that, when directives producing extractions are combined with `&`, the resulting "super-directive"
simply extracts the concatenation of its sub-extractions.

And once again, you can factor things out if you want, thereby pushing the "factoring out" of directive configurations
to its extreme:

@@snip [DirectiveExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/DirectiveExamplesSpec.scala) { #example-7 }

This type of combining directives with the `|` and `&` operators as well as "saving" more complex directive
configurations as a `val` works across the board, with all directives taking inner routes.

@@@

@@@ div { .group-java }

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

@@@

Note that going too far with "compressing" several directives into a single one probably doesn't result in the most
readable and therefore maintainable routing code. It might even be that the very first of this series of examples
is in fact the most readable one.

Still, the purpose of the exercise presented here is to show you how flexible directives can be and how you can
use their power to define your web service behavior at the level of abstraction that is right for **your** application.

@@@ div { .group-scala }

### Composing Directives with `concat` Combinator

Alternatively we can combine directives using `concat` combinator where we pass each directive as an argument to the combinator function instead of chaining them with `~` . Let's take a look at the usage of this combinator:

@@snip [DirectiveExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/DirectiveExamplesSpec.scala) { #example-8 }

@@@

## Type Safety of Directives

When you combine directives with the @scala[`|` and `&` operators]@java[`anyOf` and `allOf` methods] the routing DSL makes sure that all extractions work as
expected and logical constraints are enforced at compile-time.

For example you cannot @scala[`|`]@java[`anyOf`] a directive producing an extraction with one that doesn't:

Scala
:   ```scala
val route = path("order" / IntNumber) | get // doesn't compile
```

Java
:  ```java
anyOf(this::get, this::extractClientIP, routeProvider) // doesn't compile
```

Also the number of extractions and their types have to match up:

Scala
:  ```scala
val route = path("order" / IntNumber) | path("order" / DoubleNumber)   // doesn't compile
val route = path("order" / IntNumber) | parameter('order.as[Int])      // ok
```

Java
:  ```java
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

When you combine directives producing extractions with the @scala[`&` operator]@java[`allOf` method] all extractions will be properly gathered up:

Scala
:  ```scala
val order = path("order" / IntNumber) & parameters('oem, 'expired ?)
val route =
  order { (orderId, oem, expired) =>
    ...
  }
```

Java
:  ```java
allOf(this::extractScheme, this::extractMethod, (scheme, method) -> ...) 
```

Directives offer a great way of constructing your web service logic from small building blocks in a plug and play
fashion while maintaining DRYness and full type-safety. If the large range of @ref[Predefined Directives](alphabetically.md) does not
fully satisfy your needs you can also easily create @ref[Custom Directives](custom-directives.md).

@@@ div { .group-scala }

## Automatic Tuple extraction (flattening)

Convenient Scala DSL syntax described in @ref[Basics](#basics), and @ref[Composing Directives](#composing-directives) 
are made possible by Tuple extraction internally. Let's see how this works with examples.
 
```scala
val futureOfInt: Future[Int] = Future.successful(1)
val route =
  path("success") {
    onSuccess(futureOfInt) { //: Directive[Tuple1[Int]]
      i => complete("Future was completed.")
    }
  }
```
Looking at the above code, `onSuccess(futureOfInt)` returns a `Directive1[Int] = Directive[Tuple1[Int]]`.

```scala
val futureOfTuple2: Future[Tuple2[Int,Int]] = Future.successful( (1,2) )
val route =
  path("success") {
    onSuccess(futureOfTuple2) { //: Directive[Tuple2[Int,Int]]
      (i, j) => complete("Future was completed.")
    }
  }
```

Similarly, `onSuccess(futureOfTuple2)` returns a `Directive1[Tuple2[Int,Int]] = Directive[Tuple1[Tuple2[Int,Int]]]`,
but this will be automatically converted to `Directive[Tuple2[Int,Int]]` to avoid nested Tuples.

```scala
val futureOfUnit: Future[Unit] = Future.successful( () )
val route =
  path("success") {
    onSuccess(futureOfUnit) { //: Directive0
        complete("Future was completed.")
    }
  }
```
If the future returns `Future[Unit]`, it is a bit special case as it results in `Directive0`.
Looking at the above code, `onSuccess(futureOfUnit)` returns a `Directive1[Unit] = Directive[Tuple1[Unit]]`.
However, the DSL interprets `Unit` as `Tuple0`, and automatically converts the result to `Directive[Unit] = Directive0`,

@@@
