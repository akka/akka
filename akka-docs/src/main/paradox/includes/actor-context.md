
<!--- #actor-context-typed-access --->
## Accessing the ActorContext

The ActorContext can be accessed for many purposes such as:

* Spawning child actors and supervision
* Watching other actors (`DeathWatch`) to receive a `Terminated(otherActor)` event should the watched actor stop permanently
* Logging
* Creating message adapters
* Request-response interactions (ask) with another actor
* Access to the `self` ActorRef

If a behavior needs to use the `ActorContext`, for example to spawn child actors, or use
@scala[`context.self`]@java[`context.getSelf()`], it can be obtained by wrapping construction with `Behaviors.setup`:

Scala
:  @@snip [BasicPersistentBehaviorCompileOnly.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorCompileOnly.scala) { #actor-context }

Java
:  @@snip [BasicPersistentBehaviorTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorTest.java) { #actor-context }

### ActorContext Thread Safety

Many of the methods in `ActorContext` are not thread-safe and

* Must not be accessed from threads from@scala[`scala.concurrent.Future`]@java[`java.util.concurrent.CompletionStage`] callbacks  
* Must not be shared between several actor instances
* Should only be used in the ordinary actor message processing thread

<!--- #actor-context-typed-access --->
 