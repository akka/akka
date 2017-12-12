# FileAndResourceDirectives

Like the @ref[RouteDirectives](../route-directives/index.md) the @unidoc[FileAndResourceDirectives] are somewhat special in akka-http's routing DSL.
Contrary to all other directives they do not produce instances of type `Directive[L <: HList]` but rather "plain"
routes of type @unidoc[Route].
The reason is that they are not meant for wrapping an inner route (like most other directives, as intermediate-level
elements of a route structure, do) but rather form the actual route structure **leaves**.

So in most cases the inner-most element of a route structure branch is one of the @ref[RouteDirectives](../route-directives/index.md) or
@unidoc[FileAndResourceDirectives].

@@toc { depth=1 }

@@@ index

* [getFromBrowseableDirectories](getFromBrowseableDirectories.md)
* [getFromBrowseableDirectory](getFromBrowseableDirectory.md)
* [getFromDirectory](getFromDirectory.md)
* [getFromFile](getFromFile.md)
* [getFromResource](getFromResource.md)
* [getFromResourceDirectory](getFromResourceDirectory.md)
* [listDirectoryContents](listDirectoryContents.md)

@@@