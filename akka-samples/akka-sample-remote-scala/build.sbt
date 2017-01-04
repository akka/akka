name := "akka-sample-remote-scala"

version := "2.5-SNAPSHOT"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.5-SNAPSHOT",
  "com.typesafe.akka" %% "akka-remote" % "2.5-SNAPSHOT"
)

licenses := Seq(("CC0", url("http://creativecommons.org/publicdomain/zero/1.0")))
