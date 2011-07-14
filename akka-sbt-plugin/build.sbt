
sbtPlugin := true

organization := "se.scalablesolutions.akka"

name := "akka-sbt-plugin"

version := "2.0-SNAPSHOT"

publishMavenStyle := true

publishTo := Some("Typesafe Publish Repo" at "http://repo.typesafe.com/typesafe/maven-releases/")

credentials += Credentials(Path.userHome / ".ivy2" / "typesafe-credentials")
