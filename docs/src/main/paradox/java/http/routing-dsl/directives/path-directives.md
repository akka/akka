# PathDirectives

Path directives are the most basic building blocks for routing requests depending on the URI path.

When a request (or rather the respective @unidoc[RequestContext] instance) enters the route structure it has an
"unmatched path" that is identical to the `request.uri.path`. As it descends the routing tree and passes through one
or more `pathPrefix` or `path` directives the "unmatched path" progressively gets "eaten into" from the
left until, in most cases, it eventually has been consumed completely.

The two main directives are `path` and `pathPrefix`. The `path` directive tries to match the complete remaining
unmatched path against the specified "path matchers", the `pathPrefix` directive only matches a prefix and passes the
remaining unmatched path to nested directives. Both directives automatically match a slash from the beginning, so
that matching slashes in a hierarchy of nested `pathPrefix` and `path` directives is usually not needed.

Path directives take a variable amount of arguments. Each argument must be a `PathMatcher` or a string (which is
automatically converted to a path matcher using `PathMatchers.segment`). In the case of `path` and `pathPrefix`,
if multiple arguments are supplied, a slash is assumed between any of the supplied path matchers. The `rawPathX`
variants of those directives on the other side do no such preprocessing, so that slashes must be matched manually.

## Path Matchers

A path matcher is a description of a part of a path to match. The simplest path matcher is `PathMatcher.segment` which
matches exactly one path segment against the supplied constant string.

Other path matchers defined in @unidoc[PathMatchers] match the end of the path (`PathMatchers.END`), a single slash
(`PathMatchers.SLASH`), or nothing at all (`PathMatchers.NEUTRAL`).

Many path matchers are hybrids that can both match (by using them with one of the PathDirectives) and extract values,
Extracting a path matcher value (i.e. using it with `handleWithX`) is only allowed if it nested inside a path
directive that uses that path matcher and so specifies at which position the value should be extracted from the path.

Predefined path matchers allow extraction of various types of values:

`PathMatchers.segment(String)`
: Strings simply match themselves and extract no value.
Note that strings are interpreted as the decoded representation of the path, so if they include a '/' character
this character will match "%2F" in the encoded raw URI!

`PathMatchers.regex`
: You can use a regular expression instance as a path matcher, which matches whatever the regex matches and extracts
one `String` value. A `PathMatcher` created from a regular expression extracts either the complete match (if the
regex doesn't contain a capture group) or the capture group (if the regex contains exactly one capture group).
If the regex contains more than one capture group an `IllegalArgumentException` will be thrown.

`PathMatchers.SLASH`
: Matches exactly one path-separating slash (`/`) character.

`PathMatchers.END`
: Matches the very end of the path, similar to `$` in regular expressions.

`PathMatchers.Segment`
: Matches if the unmatched path starts with a path segment (i.e. not a slash).
If so the path segment is extracted as a `String` instance.

`PathMatchers.Remaining`
: Matches and extracts the complete remaining unmatched part of the request's URI path as an (encoded!) String.
If you need access to the remaining *decoded* elements of the path use `RemainingPath` instead.

`PathMatchers.intValue`
: Efficiently matches a number of decimal digits (unsigned) and extracts their (non-negative) `Int` value. The matcher
will not match zero digits or a sequence of digits that would represent an `Int` value larger than `Integer.MAX_VALUE`.

`PathMatchers.longValue`
: Efficiently matches a number of decimal digits (unsigned) and extracts their (non-negative) `Long` value. The matcher
will not match zero digits or a sequence of digits that would represent an `Long` value larger than `Long.MAX_VALUE`.

`PathMatchers.hexIntValue`
: Efficiently matches a number of hex digits and extracts their (non-negative) `Int` value. The matcher will not match
zero digits or a sequence of digits that would represent an `Int` value larger than `Integer.MAX_VALUE`.

`PathMatchers.hexLongValue`
: Efficiently matches a number of hex digits and extracts their (non-negative) `Long` value. The matcher will not
match zero digits or a sequence of digits that would represent an `Long` value larger than `Long.MAX_VALUE`.

`PathMatchers.uuid`
: Matches and extracts a `java.util.UUID` instance.

`PathMatchers.NEUTRAL`
: A matcher that always matches, doesn't consume anything and extracts nothing.
Serves mainly as a neutral element in `PathMatcher` composition.

`PathMatchers.segments`
: Matches all remaining segments as a list of strings. Note that this can also be "no segments" resulting in the empty
list. If the path has a trailing slash this slash will *not* be matched, i.e. remain unmatched and to be consumed by
potentially nested directives.


Here's a collection of path matching examples:

@@snip [PathDirectiveExampleTest.java]($test$/java/docs/http/javadsl/server/PathDirectiveExampleTest.java) { #path-examples }