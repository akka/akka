name := "akka-sample-remote-java"

version := "2.5-M1"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.5-M1",
  "com.typesafe.akka" %% "akka-remote" % "2.5-M1"
)

licenses := Seq(("CC0", url("http://creativecommons.org/publicdomain/zero/1.0")))
