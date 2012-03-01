
import sbt._
import Keys._
import akka.sbt.AkkaKernelPlugin
import akka.sbt.AkkaKernelPlugin.{ Dist, outputDirectory, distJvmOptions}

object HelloKernelBuild extends Build {
  val Organization = "akka.sample"
  val Version      = "2.0-RC4"
  val ScalaVersion = "2.9.1"

  lazy val HelloKernel = Project(
    id = "hello-kernel",
    base = file("."),
    settings = defaultSettings ++ AkkaKernelPlugin.distSettings ++ Seq(
      libraryDependencies ++= Dependencies.helloKernel,
      distJvmOptions in Dist := "-Xms256M -Xmx1024M",
      outputDirectory in Dist := file("target/hello-dist")
    )
  )

  lazy val buildSettings = Defaults.defaultSettings ++ Seq(
    organization := Organization,
    version      := Version,
    scalaVersion := ScalaVersion,
    crossPaths   := false,
    organizationName := "Typesafe Inc.",
    organizationHomepage := Some(url("http://www.typesafe.com"))
  )
  
  lazy val defaultSettings = buildSettings ++ Seq(
    resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/",

    // compile options
    scalacOptions ++= Seq("-encoding", "UTF-8", "-deprecation", "-unchecked"),
    javacOptions  ++= Seq("-Xlint:unchecked", "-Xlint:deprecation")

  )
}

object Dependencies {
  import Dependency._

  val helloKernel = Seq(
    akkaKernel, akkaSlf4j, logback
  )
}

object Dependency {
  // Versions
  object V {
    val Akka      = "2.0-RC4"
  }

  val akkaKernel        = "com.typesafe.akka" % "akka-kernel"        % V.Akka
  val akkaSlf4j         = "com.typesafe.akka" % "akka-slf4j"         % V.Akka
  val logback           = "ch.qos.logback"    % "logback-classic"    % "1.0.0"
}
