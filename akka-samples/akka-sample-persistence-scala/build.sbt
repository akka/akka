name := "akka-sample-persistence-scala"

version := "2.4-SNAPSHOT"

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4-SNAPSHOT",
  "com.typesafe.akka" %% "akka-persistence" % "2.4-SNAPSHOT"
)
