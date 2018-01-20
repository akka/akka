# Fault Tolerance

The default supervision strategy is for the Actor be stopped. However that can be modified by wrapping behaviors in a call to `Behaviors.supervise` 
for example to restart on `IllegalStateExceptions`: 

Scala
:  @@snip [SupervisionCompileOnlyTest.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/supervision/SupervisionCompileOnlyTest.scala) { #restart }

Java
:  @@snip [SupervisionCompileOnlyTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/supervision/SupervisionCompileOnlyTest.java) { #restart }

Or to resume instead:

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


