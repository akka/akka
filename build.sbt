import com.typesafe.sbt.pgp.PgpKeys._
import akka._

lazy val root = Project(
    id = "akka-http-root",
    base = file(".")
  )
  .enablePlugins(NoPublish)
  .aggregate(
    parsing,
    httpCore,
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
  .settings(Dependencies.http)
  .dependsOn(httpCore)

lazy val httpTestkit = project("akka-http-testkit")
  .settings(Dependencies.httpTestkit)
  .dependsOn(httpCore % "compile->compile,test", http)

lazy val httpTests = project("akka-http-tests")
  .settings(Dependencies.httpTests)
  .dependsOn(httpSprayJson, httpXml, httpJackson,
    httpTestkit % "test", httpCore % "test->test")
  .enablePlugins(NoPublish)
  .disablePlugins(MimaPlugin) // this is only tests
  //.configs(MultiJvm)


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
