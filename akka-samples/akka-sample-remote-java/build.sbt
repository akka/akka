name := "akka-sample-remote-java"

version := "2.4.9-RC2"

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4.9-RC2",
  "com.typesafe.akka" %% "akka-remote" % "2.4.9-RC2"
)

licenses := Seq(("CC0", url("http://creativecommons.org/publicdomain/zero/1.0")))
