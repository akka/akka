import com.typesafe.sbt.SbtMultiJvm.multiJvmSettings
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm

val AkkaVersion = "2.9.2"
val AkkaDiagnosticsVersion = "2.1.0"
val LogbackClassicVersion = "1.2.11" 
val ScalaTestVersion = "3.1.1"

val `akka-sample-distributed-data-scala` = project
  .in(file("."))
  .settings(multiJvmSettings: _*)
  .settings(
    organization := "com.lightbend.akka.samples",
    version := "1.0",
    scalaVersion := "2.13.12",
    scalacOptions in Compile ++= Seq("-deprecation", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint"),
    javacOptions in Compile ++= Seq("-Xlint:unchecked", "-Xlint:deprecation"),
    javaOptions in run ++= Seq("-Xms128m", "-Xmx1024m"),
    resolvers += "Akka library repository".at("https://repo.akka.io/maven"),
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-cluster-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-serialization-jackson" % AkkaVersion,
      "com.lightbend.akka" %% "akka-diagnostics" % AkkaDiagnosticsVersion,
      "com.typesafe.akka" %% "akka-multi-node-testkit" % AkkaVersion % Test,
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test,
      "ch.qos.logback" % "logback-classic" % LogbackClassicVersion % Test,
      "org.scalatest" %% "scalatest" % ScalaTestVersion % Test),
    fork in run := true,
    // disable parallel tests
    parallelExecution in Test := false,
    // show full stack traces and test case durations
    testOptions in Test += Tests.Argument("-oDF"),
    logBuffered in Test := false,
    licenses := Seq(("CC0", url("http://creativecommons.org/publicdomain/zero/1.0")))
  )
  .configs (MultiJvm)
