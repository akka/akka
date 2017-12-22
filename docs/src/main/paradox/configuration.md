# Configuration

Just like any other Akka module Akka HTTP is configured via [Typesafe Config](https://github.com/lightbend/config).
Usually this means that you provide an `application.conf` which contains all the application-specific settings that
differ from the default ones provided by the reference configuration files from the individual Akka modules.

These are the relevant default configuration values for the Akka HTTP modules.

akka-http-core
:  @@snip [reference.conf]($akka-http$/akka-http-core/src/main/resources/reference.conf)

akka-http
:  @@snip [reference.conf]($akka-http$/akka-http/src/main/resources/reference.conf)

akka-http-caching
:  @@snip [reference.conf]($akka-http$/akka-http-caching/src/main/resources/reference.conf)

The other Akka HTTP modules do not offer any configuration via [Typesafe Config](https://github.com/lightbend/config).
