import akka.{ AkkaBuild, Dependencies, Formatting, Unidoc }

AkkaBuild.defaultSettings

Formatting.formatSettings

Unidoc.scaladocSettings

publishArtifact in Compile := false

libraryDependencies ++= Dependencies.actorTests
