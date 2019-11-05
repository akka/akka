# Coordinated Shutdown

Under normal conditions when `ActorSystem` is terminated or the JVM process is shut down certain
actors and services will be stopped in a specific order. 

This is handled by an extension named `CoordinatedShutdown`. It will run the registered tasks
during the shutdown process. The order of the shutdown phases is defined in configuration `akka.coordinated-shutdown.phases`.
The default phases are defined as:

@@snip [reference.conf](/akka-actor/src/main/resources/reference.conf) { #coordinated-shutdown-phases }

More phases can be added in the application's configuration if needed by overriding a phase with an
additional `depends-on`. Especially the phases `before-service-unbind`, `before-cluster-shutdown` and
`before-actor-system-terminate` are intended for application specific phases or tasks.

The default phases are defined in a single linear order, but the phases can be ordered as a
directed acyclic graph (DAG) by defining the dependencies between the phases.
The phases are ordered with [topological](https://en.wikipedia.org/wiki/Topological_sorting) sort of the DAG.

Tasks can be added to a phase with:

Scala
:  @@snip [ActorDocSpec.scala](/akka-docs/src/test/scala/docs/actor/ActorDocSpec.scala) { #coordinated-shutdown-addTask }

Java
:  @@snip [ActorDocTest.java](/akka-docs/src/test/java/jdocs/actor/ActorDocTest.java) { #coordinated-shutdown-addTask }

If cancellation of previously added tasks is required:

Scala
:  @@snip [ActorDocSpec.scala](/akka-docs/src/test/scala/docs/actor/ActorDocSpec.scala) { #coordinated-shutdown-cancellable }

Java
:  @@snip [ActorDocTest.java](/akka-docs/src/test/java/jdocs/actor/ActorDocTest.java) { #coordinated-shutdown-cancellable }

The returned @scala[`Future[Done]`] @java[`CompletionStage<Done>`] should be completed when the task is completed. The task name parameter
is only used for debugging/logging.

Tasks added to the same phase are executed in parallel without any ordering assumptions.
Next phase will not start until all tasks of previous phase have been completed.

If tasks are not completed within a configured timeout (see @ref:[reference.conf](general/configuration-reference.md#config-akka-actor))
the next phase will be started anyway. It is possible to configure `recover=off` for a phase
to abort the rest of the shutdown process if a task fails or is not completed within the timeout.

In the above example, it may be more convenient to simply stop the actor when it's done shutting down, rather than send back a done message,
and for the shutdown task to not complete until the actor is terminated. A convenience method is provided that adds a task that sends
a message to the actor and then watches its termination:

Scala
:  @@snip [ActorDocSpec.scala](/akka-docs/src/test/scala/docs/actor/ActorDocSpec.scala) { #coordinated-shutdown-addActorTerminationTask }

Java
:  @@snip [ActorDocTest.java](/akka-docs/src/test/java/jdocs/actor/ActorDocTest.java) { #coordinated-shutdown-addActorTerminationTask }

Tasks should typically be registered as early as possible after system startup. When running
the coordinated shutdown tasks that have been registered will be performed but tasks that are
added too late will not be run.

To start the coordinated shutdown process you can invoke @scala[`run`] @java[`runAll`] on the `CoordinatedShutdown`
extension:

Scala
:  @@snip [ActorDocSpec.scala](/akka-docs/src/test/scala/docs/actor/ActorDocSpec.scala) { #coordinated-shutdown-run }

Java
:  @@snip [ActorDocTest.java](/akka-docs/src/test/java/jdocs/actor/ActorDocTest.java) { #coordinated-shutdown-run }

It's safe to call the @scala[`run`] @java[`runAll`] method multiple times. It will only run once.

That also means that the `ActorSystem` will be terminated in the last phase. By default, the
JVM is not forcefully stopped (it will be stopped if all non-daemon threads have been terminated).
To enable a hard `System.exit` as a final action you can configure:

```
akka.coordinated-shutdown.exit-jvm = on
```

The coordinated shutdown process can also be started by calling `ActorSystem.terminate()`.

When using @ref:[Akka Cluster](cluster-usage.md) the `CoordinatedShutdown` will automatically run
when the cluster node sees itself as `Exiting`, i.e. leaving from another node will trigger
the shutdown process on the leaving node. Tasks for graceful leaving of cluster including graceful
shutdown of Cluster Singletons and Cluster Sharding are added automatically when Akka Cluster is used,
i.e. running the shutdown process will also trigger the graceful leaving if it's not already in progress.

By default, the `CoordinatedShutdown` will be run when the JVM process exits, e.g.
via `kill SIGTERM` signal (`SIGINT` ctrl-c doesn't work). This behavior can be disabled with:

```
akka.coordinated-shutdown.run-by-jvm-shutdown-hook=off
```

If you have application specific JVM shutdown hooks it's recommended that you register them via the
`CoordinatedShutdown` so that they are running before Akka internal shutdown hooks, e.g.
those shutting down Akka Remoting (Artery).

Scala
:  @@snip [ActorDocSpec.scala](/akka-docs/src/test/scala/docs/actor/ActorDocSpec.scala) { #coordinated-shutdown-jvm-hook }

Java
:  @@snip [ActorDocTest.java](/akka-docs/src/test/java/jdocs/actor/ActorDocTest.java) { #coordinated-shutdown-jvm-hook }

For some tests it might be undesired to terminate the `ActorSystem` via `CoordinatedShutdown`.
You can disable that by adding the following to the configuration of the `ActorSystem` that is
used in the test:

```
# Don't terminate ActorSystem via CoordinatedShutdown in tests
akka.coordinated-shutdown.terminate-actor-system = off
akka.coordinated-shutdown.run-by-actor-system-terminate = off
akka.coordinated-shutdown.run-by-jvm-shutdown-hook = off
akka.cluster.run-coordinated-shutdown-when-down = off
```
