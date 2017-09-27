# Fault Tolerance

As explained in @ref:[Actor Systems](general/actor-systems.md) each actor is the supervisor of its
children, and as such each actor defines fault handling supervisor strategy.
This strategy cannot be changed afterwards as it is an integral part of the
actor system’s structure.

## Fault Handling in Practice

First, let us look at a sample that illustrates one way to handle data store errors,
which is a typical source of failure in real world applications. Of course it depends
on the actual application what is possible to do when the data store is unavailable,
but in this sample we use a best effort re-connect approach.

Read the following source code. The inlined comments explain the different pieces of
the fault handling and why they are added. It is also highly recommended to run this
sample as it is easy to follow the log output to understand what is happening at runtime.

## Creating a Supervisor Strategy

The following sections explain the fault handling mechanism and alternatives
in more depth.

For the sake of demonstration let us consider the following strategy:

Scala
:  @@snip [FaultHandlingDocSpec.scala]($code$/scala/docs/actor/FaultHandlingDocSpec.scala) { #strategy }

Java
:  @@snip [FaultHandlingTest.java]($code$/java/jdocs/actor/FaultHandlingTest.java) { #strategy }

We have chosen a few well-known exception types in order to demonstrate the
application of the fault handling directives described in @ref:[supervision](general/supervision.md).
First off, it is a one-for-one strategy, meaning that each child is treated
separately (an all-for-one strategy works very similarly, the only difference
is that any decision is applied to all children of the supervisor, not only the
failing one). 
In the above example, `10` and @scala[`1 minute`]@java[`Duration.create(1, TimeUnit.MINUTES)`] are passed to the `maxNrOfRetries`
and `withinTimeRange` parameters respectively, which means that the strategy restarts a child up to 10 restarts per minute.
The child actor is stopped if the restart count exceeds `maxNrOfRetries` during the `withinTimeRange` duration.

Also, there are special values for these parameters. If you specify:

* `-1` to `maxNrOfRetries`, and @scala[`Duration.inf`]@java[`Duration.Inf()`] to `withinTimeRange`
    * then the child is always restarted without any limit
* `-1` to `maxNrOfRetries`, and a non-infinite `Duration` to `withinTimeRange` 
    * `maxNrOfRetries` is treated as `1`
* a non-negative number to `maxNrOfRetries` and @scala[`Duration.inf`]@java[`Duration.Inf()`] to `withinTimeRange`
    * `withinTimeRange` is treated as infinite duration (i.e.) no matter how long it takes, once the restart count exceeds `maxNrOfRetries`, the child actor is stopped  
   
The match statement which forms the bulk of the body   
@scala[is of type `Decider` which is a `PartialFunction[Throwable, Directive]`.]
@java[consists of `PFBuilder` returned by `DeciderBuilder`'s `match` method, where the builder is finished by the `build` method.]
This is the piece which maps child failure types to their corresponding directives.

@@@ note

If the strategy is declared inside the supervising actor (as opposed to
@scala[within a companion object]@java[a separate class]) its decider has access to all internal state of
the actor in a thread-safe fashion, including obtaining a reference to the
currently failed child (available as the @scala[`sender`]@java[`getSender()`] of the failure message).

@@@

### Default Supervisor Strategy

`Escalate` is used if the defined strategy doesn't cover the exception that was thrown.

When the supervisor strategy is not defined for an actor the following
exceptions are handled by default:

 * `ActorInitializationException` will stop the failing child actor
 * `ActorKilledException` will stop the failing child actor
 * `DeathPactException` will stop the failing child actor
 * `Exception` will restart the failing child actor
 * Other types of `Throwable` will be escalated to parent actor

If the exception escalate all the way up to the root guardian it will handle it
in the same way as the default strategy defined above.

@@@ div { .group-scala }

You can combine your own strategy with the default strategy:

@@snip [FaultHandlingDocSpec.scala]($code$/scala/docs/actor/FaultHandlingDocSpec.scala) { #default-strategy-fallback }

@@@

### Stopping Supervisor Strategy

Closer to the Erlang way is the strategy to just stop children when they fail
and then take corrective action in the supervisor when DeathWatch signals the
loss of the child. This strategy is also provided pre-packaged as
`SupervisorStrategy.stoppingStrategy` with an accompanying
`StoppingSupervisorStrategy` configurator to be used when you want the
`"/user"` guardian to apply it.

### Logging of Actor Failures

By default the `SupervisorStrategy` logs failures unless they are escalated.
Escalated failures are supposed to be handled, and potentially logged, at a level
higher in the hierarchy.

You can mute the default logging of a `SupervisorStrategy` by setting
`loggingEnabled` to `false` when instantiating it. Customized logging
can be done inside the `Decider`. Note that the reference to the currently
failed child is available as the `sender` when the `SupervisorStrategy` is
declared inside the supervising actor.

You may also customize the logging in your own `SupervisorStrategy` implementation
by overriding the `logFailure` method.

## Supervision of Top-Level Actors

Toplevel actors means those which are created using `system.actorOf()`, and
they are children of the @ref:[User Guardian](general/supervision.md#user-guardian). There are no
special rules applied in this case, the guardian simply applies the configured
strategy.

## Test Application

The following section shows the effects of the different directives in practice,
where a test setup is needed. First off, we need a suitable supervisor:

Scala
:  @@snip [FaultHandlingDocSpec.scala]($code$/scala/docs/actor/FaultHandlingDocSpec.scala) { #supervisor }

Java
:  @@snip [FaultHandlingTest.java]($code$/java/jdocs/actor/FaultHandlingTest.java) { #supervisor }

This supervisor will be used to create a child, with which we can experiment:

Scala
:  @@snip [FaultHandlingDocSpec.scala]($code$/scala/docs/actor/FaultHandlingDocSpec.scala) { #child }

Java
:  @@snip [FaultHandlingTest.java]($code$/java/jdocs/actor/FaultHandlingTest.java) { #child }

The test is easier by using the utilities described in @scala[@ref:[Testing Actor Systems](testing.md)]@java[@ref:[TestKit](testing.md)],
where `TestProbe` provides an actor ref useful for receiving and inspecting replies.

Scala
:  @@snip [FaultHandlingDocSpec.scala]($code$/scala/docs/actor/FaultHandlingDocSpec.scala) { #testkit }

Java
:  @@snip [FaultHandlingTest.java]($code$/java/jdocs/actor/FaultHandlingTest.java) { #testkit }

Let us create actors:

Scala
:  @@snip [FaultHandlingDocSpec.scala]($code$/scala/docs/actor/FaultHandlingDocSpec.scala) { #create }

Java
:  @@snip [FaultHandlingTest.java]($code$/java/jdocs/actor/FaultHandlingTest.java) { #create }

The first test shall demonstrate the `Resume` directive, so we try it out by
setting some non-initial state in the actor and have it fail:

Scala
:  @@snip [FaultHandlingDocSpec.scala]($code$/scala/docs/actor/FaultHandlingDocSpec.scala) { #resume }

Java
:  @@snip [FaultHandlingTest.java]($code$/java/jdocs/actor/FaultHandlingTest.java) { #resume }

As you can see the value 42 survives the fault handling directive. Now, if we
change the failure to a more serious `NullPointerException`, that will no
longer be the case:

Scala
:  @@snip [FaultHandlingDocSpec.scala]($code$/scala/docs/actor/FaultHandlingDocSpec.scala) { #restart }

Java
:  @@snip [FaultHandlingTest.java]($code$/java/jdocs/actor/FaultHandlingTest.java) { #restart }

And finally in case of the fatal `IllegalArgumentException` the child will be
terminated by the supervisor:

Scala
:  @@snip [FaultHandlingDocSpec.scala]($code$/scala/docs/actor/FaultHandlingDocSpec.scala) { #stop }

Java
:  @@snip [FaultHandlingTest.java]($code$/java/jdocs/actor/FaultHandlingTest.java) { #stop }

Up to now the supervisor was completely unaffected by the child’s failure,
because the directives set did handle it. In case of an `Exception`, this is not
true anymore and the supervisor escalates the failure.

Scala
:  @@snip [FaultHandlingDocSpec.scala]($code$/scala/docs/actor/FaultHandlingDocSpec.scala) { #escalate-kill }

Java
:  @@snip [FaultHandlingTest.java]($code$/java/jdocs/actor/FaultHandlingTest.java) { #escalate-kill }

The supervisor itself is supervised by the top-level actor provided by the
`ActorSystem`, which has the default policy to restart in case of all
`Exception` cases (with the notable exceptions of
`ActorInitializationException` and `ActorKilledException`). Since the
default directive in case of a restart is to kill all children, we expected our poor
child not to survive this failure.

In case this is not desired (which depends on the use case), we need to use a
different supervisor which overrides this behavior.

Scala
:  @@snip [FaultHandlingDocSpec.scala]($code$/scala/docs/actor/FaultHandlingDocSpec.scala) { #supervisor2 }

Java
:  @@snip [FaultHandlingTest.java]($code$/java/jdocs/actor/FaultHandlingTest.java) { #supervisor2 }

With this parent, the child survives the escalated restart, as demonstrated in
the last test:

Scala
:  @@snip [FaultHandlingDocSpec.scala]($code$/scala/docs/actor/FaultHandlingDocSpec.scala) { #escalate-restart }

Java
:  @@snip [FaultHandlingTest.java]($code$/java/jdocs/actor/FaultHandlingTest.java) { #escalate-restart }
