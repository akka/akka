# The PathMatcher DSL

For being able to work with the @ref[PathDirectives](directives/path-directives/index.md) effectively you should have some understanding of the
`PathMatcher` mini-DSL that Akka HTTP provides for elegantly defining URI matching behavior.

## Overview

When a request (or rather the respective @unidoc[RequestContext] instance) enters the route structure it has an
"unmatched path" that is identical to the `request.uri.path`. As it descends the routing tree and passes through one
or more @ref[pathPrefix](directives/path-directives/pathPrefix.md) or @ref[path](directives/path-directives/path.md) directives the "unmatched path" progressively gets "eaten into" from the
left until, in most cases, it eventually has been consumed completely.

What exactly gets matched and consumed as well as extracted from the unmatched path in each directive is defined with
the path matching DSL, which is built around these types:

```scala
trait PathMatcher[L: Tuple]
type PathMatcher0 = PathMatcher[Unit]
type PathMatcher1[T] = PathMatcher[Tuple1[T]]
```

The number and types of the values extracted by a `PathMatcher` instance is represented by the `L` type
parameter which needs to be one of Scala's TupleN types or `Unit` (which is designated by the `Tuple` context bound).
The convenience alias `PathMatcher0` can be used for all matchers which don't extract anything while `PathMatcher1[T]`
defines a matcher which only extracts a single value of type `T`.

Here is an example of a more complex `PathMatcher` expression:

