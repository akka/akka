name := "akka-sample-persistence-scala"

version := "2.3-SNAPSHOT"

scalaVersion := "2.11.5"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4-SNAPSHOT",
  "com.typesafe.akka" %% "akka-persistence-experimental" % "2.4-SNAPSHOT"
)
