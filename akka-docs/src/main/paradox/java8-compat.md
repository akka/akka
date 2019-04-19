# Java 8 Compatibility

Akka requires that you have [Java 8](http://www.oracle.com/technetwork/java/javase/downloads/index.html) or
later installed on your machine.

Starting with Akka 2.4.2 we have begun to introduce Java 8 types (most
prominently `java.util.concurrent.CompletionStage` and
`java.util.Optional`) where that was possible without breaking binary or
source compatibility. Where this was not possible (for example in the return
type of `ActorSystem.terminate()`) please refer to the
`scala-java8-compat` library that allows easy conversion between the Scala
and Java counterparts. The artifact can be included in Maven builds using:

```
<dependency>
  <groupId>org.scala-lang.modules</groupId>
  <artifactId>scala-java8-compat_2.12</artifactId>
  <version>0.7.0</version>
</dependency>
```

We will only be able to seamlessly integrate all functional interfaces once
we can rely on Scala 2.12 to provide full interoperability—this will mean that
Scala users can directly implement Java Functional Interfaces using lambda syntax
as well as that Java users can directly implement Scala functions using lambda
syntax.
