## The URI model

Akka HTTP offers its own specialised `Uri` model class which is tuned for both performance and idiomatic usage within
other types of the HTTP model. For example, an `HttpRequest`'s target URI is parsed into this type, where all character
escaping and other URI specific semantics are applied.

### Parsing a URI string

We follow [RFC 3986](http://tools.ietf.org/html/rfc3986#section-1.1.2) to implement the URI parsing rules.
When you try to parse a URI string, Akka HTTP internally creates an instance of the `URI` class, which holds the modeled URI components inside.

For example, the following creates an instance of a simple valid URI:

```
  URI("http://localhost")
```

Below are some more examples of valid URI strings, and how you can construct a `Uri` model class instances,
using `Uri.from()` method by passing `scheme`, `host`, `path` and `query` parameters.

@@snip [UriSpec.scala](../../../../../../../akka-http-core/src/test/scala/akka/http/scaladsl/model/UriSpec.scala) { #valid-uri-examples }

For exact definitions of the parts of a URI, like `scheme`, `path` and `query` refer to [RFC 3986](http://tools.ietf.org/html/rfc3986#section-1.1.2).
Here's a little overview:

```
  foo://example.com:8042/over/there?name=ferret#nose
  \_/   \______________/\_________/ \_________/ \__/
   |           |            |            |        |
scheme     authority       path        query   fragment
   |   _____________________|__
  / \ /                        \
  urn:example:animal:ferret:nose
```

For "special" characters in URI, you typically use percent encoding like below.
Percent encoding is discussed in more detail in the @ref[Query String in URI](#query-string-in-uri) section.

@@snip [UriSpec.scala](../../../../../../../akka-http-core/src/test/scala/akka/http/scaladsl/model/UriSpec.scala) { #dont-double-decode }

#### Invalid URI strings and IllegalUriException

When an invalid URI string is passed to `Uri()` as below, an `IllegalUriException` is thrown.

@@snip [UriSpec.scala](../../../../../../../akka-http-core/src/test/scala/akka/http/scaladsl/model/UriSpec.scala) { #illegal-cases-immediate-exception }

#### Directives to extract URI components

To extract URI components with directives, see following references:

* @ref:[extractUri](../routing-dsl/directives/basic-directives/extractUri.md)
* @ref:[extractScheme](../routing-dsl/directives/scheme-directives/extractScheme.md)
* @ref:[scheme](../routing-dsl/directives/scheme-directives/scheme.md)
* @ref:[PathDirectives](../routing-dsl/directives/path-directives/index.md)
* @ref:[ParameterDirectives](../routing-dsl/directives/parameter-directives/index.md)

### Obtaining the raw request URI

Sometimes it may be needed to obtain the "raw" value of an incoming URI, without applying any escaping or parsing to it.
While this use case is rare, it comes up every once in a while. It is possible to obtain the "raw" request URI in Akka
HTTP Server side by turning on the `akka.http.server.raw-request-uri-header` flag.
When enabled, a `Raw-Request-URI` header will be added to each request. This header will hold the original raw request's
URI that was used. For an example check the reference configuration.

### Query string in URI

Although any part of URI can have special characters, it is more common for the query string in URI to have special characters,
which are typically [percent encoded](https://en.wikipedia.org/wiki/Percent-encoding).

`Uri` class's `query()` method returns the query string of the URI, which is modeled in an instance of the `Query` class.
When you instantiate a `Uri` class by passing a URI string, the query string is stored in its raw string form.
Then, when you call the `query()` method, the query string is parsed from the raw string.

The below code illustrates how valid query strings are parsed.
Especially, you can check how percent encoding is used and how special characters like `+` and `;` are parsed.

@@@ note
The `mode` parameter to `Query()` and `Uri.query()` is discussed in @ref[Strict and Relaxed Mode](#strict-and-relaxed-mode).
@@@

@@snip [UriSpec.scala](../../../../../../../akka-http-core/src/test/scala/akka/http/scaladsl/model/UriSpec.scala) { #query-strict-definition }

@@snip [UriSpec.scala](../../../../../../../akka-http-core/src/test/scala/akka/http/scaladsl/model/UriSpec.scala) { #query-strict-mode }

Note that:

```
  Uri("http://localhost?a=b").query()
```

is equivalent to:

```
  Query("a=b")
```

As in the [section 3.4 of RFTC 3986](http://tools.ietf.org/html/rfc3986#section-3.4),
some special characters like "/" and "?" are allowed inside a query string, without escaping them using ("%") signs.

> The characters slash ("/") and question mark ("?") may represent data within the query component.

"/" and "?" are commonly used when you have a URI whose query parameter has another URI.

@@snip [UriSpec.scala](../../../../../../../akka-http-core/src/test/scala/akka/http/scaladsl/model/UriSpec.scala) { #query-strict-without-percent-encode }

However, some other special characters can cause `IllegalUriException` without percent encoding as follows.

@@snip [UriSpec.scala](../../../../../../../akka-http-core/src/test/scala/akka/http/scaladsl/model/UriSpec.scala) { #query-strict-mode-exception-1 }

@@snip [UriSpec.scala](../../../../../../../akka-http-core/src/test/scala/akka/http/scaladsl/model/UriSpec.scala) { #query-strict-mode-exception-2 }

#### Strict and Relaxed Mode

The `Uri.query()` method and `Query()` take a parameter `mode`, which is either `Uri.ParsingMode.Strict` or `Uri.ParsingMode.Relaxed`.
Switching the mode gives different behavior on parsing some special characters in URI.

@@snip [UriSpec.scala](../../../../../../../akka-http-core/src/test/scala/akka/http/scaladsl/model/UriSpec.scala) { #query-relaxed-mode }

The below two cases threw `IllegalUriException` when you specified the `Strict` mode,

@@snip [UriSpec.scala](../../../../../../../akka-http-core/src/test/scala/akka/http/scaladsl/model/UriSpec.scala) { #query-strict-mode-exception-1 }

but the `Relaxed` mode parses them as they are.

@@snip [UriSpec.scala](../../../../../../../akka-http-core/src/test/scala/akka/http/scaladsl/model/UriSpec.scala) { #query-relaxed-mode-success }

However, even with the `Relaxed` mode, there are still invalid special characters which require percent encoding.

@@snip [UriSpec.scala](../../../../../../../akka-http-core/src/test/scala/akka/http/scaladsl/model/UriSpec.scala) { #query-relaxed-mode-exception }

Other than specifying the `mode` in the parameters, like when using directives, you can specify the `mode` in your configuration as follows.

```
    # Sets the strictness mode for parsing request target URIs.
    # The following values are defined:
    #
    # `strict`: RFC3986-compliant URIs are required,
    #     a 400 response is triggered on violations
    #
    # `relaxed`: all visible 7-Bit ASCII chars are allowed
    #
    uri-parsing-mode = strict
```

To access the raw, unparsed representation of the query part of a URI use the `rawQueryString` member of the `Uri` class.

#### Directives to extract query parameters

If you want to use directives to extract query parameters, see below pages.

* @ref:[parameters](../routing-dsl/directives/parameter-directives/parameters.md)
* @ref:[parameter](../routing-dsl/directives/parameter-directives/parameter.md)