@@snip [PathDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/PathDirectivesExamplesSpec.scala) { #path-matcher }

This will match paths like `foo/bar/X42/edit` or `foo/bar/X/create`.

@@@ note
The path matching DSL describes what paths to accept **after** URL decoding. This is why the path-separating
slashes have special status and cannot simply be specified as part of a string! The string "foo/bar" would match
the raw URI path "foo%2Fbar", which is most likely not what you want!
@@@

## Basic PathMatchers

A complex `PathMatcher` can be constructed by combining or modifying more basic ones. Here are the basic matchers
that Akka HTTP already provides for you:

String
: You can use a `String` instance as a `PathMatcher0`. Strings simply match themselves and extract no value.
Note that strings are interpreted as the decoded representation of the path, so if they include a '/' character
this character will match "%2F" in the encoded raw URI!

Regex
: You can use a `Regex` instance as a `PathMatcher1[String]`, which matches whatever the regex matches and extracts
one `String` value. A `PathMatcher` created from a regular expression extracts either the complete match (if the
regex doesn't contain a capture group) or the capture group (if the regex contains exactly one capture group).
If the regex contains more than one capture group an `IllegalArgumentException` will be thrown.

Map[String, T]
: You can use a `Map[String, T]` instance as a `PathMatcher1[T]`, which matches any of the keys and extracts the
respective map value for it.

Slash: PathMatcher0
: Matches exactly one path-separating slash (`/`) character and extracts nothing.

Segment: PathMatcher1[String]
: Matches if the unmatched path starts with a path segment (i.e. not a slash).
If so the path segment is extracted as a `String` instance.

PathEnd: PathMatcher0
: Matches the very end of the path, similar to `$` in regular expressions and extracts nothing.

Remaining: PathMatcher1[String]
: Matches and extracts the complete remaining unmatched part of the request's URI path as an (encoded!) String.
If you need access to the remaining *decoded* elements of the path use `RemainingPath` instead.

RemainingPath: PathMatcher1[Path]
: Matches and extracts the complete remaining, unmatched part of the request's URI path.

IntNumber: PathMatcher1[Int]
: Efficiently matches a number of decimal digits (unsigned) and extracts their (non-negative) `Int` value. The matcher
will not match zero digits or a sequence of digits that would represent an `Int` value larger than `Int.MaxValue`.

LongNumber: PathMatcher1[Long]
: Efficiently matches a number of decimal digits (unsigned) and extracts their (non-negative) `Long` value. The matcher
will not match zero digits or a sequence of digits that would represent an `Long` value larger than `Long.MaxValue`.

HexIntNumber: PathMatcher1[Int]
: Efficiently matches a number of hex digits and extracts their (non-negative) `Int` value. The matcher will not match
zero digits or a sequence of digits that would represent an `Int` value larger than `Int.MaxValue`.

HexLongNumber: PathMatcher1[Long]
: Efficiently matches a number of hex digits and extracts their (non-negative) `Long` value. The matcher will not
match zero digits or a sequence of digits that would represent an `Long` value larger than `Long.MaxValue`.

DoubleNumber: PathMatcher1[Double]
: Matches and extracts a `Double` value. The matched string representation is the pure decimal,
optionally signed form of a double value, i.e. without exponent.

JavaUUID: PathMatcher1[UUID]
: Matches and extracts a `java.util.UUID` instance.

Neutral: PathMatcher0
: A matcher that always matches, doesn't consume anything and extracts nothing.
Serves mainly as a neutral element in `PathMatcher` composition.

Segments: PathMatcher1[List[String]]
: Matches all remaining segments as a list of strings. Note that this can also be "no segments" resulting in the empty
list. If the path has a trailing slash this slash will *not* be matched, i.e. remain unmatched and to be consumed by
potentially nested directives.

separateOnSlashes(string: String): PathMatcher0
: Converts a path string containing slashes into a `PathMatcher0` that interprets slashes as
path segment separators. This means that a matcher matching "%2F" cannot be constructed with this helper.

provide[L: Tuple](extractions: L): PathMatcher[L]
: Always matches, consumes nothing and extracts the given `TupleX` of values.

PathMatcher[L: Tuple](prefix: Path, extractions: L): PathMatcher[L]
: Matches and consumes the given path prefix and extracts the given list of extractions.
If the given prefix is empty the returned matcher matches always and consumes nothing.


## Combinators

Path matchers can be combined with these combinators to form higher-level constructs:

Tilde Operator (`~`)
: The tilde is the most basic combinator. It simply concatenates two matchers into one, i.e if the first one matched
(and consumed) the second one is tried. The extractions of both matchers are combined type-safely.
For example: `"foo" ~ "bar"` yields a matcher that is identical to `"foobar"`.

Slash Operator (`/`)
: This operator concatenates two matchers and inserts a `Slash` matcher in between them.
For example: `"foo" / "bar"` is identical to `"foo" ~ Slash ~ "bar"`.

Pipe Operator (`|`)
: This operator combines two matcher alternatives in that the second one is only tried if the first one did *not* match.
The two sub-matchers must have compatible types.
For example: `"foo" | "bar"` will match either "foo" *or* "bar".


## Modifiers

Path matcher instances can be transformed with these modifier methods:

/
: The slash operator cannot only be used as combinator for combining two matcher instances, it can also be used as
a postfix call. `matcher /` is identical to `matcher ~ Slash` but shorter and easier to read.

?
: 
By postfixing a matcher with `?` you can turn any `PathMatcher` into one that always matches, optionally consumes
and potentially extracts an `Option` of the underlying matchers extraction. The result type depends on the type
of the underlying matcher:

|If a `matcher` is of type | then `matcher.?` is of type|
|--------------------------|----------------------------|
|`PathMatcher0`          | `PathMatcher0`          |
|`PathMatcher1[T]`       | `PathMatcher1[Option[T]`|
|`PathMatcher[L: Tuple]` | `PathMatcher[Option[L]]`|

repeat(separator: PathMatcher0 = PathMatchers.Neutral)
: 
By postfixing a matcher with `repeat(separator)` you can turn any `PathMatcher` into one that always matches,
consumes zero or more times (with the given separator) and potentially extracts a `List` of the underlying matcher's
extractions. The result type depends on the type of the underlying matcher:

|If a `matcher` is of type | then `matcher.repeat(...)` is of type|
|--------------------------|--------------------------------------|
|`PathMatcher0`          | `PathMatcher0`        |
|`PathMatcher1[T]`       | `PathMatcher1[List[T]`|
|`PathMatcher[L: Tuple]` | `PathMatcher[List[L]]`|

unary_!
: By prefixing a matcher with `!` it can be turned into a `PathMatcher0` that only matches if the underlying matcher
does *not* match and vice versa.

transform / (h)flatMap / (h)map
: These modifiers allow you to append your own "post-application" logic to another matcher in order to form a custom
one. You can map over the extraction(s), turn mismatches into matches or vice-versa or do anything else with the
results of the underlying matcher. Take a look at the method signatures and implementations for more guidance as to
how to use them.


## Examples

@@snip [PathDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/PathDirectivesExamplesSpec.scala) { #path-dsl }
