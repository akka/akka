# Coexistence

We believe Akka Typed will be adopted in existing systems gradually and therefore it's important to be able to use typed 
and untyped actors together, within the same `ActorSystem`. Also, we will not be able to integrate with all existing modules in one big bang release and that is another reason for why these two ways of writing actors must be able to coexist.

There are two different `ActorSystem`s: `akka.actor.ActorSystem` and `akka.actor.typed.ActorSystem`. 
The latter should only be used for greenfield projects that are only using typed actors. The `akka.actor.ActorSystem`
can be adapted so that it can create typed actors.

Typed and untyped can interact the following ways:

* untyped actor systems can create typed actors
* typed actors can send messages to untyped actors, and opposite
* spawn and supervise typed child from untyped parent, and opposite
* watch typed from untyped, and opposite

For all these examples the `akka.actor` package is aliased to `untyped`

Scala
:  @@snip [UntypedWatchingTypedSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/UntypedWatchingTypedSpec.scala) { #import-alias }

### Untyped to typed 

For the following typed actor here's how you create, watch and send messages to
it from an untyped actor.

Scala
:  @@snip [UntypedWatchingTypedSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/UntypedWatchingTypedSpec.scala) { #typed }

The top level untyped actor is created from an untyped actor system:

Scala
:  @@snip [UntypedWatchingTypedSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/UntypedWatchingTypedSpec.scala) { #create-untyped }


Then it can create a typed actor, watch it, and send a message to it:

Scala
:  @@snip [UntypedWatchingTypedSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/UntypedWatchingTypedSpec.scala) { #untyped-watch }


There is one `import` that is needed to make that work:

Scala
:  @@snip [UntypedWatchingTypedSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/UntypedWatchingTypedSpec.scala) { #adapter-import }


That adds some implicit extension methods that are added to untyped and typed `ActorSystem` and `ActorContext` in both directions. Note the inline comments in the example above. In the `javadsl` the corresponding adapter methods are static methods in `akka.typed.javadsl.Adapter`.

### Typed to untyped

Let's turn the example upside down and first start the typed actor and then the untyped as a child.

The following will show how to create, watch and send messages back and forth from a typed actor to this
untyped actor:

Scala
:  @@snip [TypedWatchingUntypedSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/TypedWatchingUntypedSpec.scala) { #untyped }

Creating the actor system and the typed actor:

Scala
:  @@snip [TypedWatchingUntypedSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/TypedWatchingUntypedSpec.scala) { #create }

Then the typed actor creates the untyped actor, watches it and sends and receives a response:

Scala
:  @@snip [TypedWatchingUntypedSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/TypedWatchingUntypedSpec.scala) { #typed }

There is one caveat regarding supervision of untyped child from typed parent. If the child throws an exception we would expect it to be restarted, but supervision in Akka Typed defaults to stopping the child in case it fails. The restarting facilities in Akka Typed will not work with untyped children. However, the workaround is simply to add another untyped actor that takes care of the supervision, i.e. restarts in case of failure if that is the desired behavior.


