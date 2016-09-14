import sbt._
import Keys._

import com.typesafe.sbt.pgp.PgpKeys._
import akka._

name := "akka-http"

val commonSettings =
  Seq(
    organization := "com.typesafe.akka",
    organizationName := "Lightbend",
    startYear := Some(2014),
    test in assembly := {},
    licenses := Seq("Apache License 2.0" -> url("http://opensource.org/licenses/Apache-2.0")),
    scalaVersion := "2.11.8",
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
    testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v")) ++
      Dependencies.Versions ++ akka.Formatting.formatSettings

val dontPublishSettings = Seq(
  publishSigned := (),
  publish := (),
  publishArtifact /* in Compile */ := false
)

lazy val parentSettings = Seq(
  publishArtifact := false
) ++ dontPublishSettings

lazy val root = Project(
    id = "akka-http-root",
    base = file(".")
  )
    .settings(commonSettings)
    .settings(Seq(
      publishArtifact := false,
      publishTo := Some(Resolver.file("Unused transient repository", file("target/unusedrepo")))))
    .aggregate(
      parsing,
      httpCore,
      http2Support,
      http,
      httpTestkit,
      httpTests, 
      httpMarshallersScala, 
      httpMarshallersJava, 
      docs
    )

lazy val parsing = project("akka-parsing")
  .settings(Dependencies.parsing)
  .settings(Seq(
    scalacOptions := scalacOptions.value.filterNot(_ == "-Xfatal-warnings")
  ))

lazy val httpCore = project("akka-http-core")
  .settings(Dependencies.httpCore)
  .dependsOn(parsing)
  //.disablePlugins(MimaPlugin)

lazy val http = project("akka-http")
  .dependsOn(httpCore)

lazy val http2Support = project("akka-http2-support")
  .dependsOn(httpCore, httpTestkit % "test", httpCore % "test->test")

lazy val httpTestkit = project("akka-http-testkit")
  .settings(Dependencies.httpTestkit)
  .dependsOn(http)

lazy val httpTests = project("akka-http-tests")
  .settings(Dependencies.httpTests)
  .dependsOn(httpSprayJson, httpXml, httpJackson,
    httpTestkit % "test", httpCore % "test->test")
 //.configs(MultiJvm) //.disablePlugins(MimaPlugin)


lazy val httpMarshallersScala = project("akka-http-marshallers-scala")
  //.disablePlugins(MimaPlugin)
  .settings(parentSettings: _*)
  .aggregate(httpSprayJson, httpXml)

lazy val httpXml =
  httpMarshallersScalaSubproject("xml")

lazy val httpSprayJson =
  httpMarshallersScalaSubproject("spray-json")

lazy val httpMarshallersJava = project("akka-http-marshallers-java") 
  //.disablePlugins(MimaPlugin)
  .settings(parentSettings: _*)
  .aggregate(httpJackson)

lazy val httpJackson =
  httpMarshallersJavaSubproject("jackson")

def project(name: String) =
  Project(id = name, base = file(name))
    .settings(commonSettings)

def httpMarshallersScalaSubproject(name: String) =
  Project(
    id = s"akka-http-$name",
    base = file(s"akka-http-marshallers-scala/akka-http-$name"),
    dependencies = Seq(http)
  ).settings(commonSettings)
  //.disablePlugins(MimaPlugin)

def httpMarshallersJavaSubproject(name: String) =
  Project(
    id = s"akka-http-$name",
    base = file(s"akka-http-marshallers-java/akka-http-$name"),
    dependencies = Seq(http)
  ).settings(commonSettings)
  //.disablePlugins(MimaPlugin)

lazy val docs = project("docs")
  .enablePlugins(ParadoxPlugin)
  .dependsOn(httpCore, http, httpTestkit)
  .settings(commonSettings)
  .settings(Dependencies.docs)
  .settings(
    name := "akka-http-docs",
    publishArtifact := false,
    paradoxTheme := Some(builtinParadoxTheme("generic")),
    paradoxNavigationDepth := 3,
    paradoxProperties ++= Map(
      "version" -> version.value,
      "akka.version" -> Dependencies.akkaVersion,
      "scala.binaryVersion" -> scalaBinaryVersion.value,
      "scala.version" -> scalaVersion.value
    )
  )


shellPrompt := { s => Project.extract(s).currentProject.id + " > " }
