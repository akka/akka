# PathDirectives

@@toc { depth=1 }

@@@ index

* [path](path.md)
* [pathEnd](pathEnd.md)
* [pathEndOrSingleSlash](pathEndOrSingleSlash.md)
* [pathPrefix](pathPrefix.md)
* [pathPrefixTest](pathPrefixTest.md)
* [pathSingleSlash](pathSingleSlash.md)
* [pathSuffix](pathSuffix.md)
* [pathSuffixTest](pathSuffixTest.md)
* [rawPathPrefix](rawPathPrefix.md)
* [rawPathPrefixTest](rawPathPrefixTest.md)
* [redirectToNoTrailingSlashIfPresent](redirectToNoTrailingSlashIfPresent.md)
* [redirectToTrailingSlashIfMissing](redirectToTrailingSlashIfMissing.md)
* [ignoreTrailingSlash](ignoreTrailingSlash.md)
* [../../path-matchers](../../path-matchers.md)

@@@

<a id="overview-path-scala"></a>
## Overview of path directives

This is a tiny overview for some of the most common path directives:

* `rawPathPrefix(x)`: it matches x and leaves a suffix (if any) unmatched.
* `pathPrefix(x)`: is equivalent to `rawPathPrefix(Slash ~ x)`. It matches a leading slash followed by _x_ and then leaves a suffix unmatched.
* `path(x)`: is equivalent to `rawPathPrefix(Slash ~ x ~ PathEnd)`. It matches a leading slash followed by _x_ and then the end.
* `pathEnd`: is equivalent to just `rawPathPrefix(PathEnd)`. It is matched only when there is nothing left to match from the path. This directive should not be used at the root as the minimal path is the single slash.
* `pathSingleSlash`: is equivalent to `rawPathPrefix(Slash ~ PathEnd)`. It matches when the remaining path is just a single slash.
* `pathEndOrSingleSlash`: is equivalent to `rawPathPrefix(PathEnd)` or `rawPathPrefix(Slash ~ PathEnd)`. It matches either when there is no remaining path or is just a single slash.
