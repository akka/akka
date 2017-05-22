# Actor DSL

@@@ warning

Actor DSL is deprecated and will be removed in the near future.
Use plain `system.actorOf` or `context.actorOf` instead.

@@@

## The Actor DSL

Simple actors—for example one-off workers or even when trying things out in the
REPL—can be created more concisely using the `Act` trait. The supporting
infrastructure is bundled in the following import:

@@snip [ActorDSLSpec.scala]($akka$/akka-actor-tests/src/test/scala/akka/actor/ActorDSLSpec.scala) { #import }

This import is assumed for all code samples throughout this section. The
implicit actor system serves as `ActorRefFactory` for all examples
below. To define a simple actor, the following is sufficient:

@@snip [ActorDSLSpec.scala]($akka$/akka-actor-tests/src/test/scala/akka/actor/ActorDSLSpec.scala) { #simple-actor }

Here, `actor` takes the role of either `system.actorOf` or
`context.actorOf`, depending on which context it is called in: it takes an
implicit `ActorRefFactory`, which within an actor is available in the
form of the `implicit val context: ActorContext`. Outside of an actor, you’ll
have to either declare an implicit `ActorSystem`, or you can give the
factory explicitly (see further below).

The two possible ways of issuing a `context.become` (replacing or adding the
new behavior) are offered separately to enable a clutter-free notation of
nested receives:

@@snip [ActorDSLSpec.scala]($akka$/akka-actor-tests/src/test/scala/akka/actor/ActorDSLSpec.scala) { #becomeStacked }

Please note that calling `unbecome` more often than `becomeStacked` results
in the original behavior being installed, which in case of the `Act`
trait is the empty behavior (the outer `become` just replaces it during
construction).

### Life-cycle management

Life-cycle hooks are also exposed as DSL elements (see @ref:[Start Hook](actors.md#start-hook) and @ref:[Stop Hook](actors.md#stop-hook)), where later invocations of the methods shown below will replace the contents of the respective hooks:

@@snip [ActorDSLSpec.scala]($akka$/akka-actor-tests/src/test/scala/akka/actor/ActorDSLSpec.scala) { #simple-start-stop }

The above is enough if the logical life-cycle of the actor matches the restart
cycles (i.e. `whenStopping` is executed before a restart and `whenStarting`
afterwards). If that is not desired, use the following two hooks (see @ref:[Restart Hooks](actors.md#restart-hook)):

@@snip [ActorDSLSpec.scala]($akka$/akka-actor-tests/src/test/scala/akka/actor/ActorDSLSpec.scala) { #failing-actor }

It is also possible to create nested actors, i.e. grand-children, like this:

@@snip [ActorDSLSpec.scala]($akka$/akka-actor-tests/src/test/scala/akka/actor/ActorDSLSpec.scala) { #nested-actor }

@@@ note

In some cases it will be necessary to explicitly pass the
`ActorRefFactory` to the `actor()` method (you will notice when
the compiler tells you about ambiguous implicits).

@@@

The grand-child will be supervised by the child; the supervisor strategy for
this relationship can also be configured using a DSL element (supervision
directives are part of the `Act` trait):

@@snip [ActorDSLSpec.scala]($akka$/akka-actor-tests/src/test/scala/akka/actor/ActorDSLSpec.scala) { #supervise-with }

### Actor with `Stash`

Last but not least there is a little bit of convenience magic built-in, which
detects if the runtime class of the statically given actor subtype extends the
`RequiresMessageQueue` trait via the `Stash` trait (this is a
complicated way of saying that `new Act with Stash` would not work because its
runtime erased type is just an anonymous subtype of `Act`). The purpose is to
automatically use the appropriate deque-based mailbox type required by `Stash`.
If you want to use this magic, simply extend `ActWithStash`:

@@snip [ActorDSLSpec.scala]($akka$/akka-actor-tests/src/test/scala/akka/actor/ActorDSLSpec.scala) { #act-with-stash }