# Persistence Plugins 

Storage backends for journals and snapshot stores are pluggable in the Akka persistence extension.

A directory of persistence journal and snapshot store plugins is available at the Akka Community Projects page, see [Community plugins](http://akka.io/community/)

Two popular plugins are:

* [akka-persistence-cassandra](https://doc.akka.io/docs/akka-persistence-cassandra/current/)
* [akka-persistence-jdbc](https://github.com/dnvriend/akka-persistence-jdbc)

Plugins can be selected either by "default" for all persistent actors,
or "individually", when a persistent actor defines its own set of plugins.

When a persistent actor does NOT override the `journalPluginId` and `snapshotPluginId` methods,
the persistence extension will use the "default" journal and snapshot-store plugins configured in `reference.conf`:

```
akka.persistence.journal.plugin = ""
akka.persistence.snapshot-store.plugin = ""
```

However, these entries are provided as empty "", and require explicit user configuration via override in the user `application.conf`.

* For an example of a journal plugin which writes messages to LevelDB see @ref:[Local LevelDB journal](#local-leveldb-journal).
* For an example of a snapshot store plugin which writes snapshots as individual files to the local filesystem see @ref:[Local snapshot store](#local-snapshot-store).

## Eager initialization of persistence plugin

By default, persistence plugins are started on-demand, as they are used. In some case, however, it might be beneficial
to start a certain plugin eagerly. In order to do that, you should first add `akka.persistence.Persistence`
under the `akka.extensions` key. Then, specify the IDs of plugins you wish to start automatically under
`akka.persistence.journal.auto-start-journals` and `akka.persistence.snapshot-store.auto-start-snapshot-stores`.

For example, if you want eager initialization for the leveldb journal plugin and the local snapshot store plugin, your configuration should look like this:  

```
akka {

  extensions = [akka.persistence.Persistence]

  persistence {

    journal {
      plugin = "akka.persistence.journal.leveldb"
      auto-start-journals = ["akka.persistence.journal.leveldb"]
    }

    snapshot-store {
      plugin = "akka.persistence.snapshot-store.local"
      auto-start-snapshot-stores = ["akka.persistence.snapshot-store.local"]
    }

  }

}
```

## Pre-packaged plugins

The Akka Persistence module comes with few built-in persistence plugins, but none of these are suitable
for production usage in an Akka Cluster. 

### Local LevelDB journal

This plugin writes events to a local LevelDB instance.

@@@ warning
The LevelDB plugin cannot be used in an Akka Cluster since the storage is in a local file system.
@@@ 

The LevelDB journal plugin config entry is `akka.persistence.journal.leveldb`. Enable this plugin by
defining config property:

@@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #leveldb-plugin-config }

LevelDB based plugins will also require the following additional dependency declaration:

@@dependency[sbt,Maven,Gradle] {
  group="org.fusesource.leveldbjni"
  artifact="leveldbjni-all"
  version="1.8"
}

The default location of LevelDB files is a directory named `journal` in the current working
directory. This location can be changed by configuration where the specified path can be relative or absolute:

@@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #journal-config }

With this plugin, each actor system runs its own private LevelDB instance.

One peculiarity of LevelDB is that the deletion operation does not remove messages from the journal, but adds
a "tombstone" for each deleted message instead. In the case of heavy journal usage, especially one including frequent
deletes, this may be an issue as users may find themselves dealing with continuously increasing journal sizes. To
this end, LevelDB offers a special journal compaction function that is exposed via the following configuration:

@@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #compaction-intervals-config }

### Shared LevelDB journal

For testing purposes a LevelDB instance can also be shared by multiple actor systems (on the same or on different nodes). This, for
example, allows persistent actors to failover to a backup node and continue using the shared journal instance from the
backup node.

@@@ warning
A shared LevelDB instance is a single point of failure and should therefore only be used for testing
purposes.
@@@

@@@ note
This plugin has been supplanted by @ref:[Persistence Plugin Proxy](#persistence-plugin-proxy).
@@@

A shared LevelDB instance is started by instantiating the `SharedLeveldbStore` actor.

Scala
:  @@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #shared-store-creation }

Java
:  @@snip [LambdaPersistencePluginDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistencePluginDocTest.java) { #shared-store-creation }

By default, the shared instance writes journaled messages to a local directory named `journal` in the current
working directory. The storage location can be changed by configuration:

@@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #shared-store-config }

Actor systems that use a shared LevelDB store must activate the `akka.persistence.journal.leveldb-shared`
plugin.

@@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #shared-journal-config }

This plugin must be initialized by injecting the (remote) `SharedLeveldbStore` actor reference. Injection is
done by calling the `SharedLeveldbJournal.setStore` method with the actor reference as argument.

Scala
:  @@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #shared-store-usage }

Java
:  @@snip [LambdaPersistencePluginDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistencePluginDocTest.java) { #shared-store-usage }

Internal journal commands (sent by persistent actors) are buffered until injection completes. Injection is idempotent
i.e. only the first injection is used.

### Local snapshot store

This plugin writes snapshot files to the local filesystem.

@@@ warning
The local snapshot store plugin cannot be used in an Akka Cluster since the storage is in a local file system.
@@@

The local snapshot store plugin config entry is `akka.persistence.snapshot-store.local`. 
Enable this plugin by defining config property:

@@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #leveldb-snapshot-plugin-config }

The default storage location is a directory named `snapshots` in the current working
directory. This can be changed by configuration where the specified path can be relative or absolute:

@@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #snapshot-config }

Note that it is not mandatory to specify a snapshot store plugin. If you don't use snapshots
you don't have to configure it.

### Persistence Plugin Proxy

For testing purposes a persistence plugin proxy allows sharing of journals and snapshot stores across multiple actor systems (on the same or
on different nodes). This, for example, allows persistent actors to failover to a backup node and continue using the
shared journal instance from the backup node. The proxy works by forwarding all the journal/snapshot store messages to a
single, shared, persistence plugin instance, and therefore supports any use case supported by the proxied plugin.

@@@ warning
A shared journal/snapshot store is a single point of failure and should therefore only be used for testing
purposes.
@@@

The journal and snapshot store proxies are controlled via the `akka.persistence.journal.proxy` and
`akka.persistence.snapshot-store.proxy` configuration entries, respectively. Set the `target-journal-plugin` or
`target-snapshot-store-plugin` keys to the underlying plugin you wish to use (for example:
`akka.persistence.journal.leveldb`). The `start-target-journal` and `start-target-snapshot-store` keys should be
set to `on` in exactly one actor system - this is the system that will instantiate the shared persistence plugin.
Next, the proxy needs to be told how to find the shared plugin. This can be done by setting the `target-journal-address`
and `target-snapshot-store-address` configuration keys, or programmatically by calling the
`PersistencePluginProxy.setTargetLocation` method.

@@@ note
Akka starts extensions lazily when they are required, and this includes the proxy. This means that in order for the
proxy to work, the persistence plugin on the target node must be instantiated. This can be done by instantiating the
`PersistencePluginProxyExtension` @ref:[extension](extending-akka.md), or by calling the `PersistencePluginProxy.start` method.
@@@

@@@ note
The proxied persistence plugin can (and should) be configured using its original configuration keys.
@@@
