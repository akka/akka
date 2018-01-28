# Fault Tolerance

When an actor throws an unexpected exception, a failure, while processing a message or during initialization, the actor will by default be stopped. Note that there is an important distinction between failures and validation errors:

A validation error means that the data of a command sent to an actor is not valid, this should rather be modelled as a part of the actor protocol than make the actor throw exceptions.

A failure is instead something unexpected or outside the control of the actor itself, for example a database connection that broke. Opposite to validation errors, it is seldom useful to model such as parts of the protocol as a sending actor very seldom can do anything useful about it. 

For failures it is useful to apply the "let it crash" philosophy: instead of mixing fine grained recovery and correction of internal state that may have become partially invalid because of the failure with the business logic we move that responsibility somewhere else. For many cases the resolution can then be to "crash" the actor, and start a new one, with a fresh state that we know is valid. 

In Akka Typed this "somewhere else" is called supervision. Supervision allows you to delaratively describe what should happen when a certain type of exceptions are thrown inside an actor. To use supervision the actual Actor behavior is wrapped using `Behaviors.supervise`, for example to restart on `IllegalStateExceptions`: 

Scala
:  @@snip [SupervisionCompileOnlyTest.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/supervision/SupervisionCompileOnlyTest.scala) { #restart }

Java
:  @@snip [SupervisionCompileOnlyTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/supervision/SupervisionCompileOnlyTest.java) { #restart }

Or to resume, ignore the failure and process the next message, instead:

Scala
:  @@snip [SupervisionCompileOnlyTest.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/supervision/SupervisionCompileOnlyTest.scala) { #resume }

Java
:  @@snip [SupervisionCompileOnlyTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/supervision/SupervisionCompileOnlyTest.java) { #resume }

More complicated restart strategies can be used e.g. to restart no more than 10
times in a 10 second period:

Scala
:  @@snip [SupervisionCompileOnlyTest.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/supervision/SupervisionCompileOnlyTest.scala) { #restart-limit }

Java
:  @@snip [SupervisionCompileOnlyTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/supervision/SupervisionCompileOnlyTest.java) { #restart-limit }

To handle different exceptions with different strategies calls to `supervise`
can be nested:

Scala
:  @@snip [SupervisionCompileOnlyTest.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/supervision/SupervisionCompileOnlyTest.scala) { #multiple }

Java
:  @@snip [SupervisionCompileOnlyTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/supervision/SupervisionCompileOnlyTest.java) { #multiple }

For a full list of strategies see the public methods on `SupervisorStrategy`


## Bubbling exceptions up

In some scenarios it may be useful to push the decision about what to do on a failure upwards in the Actor hierarchy
(in untyped Akka Actors this is how it works by default) and let the parent actor handle what should happen on failures.

// FIXME is it useful that we should introduce something for it perhaps?

// IDEA 1: parent will have to use watch, and act on children terminating
// problems: no idea what exception happened (maybe that is good, to not tightly couple parent and children)

// IDEA 2: provide a special supervision strategy that queries the parent, 
// put's the actor in limbo state until the parent has decided
// problems: maybe this is just complicating things and not actually so useful? 

// IDEA 3: recommend explicit protocol between parent and child about this
// IJustFailed(ex, childRef) and that the parent acts on that 
 
