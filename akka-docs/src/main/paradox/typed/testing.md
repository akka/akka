# Testing

@@@ note
For the Akka Classic documentation of this feature see @ref:[Classic Testing](../testing.md).
@@@

@@project-info{ projectId="akka-actor-testkit-typed" }

## Dependency

To use Actor TestKit add the module to your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-actor-testkit-typed_$scala.binary_version$
  version=$akka.version$
  scope=test
}

@@@div { .group-scala }

We recommend using Akka TestKit with ScalaTest:

@@dependency[sbt,Maven,Gradle] {
  group=org.scalatest
  artifact=scalatest_$scala.binary_version$
  version=$scalatest.version$
  scope=test
}

@@@

## Introduction

Testing can either be done asynchronously using a real @apidoc[akka.actor.typed.ActorSystem] or synchronously on the testing thread using the
@apidoc[typed.*.BehaviorTestKit].

For testing logic in a @apidoc[Behavior] in isolation synchronous testing is preferred, but the features that can be
tested are limited. For testing interactions between multiple actors a more realistic asynchronous test is preferred.

Those two testing approaches are described in:

@@toc { depth=2 }

@@@ index

* [Asynchronous testing](testing-async.md)
* [Synchronous behavior testing](testing-sync.md)

@@@

