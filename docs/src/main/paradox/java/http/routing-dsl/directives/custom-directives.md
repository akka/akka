# Custom Directives

Part of the power of akka-http directives comes from the ease with which it’s possible to define
custom directives at differing levels of abstraction.

There are essentially three ways of creating custom directives:

 1. By introducing new “labels” for configurations of existing directives
 2. By transforming existing directives
 3. By writing a directive “from scratch”

## Configuration Labeling

The easiest way to create a custom directive is to simply assign a new name for a certain configuration
of one or more existing directives. In fact, most of the predefined akka-http directives can be considered
named configurations of more low-level directives.

The basic technique is explained in the chapter about Composing Directives, where, for example, a new directive
`getOrPut` is defined like this:

@@snip [CustomDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/CustomDirectivesExamplesTest.java) { #labeling-1 #labeling-2 }

Multiple directives can be nested to produce a single directive out of multiple like this:

@@snip [CustomDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/CustomDirectivesExamplesTest.java) { #composition-1 #composition-2 }

Another example is the @ref[MethodDirectives](method-directives/index.md) which are simply instances of a preconfigured @ref[method](method-directives/method.md) directive.
The low-level directives that most often form the basis of higher-level “named configuration” directives are grouped
together in the @ref[BasicDirectives](basic-directives/index.md) trait.