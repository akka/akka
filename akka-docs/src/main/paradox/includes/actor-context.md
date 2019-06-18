
<!--- #actor-context-typed-access --->
## Accessing the ActorContext

If the behavior needs to use the `ActorContext`, for example to spawn child actors, or use
@scala[`context.self`]@java[`context.getSelf()`], it can be obtained by wrapping construction with `Behaviors.setup`:

Scala
:  @@snip [BasicPersistentBehaviorCompileOnly.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorCompileOnly.scala) { #actor-context }

Java
:  @@snip [BasicPersistentBehaviorTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorTest.java) { #actor-context }

<!--- #actor-context-typed-access --->
 