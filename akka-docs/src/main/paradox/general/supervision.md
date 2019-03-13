# Supervision and Monitoring

This chapter outlines the concept behind supervision, the primitives offered
and their semantics. For details on how that translates into real code, please
refer to the corresponding chapters for Scala and Java APIs.

## Sample project

You can look at the
@extref[Supervision example project](samples:akka-samples-supervision-java)
to see what this looks like in practice.

<a id="supervision-directives"></a>
## What Supervision Means

As described in @ref:[Actor Systems](actor-systems.md) supervision describes a dependency
relationship between actors: the supervisor delegates tasks to subordinates and
therefore must respond to their failures.  When a subordinate detects a failure
(i.e. throws an exception), it suspends itself and all its subordinates and
sends a message to its supervisor, signaling failure.  Depending on the nature
of the work to be supervised and the nature of the failure, the supervisor has
a choice of the following four options:

 1. Resume the subordinate, keeping its accumulated internal state
 2. Restart the subordinate, clearing out its accumulated internal state
 3. Stop the subordinate permanently
 4. Escalate the failure, thereby failing itself

It is important to always view an actor as part of a supervision hierarchy,
which explains the existence of the fourth choice (as a supervisor also is
subordinate to another supervisor higher up) and has implications on the first
three: resuming an actor resumes all its subordinates, restarting an actor
entails restarting all its subordinates (but see below for more details),
similarly terminating an actor will also terminate all its subordinates. It
should be noted that the default behavior of the `preRestart` hook of the
`Actor` class is to terminate all its children before restarting, but
this hook can be overridden; the recursive restart applies to all children left
after this hook has been executed.

Each supervisor is configured with a function translating all possible failure
causes (i.e. exceptions) into one of the four choices given above; notably,
this function does not take the failed actor’s identity as an input. It is
quite easy to come up with examples of structures where this might not seem
flexible enough, e.g. wishing for different strategies to be applied to
different subordinates. At this point it is vital to understand that
supervision is about forming a recursive fault handling structure. If you try
to do too much at one level, it will become hard to reason about, hence the
recommended way in this case is to add a level of supervision.

Akka implements a specific form called “parental supervision”. Actors can only
be created by other actors—where the top-level actor is provided by the
library—and each created actor is supervised by its parent. This restriction
makes the formation of actor supervision hierarchies implicit and encourages
sound design decisions. It should be noted that this also guarantees that
actors cannot be orphaned or attached to supervisors from the outside, which
might otherwise catch them unawares. In addition, this yields a natural and
clean shutdown procedure for (sub-trees of) actor applications.

@@@ warning

