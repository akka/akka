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

```scala
val route = complete("yeah")
```

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

## Structure

The general anatomy of a directive is as follows:

```scala
name(arguments) { extractions =>
  ... // inner route
}
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

@@@ note
Gotcha: forgetting the `~` (tilde) character in between directives can result in perfectly valid
Scala code that compiles but does not work as expected. What would be intended as a single expression would actually be multiple expressions, and only the final one would be used as the result of the parent directive. Alternatively, you might choose to use the `concat` combinator. `concat(a, b, c)` is the same as `a ~ b ~ c`.
@@@

As you have seen from the examples presented so far the "normal" way of composing directives is nesting.
Let's take a look at this concrete example:

@@snip [DirectiveExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/DirectiveExamplesSpec.scala) { #example-1 }

Here the `get` and `put` directives are chained together with the `~` operator to form a higher-level route that
serves as the inner route of the `path` directive. To make this structure more explicit you could also write the whole
thing like this:

@@snip [DirectiveExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/DirectiveExamplesSpec.scala) { #example-2 }

What you can't see from this snippet is that directives are not implemented as simple methods but rather as stand-alone
objects of type `Directive`. This gives you more flexibility when composing directives. For example you can
also use the `|` operator on directives. Here is yet another way to write the example:

@@snip [DirectiveExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/DirectiveExamplesSpec.scala) { #example-3 }

Or better (without dropping down to writing an explicit @unidoc[Route] function manually):

@@snip [DirectiveExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/DirectiveExamplesSpec.scala) { #example-4 }

If you have a larger route structure where the `(get | put)` snippet appears several times you could also factor it
out like this:

@@snip [DirectiveExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/DirectiveExamplesSpec.scala) { #example-5 }

Note that, because `getOrPut` doesn't take any parameters, it can be a `val` here.

As an alternative to nesting you can also use the *&* operator:

@@snip [DirectiveExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/DirectiveExamplesSpec.scala) { #example-6 }

Here you can see that, when directives producing extractions are combined with `&`, the resulting "super-directive"
simply extracts the concatenation of its sub-extractions.

And once again, you can factor things out if you want, thereby pushing the "factoring out" of directive configurations
to its extreme:

@@snip [DirectiveExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/DirectiveExamplesSpec.scala) { #example-7 }

This type of combining directives with the `|` and `&` operators as well as "saving" more complex directive
configurations as a `val` works across the board, with all directives taking inner routes.

Note that going this far with "compressing" several directives into a single one probably doesn't result in the most
readable and therefore maintainable routing code. It might even be that the very first of this series of examples
is in fact the most readable one.

Still, the purpose of the exercise presented here is to show you how flexible directives can be and how you can
use their power to define your web service behavior at the level of abstraction that is right for **your** application.

### Composing Directives with `concat` Combinator

Alternatively we can combine directives using `concat` combinator where we pass each directive as an argument to the combinator function instead of chaining them with `~` . Let's take a look at the usage of this combinator:

@@snip [DirectiveExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/DirectiveExamplesSpec.scala) { #example-8 }

## Type Safety of Directives

When you combine directives with the `|` and `&` operators the routing DSL makes sure that all extractions work as
expected and logical constraints are enforced at compile-time.

For example you cannot `|` a directive producing an extraction with one that doesn't:

```scala
val route = path("order" / IntNumber) | get // doesn't compile
```

Also the number of extractions and their types have to match up:

```scala
val route = path("order" / IntNumber) | path("order" / DoubleNumber)   // doesn't compile
val route = path("order" / IntNumber) | parameter('order.as[Int])      // ok
```

When you combine directives producing extractions with the `&` operator all extractions will be properly gathered up:

```scala
val order = path("order" / IntNumber) & parameters('oem, 'expired ?)
val route =
  order { (orderId, oem, expired) =>
    ...
  }
```

Directives offer a great way of constructing your web service logic from small building blocks in a plug and play
fashion while maintaining DRYness and full type-safety. If the large range of @ref[Predefined Directives](alphabetically.md) does not
fully satisfy your needs you can also easily create @ref[Custom Directives](custom-directives.md).

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

