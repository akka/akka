name := "akka-docs-udp-multicast"

version := "2.4-SNAPSHOT"

scalaVersion := "2.11.5"

compileOrder := CompileOrder.ScalaThenJava

javacOptions ++= Seq("-source", "1.7", "-target", "1.7", "-Xlint")

testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4-SNAPSHOT",
  "com.typesafe.akka" %% "akka-testkit" % "2.4-SNAPSHOT",
  "org.scalatest" %% "scalatest" % "2.2.1" % "test",
  "junit" % "junit" % "4.11" % "test",
  "com.novocode" % "junit-interface" % "0.10" % "test"
)
