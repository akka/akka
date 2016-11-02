import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import com.typesafe.sbt.pgp.PgpKeys._
import akka._

inThisBuild(Def.settings(
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
  //  test in assembly := {},
  licenses := Seq("Apache License 2.0" -> url("https://opensource.org/licenses/Apache-2.0")),
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding", "UTF-8", // yes, this is 2 args
    "-unchecked",
    "-Xlint",
    // "-Yno-adapted-args", //akka-http heavily depends on adapted args and => Unit implicits break otherwise
    "-Ywarn-dead-code"
    // "-Xfuture" // breaks => Unit implicits
  ),
  testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v"),
  Dependencies.Versions,
  Formatting.formatSettings
))


lazy val root = Project(
    id = "akka-http-root",
    base = file(".")
  )
  .enablePlugins(UnidocRoot, NoPublish)
  .settings(
    // Unidoc doesn't like macros
    unidocProjectExcludes := Seq(parsing)
  )
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
  .settings(
    scalacOptions := scalacOptions.value.filterNot(_ == "-Xfatal-warnings")
  )

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
  .enablePlugins(MultiNode)
  .disablePlugins(MimaPlugin) // this is only tests
  .configs(MultiJvm)


lazy val httpMarshallersScala = project("akka-http-marshallers-scala")
  //.disablePlugins(MimaPlugin)
  .enablePlugins(NoPublish)
  .aggregate(httpSprayJson, httpXml)

lazy val httpXml =
  httpMarshallersScalaSubproject("xml")

lazy val httpSprayJson =
  httpMarshallersScalaSubproject("spray-json")

lazy val httpMarshallersJava = project("akka-http-marshallers-java")
  //.disablePlugins(MimaPlugin)
  .enablePlugins(NoPublish)
  .aggregate(httpJackson)

lazy val httpJackson =
  httpMarshallersJavaSubproject("jackson")

def project(name: String) =
  Project(id = name, base = file(name))

def httpMarshallersScalaSubproject(name: String) =
  Project(
    id = s"akka-http-$name",
    base = file(s"akka-http-marshallers-scala/akka-http-$name"),
    dependencies = Seq(http)
  )
  //.disablePlugins(MimaPlugin)

def httpMarshallersJavaSubproject(name: String) =
  Project(
    id = s"akka-http-$name",
    base = file(s"akka-http-marshallers-java/akka-http-$name"),
    dependencies = Seq(http)
  )
  //.disablePlugins(MimaPlugin)

lazy val docs = project("docs")
  .enablePlugins(ParadoxPlugin, NoPublish)
  .dependsOn(
    httpCore, http, httpXml, httpMarshallersJava, httpMarshallersScala,
    httpTests % "compile;test->test", httpTestkit % "compile;test->test"
  )
  .settings(Dependencies.docs)
  .settings(
    name := "akka-http-docs",
    paradoxTheme := Some(builtinParadoxTheme("generic")),
    paradoxNavigationDepth := 3,
    paradoxProperties in Compile ++= Map(
      "akka.version" -> Dependencies.akkaVersion,
      "scala.binaryVersion" -> scalaBinaryVersion.value,
      "scala.version" -> scalaVersion.value,
      "crossString" -> (scalaVersion.value match {
        case akka.Doc.BinVer(_) => ""
        case _                  => "cross CrossVersion.full"
      }),
      "extref.akka-docs.base_url" -> s"http://doc.akka.io/docs/akka/${Dependencies.akkaVersion}/%s",
      "github.base_url" -> GitHub.url(version.value)
    ),
    Formatting.docFormatSettings
  )

shellPrompt := { s => Project.extract(s).currentProject.id + " > " }
