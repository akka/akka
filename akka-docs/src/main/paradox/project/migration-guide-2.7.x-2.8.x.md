---
project.description: Migrating to Akka 2.8.
---
# Migration Guide 2.7.x to 2.8.x

Akka 2.8.x is binary backwards compatible with 2.7.x with the ordinary exceptions listed in the
@ref:[Binary Compatibility Rules](../common/binary-compatibility-rules.md).

No configuration changes are needed for updating an application from Akka 2.7.x to 2.8.x.

Rolling updates of Akka Cluster from Akka 2.7.x to 2.8.x is fully supported.

No deprecated features or APIs have been removed in Akka 2.8.x, except for OSGi


## OSGi no longer supported out of the box

OSGi packaging and support in Akka has been dropped.

Projects using OSGi will need to re-package or find another solution for using OSGi, you can find some possible hints
in issue: https://github.com/akka/akka/issues/28304
