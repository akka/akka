# Migration Guide within Akka HTTP 10.0.x

## General Notes
Akka HTTP is binary backwards compatible within for all version within the 10.0.x range. However, there are a set of APIs
that are marked with the special annotation `@ApiMayChange` which are exempt from this rule, in order to allow them to be
evolved more freely until stabilising them, by removing this annotation.
See @extref:[The @DoNotInherit and @ApiMayChange markers](akka-docs:common/binary-compatibility-rules.html#The_@DoNotInherit_and_@ApiMayChange_markers) for further information.

This migration guide aims to help developers who use these bleeding-edge APIs to migrate between their evolving versions
within patch releases.

## Akka HTTP 10.0.6 -> 10.0.7

### `HttpApp#route` has been renamed to `HttpApp#routes`

In order to provide a more descriptive name, `HttpApp#route` has been renamed to `HttpApp#routes`. The previous name
might have led to some confusion by wrongly implying that only one route could be returned in that method.
To migrate to 10.0.6, you must rename the old `route` method to `routes`.
