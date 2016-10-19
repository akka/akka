package akka

import sbt._
import sbt.Keys._

object Common extends AutoPlugin {

  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin

  override lazy val projectSettings = Seq(
    organization := "com.typesafe.akka",
    organizationName := "Lightbend",
    organizationHomepage := Some(url("https://www.lightbend.com")),
    homepage := Some(url("http://akka.io")),
    scmInfo := Some(
      ScmInfo(url("https://github.com/akka/akka-http"), "git@github.com:akka/akka-http.git")),
    developers := List(
      Developer("contributors", "Contributors", "akka-user@googlegroups.com",
        url("https://github.com/akka/akka-http/graphs/contributors"))
    ),
    startYear := Some(2014),
    test in sbtassembly.AssemblyKeys.assembly := {},
    licenses := Seq("Apache License 2.0" -> url("https://opensource.org/licenses/Apache-2.0")),
    crossVersion := CrossVersion.binary,
    scalacOptions ++= Seq(
      "-deprecation",
      "-encoding", "UTF-8", // yes, this is 2 args
      "-unchecked",
      "-Xlint",
      // "-Yno-adapted-args", //akka-http heavily depends on adapted args and => Unit implicits break otherwise
      "-Ywarn-dead-code"
        // "-Xfuture" // breaks => Unit implicits
    ),
    testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v")
  ) ++ Dependencies.Versions ++ Formatting.formatSettings

}
