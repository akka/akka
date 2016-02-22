name := "akka-sample-remote-scala"

version := "2.4-SNAPSHOT"

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4-SNAPSHOT",
  "com.typesafe.akka" %% "akka-remote" % "2.4-SNAPSHOT"
)

licenses := Seq(("CC0", url("http://creativecommons.org/publicdomain/zero/1.0")))
