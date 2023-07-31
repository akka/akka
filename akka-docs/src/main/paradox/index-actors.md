# Classic Actors

@@include[includes.md](includes.md) { #actor-api }

## Dependency

The Akka dependencies are available from Akka's library repository. To access them there, you need to configure the URL for this repository.

@@repository [sbt,Maven,Gradle] {
id="akka-repository"
name="Akka library repository"
url="https://repo.akka.io/maven"
}

To use Classic Akka Actors, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  bomGroup=com.typesafe.akka bomArtifact=akka-bom_$scala.binary.version$ bomVersionSymbols=AkkaVersion
  symbol1=AkkaVersion
  value1="$akka.version$"
  group="com.typesafe.akka"
  artifact="akka-actor_$scala.binary.version$"
  version=AkkaVersion
  group2="com.typesafe.akka"
  artifact2="akka-testkit_$scala.binary.version$"
  scope2=test
  version2=AkkaVersion
}

@@toc { depth=2 }

@@@ index

* [actors](actors.md)
* [supervision overview](supervision-classic.md)
* [fault-tolerance](fault-tolerance.md)
* [dispatchers](dispatchers.md)
* [mailboxes](mailboxes.md)
* [routing](routing.md)
* [fsm](fsm.md)
* [persistence](persistence.md)
* [persistence-fsm](persistence-fsm.md)
* [testing](testing.md)

@@@
