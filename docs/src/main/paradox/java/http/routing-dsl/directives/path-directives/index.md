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
* [../path-directives](../path-directives.md)

@@@

<a id="overview-path"></a>
## Overview of path directives

This is a tiny overview for some of the most common path directives:

* `rawPathPrefix(x)`: it matches x and leaves a suffix (if any) unmatched.
* `pathPrefix(x)`: is equivalent to `rawPathPrefix(slash().concat(segment(x)))`. It matches a leading slash followed by _x_ and then leaves a suffix unmatched.
* `path(x)`: is equivalent to `rawPathPrefix(slash().concat(segment(x)).concat(pathEnd()))`. It matches a leading slash followed by _x_ and then the end.
* `pathEnd`: is equivalent to `rawPathPrefix(pathEnd())`. It is matched only when there is nothing left to match from the path. This directive should not be used at the root as the minimal path is the single slash.
* `pathSingleSlash`: is equivalent to `rawPathPrefix(slash().concat(pathEnd()))`. It matches when the remaining path is just a single slash.
* `pathEndOrSingleSlash`: is equivalent to `rawPathPrefix(pathEnd())` or `rawPathPrefix(slash().concat(pathEnd()))`. It matches either when there is no remaining path or is just a single slash.
