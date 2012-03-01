import sbt._
import Keys._

object TutorialBuild extends Build {
  lazy val buildSettings = Seq(
    organization := "com.typesafe.akka",
    version := "2.0-RC4",
    scalaVersion := "2.9.1"
  )

  lazy val akka = Project(
    id = "akka-tutorial-first",
    base = file("."),
    settings = Defaults.defaultSettings ++ Seq(
      libraryDependencies ++= Seq(
        "com.typesafe.akka" % "akka-actor"      % "2.0-RC4",
        "junit"             % "junit"           % "4.5"           % "test",
        "org.scalatest"     % "scalatest_2.9.0" % "1.6.1"         % "test",
        "com.typesafe.akka" % "akka-testkit"    % "2.0-RC4"  % "test")
    )
  )
}
