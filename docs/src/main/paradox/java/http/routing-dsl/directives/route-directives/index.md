<a id="routedirectives-java"></a>
# RouteDirectives

The `RouteDirectives` have a special role in akka-http's routing DSL. Contrary to all other directives (except most
@ref[FileAndResourceDirectives](../file-and-resource-directives/index.md#fileandresourcedirectives-java)) they do not produce instances of type `Directive[L <: HList]` but rather "plain"
routes of type `Route`.
The reason is that the `RouteDirectives` are not meant for wrapping an inner route (like most other directives, as
intermediate-level elements of a route structure, do) but rather form the leaves of the actual route structure **leaves**.

So in most cases the inner-most element of a route structure branch is one of the `RouteDirectives` (or
@ref[FileAndResourceDirectives](../file-and-resource-directives/index.md#fileandresourcedirectives-java)):

@@toc { depth=1 }

@@@ index

* [complete](complete.md)
* [failWith](failWith.md)
* [redirect](redirect.md)
* [reject](reject.md)

@@@