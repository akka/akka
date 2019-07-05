# Classic Actors

@@@ note

Akka Classic is the original Actor APIs, which have been improved by more type safe and guided Actor APIs, 
known as Akka Typed. Akka Classic is still fully supported and existing applications can continue to use 
the classic APIs. It is also possible to use Akka Typed together with classic actors within the same 
ActorSystem, see @ref[coexistense](typed/coexisting.md). For new projects we recommend using the new Actor APIs.

For the new API see @ref[Actors](typed/actors.md).

@@@

## Dependency

To use Classic Akka Actors, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-actor_$scala.binary_version$"
  version="$akka.version$"
}

@@toc { depth=2 }

@@@ index

* [actors](actors.md)
* [fault-tolerance](fault-tolerance.md)
* [dispatchers](dispatchers.md)
* [mailboxes](mailboxes.md)
* [routing](routing.md)
* [fsm](fsm.md)
* [persistence](persistence.md)
* [persistence-schema-evolution](persistence-schema-evolution.md)
* [persistence-query](persistence-query.md)
* [persistence-query-leveldb](persistence-query-leveldb.md)
* [persistence-fsm](persistence-fsm.md)
* [persistence-journals](persistence-journals.md)
* [testing](testing.md)

@@@