Supervision related parent-child communication happens by special system
messages that have their own mailboxes separate from user messages. This
implies that supervision related events are not deterministically
ordered relative to ordinary messages. In general, the user cannot influence
the order of normal messages and failure notifications. For details and
example see the @ref:[Discussion: Message Ordering](message-delivery-reliability.md#message-ordering) section.

@@@

<a id="toplevel-supervisors"></a>
## The Top-Level Supervisors

![guardians.png](guardians.png)

An actor system will during its creation start at least three actors, shown in
the image above. For more information about the consequences for actor paths
see @ref:[Top-Level Scopes for Actor Paths](addressing.md#toplevel-paths).

<a id="user-guardian"></a>
### `/user`: The Guardian Actor

The actor which is probably most interacted with is the parent of all
user-created actors, the guardian named `"/user"`. Actors created using
`system.actorOf()` are children of this actor. This means that when this
guardian terminates, all normal actors in the system will be shutdown, too. It
also means that this guardian’s supervisor strategy determines how the
top-level normal actors are supervised. Since Akka 2.1 it is possible to
configure this using the setting `akka.actor.guardian-supervisor-strategy`,
which takes the fully-qualified class-name of a
`SupervisorStrategyConfigurator`. When the guardian escalates a failure,
the root guardian’s response will be to terminate the guardian, which in effect
will shut down the whole actor system.

### `/system`: The System Guardian

This special guardian has been introduced in order to achieve an orderly
shut-down sequence where logging remains active while all normal actors
terminate, even though logging itself is implemented using actors. This is
realized by having the system guardian watch the user guardian and initiate its own
shut-down upon reception of the `Terminated` message. The top-level
system actors are supervised using a strategy which will restart indefinitely
upon all types of `Exception` except for
`ActorInitializationException` and `ActorKilledException`, which
will terminate the child in question.  All other throwables are escalated,
which will shut down the whole actor system.

### `/`: The Root Guardian

The root guardian is the grand-parent of all so-called “top-level” actors and
supervises all the special actors mentioned in @ref:[Top-Level Scopes for Actor Paths](addressing.md#toplevel-paths) using the
`SupervisorStrategy.stoppingStrategy`, whose purpose is to terminate the
child upon any type of `Exception`. All other throwables will be
escalated … but to whom? Since every real actor has a supervisor, the
supervisor of the root guardian cannot be a real actor. And because this means
that it is “outside of the bubble”, it is called the “bubble-walker”. This is a
synthetic `ActorRef` which in effect stops its child upon the first sign
of trouble and sets the actor system’s `isTerminated` status to `true` as
soon as the root guardian is fully terminated (all children recursively
stopped).

<a id="supervision-restart"></a>
## What Restarting Means

When presented with an actor which failed while processing a certain message,
causes for the failure fall into three categories:

 * Systematic (i.e. programming) error for the specific message received
 * (Transient) failure of some external resource used during processing the message
 * Corrupt internal state of the actor

Unless the failure is specifically recognizable, the third cause cannot be
ruled out, which leads to the conclusion that the internal state needs to be
cleared out. If the supervisor decides that its other children or itself is not
affected by the corruption—e.g. because of conscious application of the error
kernel pattern—it is therefore best to restart the child. This is carried out
by creating a new instance of the underlying `Actor` class and replacing
the failed instance with the fresh one inside the child’s `ActorRef`;
the ability to do this is one of the reasons for encapsulating actors within
special references. The new actor then resumes processing its mailbox, meaning
that the restart is not visible outside of the actor itself with the notable
exception that the message during which the failure occurred is not
re-processed.

The precise sequence of events during a restart is the following:

 1. suspend the actor (which means that it will not process normal messages until
resumed), and recursively suspend all children
 2. call the old instance’s `preRestart` hook (defaults to sending
termination requests to all children and calling `postStop`)
 3. wait for all children which were requested to terminate (using
`context.stop()`) during `preRestart` to actually terminate;
this—like all actor operations—is non-blocking, the termination notice from
the last killed child will effect the progression to the next step
 4. create new actor instance by invoking the originally provided factory again
 5. invoke `postRestart` on the new instance (which by default also calls `preStart`)
 6. send restart request to all children which were not killed in step 3;
restarted children will follow the same process recursively, from step 2
 7. resume the actor

## What Lifecycle Monitoring Means

@@@ note

Lifecycle Monitoring in Akka is usually referred to as `DeathWatch`

@@@

In contrast to the special relationship between parent and child described
above, each actor may monitor any other actor. Since actors emerge from
creation fully alive and restarts are not visible outside of the affected
supervisors, the only state change available for monitoring is the transition
from alive to dead. Monitoring is thus used to tie one actor to another so that
it may react to the other actor’s termination, in contrast to supervision which
reacts to failure.

Lifecycle monitoring is implemented using a `Terminated` message to be
received by the monitoring actor, where the default behavior is to throw a
special `DeathPactException` if not otherwise handled. In order to start
listening for `Terminated` messages, invoke
`ActorContext.watch(targetActorRef)`.  To stop listening, invoke
`ActorContext.unwatch(targetActorRef)`.  One important property is that the
message will be delivered irrespective of the order in which the monitoring
request and target’s termination occur, i.e. you still get the message even if
at the time of registration the target is already dead.

Monitoring is particularly useful if a supervisor cannot restart its
children and has to terminate them, e.g. in case of errors during actor
initialization. In that case it should monitor those children and re-create
them or schedule itself to retry this at a later time.

Another common use case is that an actor needs to fail in the absence of an
external resource, which may also be one of its own children. If a third party
terminates a child by way of the `system.stop(child)` method or sending a
`PoisonPill`, the supervisor might well be affected.

<a id="backoff-supervisor"></a>
### Delayed restarts with the BackoffSupervisor pattern

Provided as a built-in pattern the `akka.pattern.BackoffSupervisor` implements the so-called
*exponential backoff supervision strategy*, starting a child actor again when it fails, each time with a growing time delay between restarts.

This pattern is useful when the started actor fails <a id="^1" href="#1">[1]</a> because some external resource is not available,
and we need to give it some time to start-up again. One of the prime examples when this is useful is
when a @ref:[PersistentActor](../persistence.md) fails (by stopping) with a persistence failure - which indicates that
the database may be down or overloaded, in such situations it makes most sense to give it a little bit of time
to recover before the persistent actor is started.

> <a id="1" href="#^1">[1]</a> A failure can be indicated in two different ways; by an actor stopping or crashing.

<a id="supervision-strategies"></a>
#### Supervision strategies

There are two basic supervision strategies available for backoff:
* 'On failure': The supervisor will terminate and then start the supervised actor if it crashes. If the supervised actor stops normally (e.g. through `context.stop`), the supervisor will be terminated and no further attempt to start the supervised actor will be done.
* 'On stop': The supervisor will terminate and then start the supervised actor if it terminates in any way (consider this for `PersistentActor` since they stop on persistence failures instead of crashing)

To note that this supervision strategy does not restart the actor but rather stops and starts it. Be aware of it if you 
use @scala[`Stash` trait’s] @java[`AbstractActorWithStash`] in combination with the backoff supervision strategy.
The `preRestart` hook will not be executed if the supervised actor fails or stops and you will miss the opportunity
to unstash the messages.

#### Sharding
If the 'on stop' strategy is used for sharded actors a final termination message should be configured and used to terminate the actor on passivation. Otherwise the supervisor will just stop and start the actor again.

The termination message is configured with:

@@snip [BackoffSupervisorDocSpec.scala](/akka-docs/src/test/scala/docs/pattern/BackoffSupervisorDocSpec.scala) { #backoff-sharded }

And must be used for passivation:

@@snip [BackoffSupervisorDocSpec.scala](/akka-docs/src/test/scala/docs/pattern/BackoffSupervisorDocSpec.scala) { #backoff-sharded-passivation }

#### Simple backoff

The following Scala snippet shows how to create a backoff supervisor which will start the given echo actor after it has stopped
because of a failure, in increasing intervals of 3, 6, 12, 24 and finally 30 seconds:

@@snip [BackoffSupervisorDocSpec.scala](/akka-docs/src/test/scala/docs/pattern/BackoffSupervisorDocSpec.scala) { #backoff-stop }

The above is equivalent to this Java code:

@@snip [BackoffSupervisorDocTest.java](/akka-docs/src/test/java/jdocs/pattern/BackoffSupervisorDocTest.java) { #backoff-imports }

@@snip [BackoffSupervisorDocTest.java](/akka-docs/src/test/java/jdocs/pattern/BackoffSupervisorDocTest.java) { #backoff-stop }

Using a `randomFactor` to add a little bit of additional variance to the backoff intervals
is highly recommended, in order to avoid multiple actors re-start at the exact same point in time,
for example because they were stopped due to a shared resource such as a database going down
and re-starting after the same configured interval. By adding additional randomness to the
re-start intervals the actors will start in slightly different points in time, thus avoiding
large spikes of traffic hitting the recovering shared database or other resource that they all need to contact.

The `akka.pattern.BackoffSupervisor` actor can also be configured to stop and start the actor after a delay when the actor 
crashes and the supervision strategy decides that it should restart.

The following Scala snippet shows how to create a backoff supervisor which will start the given echo actor after it has crashed
because of some exception, in increasing intervals of 3, 6, 12, 24 and finally 30 seconds:

@@snip [BackoffSupervisorDocSpec.scala](/akka-docs/src/test/scala/docs/pattern/BackoffSupervisorDocSpec.scala) { #backoff-fail }

The above is equivalent to this Java code:

@@snip [BackoffSupervisorDocTest.java](/akka-docs/src/test/java/jdocs/pattern/BackoffSupervisorDocTest.java) { #backoff-imports }

@@snip [BackoffSupervisorDocTest.java](/akka-docs/src/test/java/jdocs/pattern/BackoffSupervisorDocTest.java) { #backoff-fail }

#### Customization

The `akka.pattern.BackoffOnFailureOptions` and `akka.pattern.BackoffOnRestartOptions` can be used to customize the behavior of the back-off supervisor actor.
Options are:
* `withAutoReset`: The backoff is reset if no failure/stop occurs within the duration. This is the default behaviour with `minBackoff` as default value
* `withManualReset`: The child must send `BackoffSupervisor.Reset` to its backoff supervisor (parent)
* `withSupervisionStrategy`: Sets a custom `OneForOneStrategy` (as each backoff supervisor only has one child). The default strategy uses the `akka.actor.SupervisorStrategy.defaultDecider` which stops and starts the child on exceptions.
* `withMaxNrOfRetries`: Sets the maximum number of retries until the supervisor will give up (`-1` is default which means no limit of retries). Note: This is set on the supervision strategy, so setting a different strategy resets the `maxNrOfRetries`.
* `withReplyWhileStopped`: By default all messages received while the child is stopped are forwarded to dead letters. With this set, the supervisor will reply to the sender instead.

Only available on `BackoffOnStopOptions`:
* `withDefaultStoppingStrategy`: Sets a `OneForOneStrategy` with the stopping decider that stops the child on all exceptions.
* `withFinalStopMessage`: Allows to define a predicate to decide on finally stopping the child (and supervisor). Used for passivate sharded actors - see above.

Some examples:

@@snip [BackoffSupervisorDocSpec.scala](/akka-docs/src/test/scala/docs/pattern/BackoffSupervisorDocSpec.scala) { #backoff-custom-stop }

The above code sets up a back-off supervisor that requires the child actor to send a `akka.pattern.BackoffSupervisor.Reset` message
to its parent when a message is successfully processed, resetting the back-off. It also uses a default stopping strategy, any exception
will cause the child to stop.

@@snip [BackoffSupervisorDocSpec.scala](/akka-docs/src/test/scala/docs/pattern/BackoffSupervisorDocSpec.scala) { #backoff-custom-fail }

The above code sets up a back-off supervisor that stops and starts the child after back-off if MyException is thrown, any other exception will be
escalated. The back-off is automatically reset if the child does not throw any errors within 10 seconds.

## One-For-One Strategy vs. All-For-One Strategy

There are two classes of supervision strategies which come with Akka:
`OneForOneStrategy` and `AllForOneStrategy`. Both are configured
with a mapping from exception type to supervision directive (see
[above](#supervision-directives)) and limits on how often a child is allowed to fail
before terminating it. The difference between them is that the former applies
the obtained directive only to the failed child, whereas the latter applies it
to all siblings as well. Normally, you should use the
`OneForOneStrategy`, which also is the default if none is specified
explicitly.

The `AllForOneStrategy` is applicable in cases where the ensemble of
children has such tight dependencies among them, that a failure of one child
affects the function of the others, i.e. they are inextricably linked. Since a
restart does not clear out the mailbox, it often is best to terminate the children
upon failure and re-create them explicitly from the supervisor (by watching the
children’s lifecycle); otherwise you have to make sure that it is no problem
for any of the actors to receive a message which was queued before the restart
but processed afterwards.

Normally stopping a child (i.e. not in response to a failure) will not
automatically terminate the other children in an all-for-one strategy; this can
be done by watching their lifecycle: if the `Terminated` message
is not handled by the supervisor, it will throw a `DeathPactException`
which (depending on its supervisor) will restart it, and the default
`preRestart` action will terminate all children. Of course this can be
handled explicitly as well.

Please note that creating one-off actors from an all-for-one supervisor entails
that failures escalated by the temporary actor will affect all the permanent
ones. If this is not desired, install an intermediate supervisor; this can very
be done by declaring a router of size 1 for the worker, see
@ref:[Routing](../routing.md).
