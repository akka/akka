organization := "com.lightbend.akka.samples"
name := "akka-sample-fsm-scala"

val AkkaVersion = "2.9.2"
val LogbackClassicVersion = "1.2.11"
val AkkaDiagnosticsVersion = "2.1.0"

scalaVersion := "2.13.12"

resolvers += "Akka library repository".at("https://repo.akka.io/maven")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "ch.qos.logback" % "logback-classic" % LogbackClassicVersion,
  "com.lightbend.akka" %% "akka-diagnostics" % AkkaDiagnosticsVersion
)

run / fork := true

licenses := Seq(
  ("CC0", url("http://creativecommons.org/publicdomain/zero/1.0"))
)
