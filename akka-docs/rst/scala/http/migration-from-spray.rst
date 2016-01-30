Migration Guide from spray
==========================

**TODO - will be written shortly.**

- ``respondWithStatus`` also known as ``overrideStatusCode`` has not been forward ported to Akka HTTP,
  as it has been seen mostly as an anti-pattern. More information here: https://github.com/akka/akka/issues/18626
- ``respondWithMediaType`` was considered an anti-pattern in spray and is not ported to Akka HTTP.
  Instead users should rely on content type negotiation as Akka HTTP implements it.
  More information here: https://github.com/akka/akka/issues/18625