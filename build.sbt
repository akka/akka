import akka._
import akka.ValidatePullRequest._
import AkkaDependency._
import Dependencies.{ h2specName, h2specExe }
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import java.nio.file.Files
import java.nio.file.attribute.{ PosixFileAttributeView, PosixFilePermission }
import sbtdynver.GitDescribeOutput
import spray.boilerplate.BoilerplatePlugin

inThisBuild(Def.settings(
  organization := "com.typesafe.akka",
  organizationName := "Lightbend",
  organizationHomepage := Some(url("https://www.lightbend.com")),
  homepage := Some(url("http://akka.io")),
  // https://github.com/dwijnand/sbt-dynver/issues/23
  isSnapshot :=  { isSnapshot.value || hasCommitsAfterTag(dynverGitDescribeOutput.value) },
  apiURL := {
    val apiVersion = if (isSnapshot.value) "current" else version.value
    Some(url(s"http://doc.akka.io/api/akka-http/$apiVersion/"))
  },
  scmInfo := Some(
    ScmInfo(url("https://github.com/akka/akka-http"), "git@github.com:akka/akka-http.git")),
  developers := List(
    Developer("contributors", "Contributors", "akka-user@googlegroups.com",
      url("https://github.com/akka/akka-http/graphs/contributors"))
  ),
  startYear := Some(2014),
  //  test in assembly := {},
  licenses := Seq("Apache-2.0" -> url("https://opensource.org/licenses/Apache-2.0")),
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding", "UTF-8", // yes, this is 2 args
    "-unchecked",
    "-Xlint",
    // "-Yno-adapted-args", //akka-http heavily depends on adapted args and => Unit implicits break otherwise
    "-Ywarn-dead-code"
    // "-Xfuture" // breaks => Unit implicits
  ),
  javacOptions ++= Seq(
    "-encoding", "UTF-8"
  ),
  testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v"),
  Dependencies.Versions,
  Formatting.formatSettings,
  shellPrompt := { s => Project.extract(s).currentProject.id + " > " }
))

lazy val root = Project(
    id = "akka-http-root",
    base = file(".")
  )
  .enablePlugins(UnidocRoot, NoPublish, DeployRsync)
  .disablePlugins(BintrayPlugin, MimaPlugin)
  .settings(
    // Unidoc doesn't like macros
    unidocProjectExcludes := Seq(parsing),
    deployRsyncArtifact :=
      (unidoc in Compile).value zip Seq(s"www/api/akka-http/${version.value}", s"www/japi/akka-http/${version.value}")
  )
  .aggregate(
    parsing,
    httpCore,
    http2Support,
    http,
    httpCaching,
    httpTestkit,
    httpTests,
    httpMarshallersScala,
    httpMarshallersJava,
    docs
  )

lazy val parsing = project("akka-parsing")
  .addAkkaModuleDependency("akka-actor")
  .settings(Dependencies.parsing)
  .settings(OSGi.parsing)
  .settings(
    scalacOptions := scalacOptions.value.filterNot(_ == "-Xfatal-warnings"),
    scalacOptions += "-language:_",
    unmanagedSourceDirectories in ScalariformKeys.format in Test := (unmanagedSourceDirectories in Test).value
  )
  .enablePlugins(ScaladocNoVerificationOfDiagrams)
  .disablePlugins(MimaPlugin)

lazy val httpCore = project("akka-http-core")
  .dependsOn(parsing)
  .addAkkaModuleDependency("akka-stream")
  .addAkkaModuleDependency("akka-stream-testkit", "test")
  .settings(Dependencies.httpCore)
  .settings(OSGi.httpCore)
  .settings(VersionGenerator.versionSettings)
  .enablePlugins(BootstrapGenjavadoc)

lazy val http = project("akka-http")
  .dependsOn(httpCore)
  .settings(Dependencies.http)
  .settings(OSGi.http)
  .settings(
    scalacOptions in Compile += "-language:_"
  )
  .enablePlugins(BootstrapGenjavadoc, BoilerplatePlugin)

lazy val http2Support = project("akka-http2-support")
  .dependsOn(httpCore, httpTestkit % "test", httpCore % "test->test")
  .addAkkaModuleDependency("akka-stream-testkit", "test")
  .settings(Dependencies.http2)
  .settings(Dependencies.http2Support)
  .settings(OSGi.http2Support)
  .settings {
    lazy val h2specPath = Def.task {
      (target in Test).value / h2specName / h2specExe
    }
    Seq(
      javaAgents += Dependencies.Compile.Test.alpnAgent,
      fork in run in Test := true,
      fork in Test := true,
      sbt.Keys.connectInput in run in Test := true,
      javaOptions in Test += "-Dh2spec.path=" + h2specPath.value,
      resourceGenerators in Test += Def.task {
        val log = streams.value.log
        val h2spec = h2specPath.value

        if (!h2spec.exists) {
          log.info("Extracting h2spec to " + h2spec)

          for (zip <- (update in Test).value.select(artifact = artifactFilter(name = h2specName, extension = "zip")))
            IO.unzip(zip, (target in Test).value)

          // Set the executable bit on the expected path to fail if it doesn't exist
          for (view <- Option(Files.getFileAttributeView(h2spec.toPath, classOf[PosixFileAttributeView]))) {
            val permissions = view.readAttributes.permissions
            if (permissions.add(PosixFilePermission.OWNER_EXECUTE))
              view.setPermissions(permissions)
          }
        }
        Seq(h2spec)
      }
    )
  }
  .enablePlugins(JavaAgent, BootstrapGenjavadoc)
  .disablePlugins(MimaPlugin) // experimental module still

lazy val httpTestkit = project("akka-http-testkit")
  .dependsOn(http)
  .addAkkaModuleDependency("akka-stream-testkit")
  .settings(Dependencies.httpTestkit)
  .settings(OSGi.httpTestkit)
  .settings(
    // don't ignore Suites which is the default for the junit-interface
    testOptions += Tests.Argument(TestFrameworks.JUnit, "--ignore-runners="),
    scalacOptions in Compile ++= Seq("-language:_"),
    mainClass in run in Test := Some("akka.http.javadsl.SimpleServerApp")
  )
  .enablePlugins(BootstrapGenjavadoc, MultiNodeScalaTest, ScaladocNoVerificationOfDiagrams)
  .disablePlugins(MimaPlugin) // testkit, no bin compat guaranteed

lazy val httpTests = project("akka-http-tests")
  .settings(Dependencies.httpTests)
  .dependsOn(httpSprayJson, httpXml, httpJackson,
    httpTestkit % "test", httpCore % "test->test")
  .enablePlugins(NoPublish).disablePlugins(BintrayPlugin) // don't release tests
  .enablePlugins(MultiNode)
  .disablePlugins(MimaPlugin) // this is only tests
  .configs(MultiJvm)
  .addAkkaModuleDependency("akka-multi-node-testkit", "test")


lazy val httpMarshallersScala = project("akka-http-marshallers-scala")
  .enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin, MimaPlugin)
  .aggregate(httpSprayJson, httpXml)

lazy val httpXml =
  httpMarshallersScalaSubproject("xml")
    .settings(Dependencies.httpXml)
    .settings(OSGi.httpXml)

lazy val httpSprayJson =
  httpMarshallersScalaSubproject("spray-json")
    .settings(Dependencies.httpSprayJson)
    .settings(OSGi.httpSprayJson)

lazy val httpMarshallersJava = project("akka-http-marshallers-java")
  .enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin, MimaPlugin)
  .aggregate(httpJackson)

lazy val httpJackson =
  httpMarshallersJavaSubproject("jackson")
    .settings(Dependencies.httpJackson)
    .settings(OSGi.httpJackson)
    .enablePlugins(ScaladocNoVerificationOfDiagrams)

lazy val httpCaching = project("akka-http-caching")
  .settings(Dependencies.httpCaching)
  .dependsOn(http, httpCore, httpTestkit % "test")

def project(name: String) =
  Project(id = name, base = file(name))

def httpMarshallersScalaSubproject(name: String) =
  Project(
    id = s"akka-http-$name",
    base = file(s"akka-http-marshallers-scala/akka-http-$name")
  )
  .dependsOn(http)
  .enablePlugins(BootstrapGenjavadoc)

def httpMarshallersJavaSubproject(name: String) =
  Project(
    id = s"akka-http-$name",
    base = file(s"akka-http-marshallers-java/akka-http-$name"),
  )
  .dependsOn(http)
  .enablePlugins(BootstrapGenjavadoc)

lazy val docs = project("docs")
  .enablePlugins(AkkaParadoxPlugin, NoPublish, DeployRsync)
  .disablePlugins(BintrayPlugin, MimaPlugin)
  .dependsOn(
    httpCore, http, httpXml, http2Support, httpMarshallersJava, httpMarshallersScala, httpCaching,
    httpTests % "compile;test->test", httpTestkit % "compile;test->test"
  )
  .settings(Dependencies.docs)
  .settings(
    name := "akka-http-docs",
    resolvers += Resolver.jcenterRepo,
    paradoxGroups := Map("Languages" -> Seq("Scala", "Java")),
    paradoxProperties in Compile ++= Map(
      "project.name" -> "Akka HTTP",
      "akka.version" -> Dependencies.akkaVersion.value,
      "akka25.version" -> Dependencies.akka25Version,
      "scala.binary_version" -> scalaBinaryVersion.value, // to be consistent with Akka build
      "scala.binaryVersion" -> scalaBinaryVersion.value,
      "scaladoc.version" -> scalaVersion.value,
      "crossString" -> (scalaVersion.value match {
        case akka.Doc.BinVer(_) => ""
        case _                  => "cross CrossVersion.full"
      }),
      "jackson.version" -> Dependencies.jacksonVersion,
      "extref.akka-docs.base_url" -> s"http://doc.akka.io/docs/akka/${Dependencies.akkaVersion.value}/%s",
      "extref.akka25-docs.base_url" -> s"http://doc.akka.io/docs/akka/2.5/%s",
      "javadoc.akka.http.base_url" -> {
        val v = if (isSnapshot.value) "current" else version.value
        s"http://doc.akka.io/japi/akka-http/$v"
      },
      "algolia.docsearch.api_key" -> "0ccbb8bf5148554a406fbf07df0a93b9",
      "algolia.docsearch.index_name" -> "akka-http",
      "google.analytics.account" -> "UA-21117439-1",
      "google.analytics.domain.name" -> "akka.io",
      "github.base_url" -> GitHub.url(version.value),
      "snip.test.base_dir" -> (sourceDirectory in Test).value.getAbsolutePath,
      "snip.akka-http.base_dir" -> (baseDirectory in ThisBuild).value.getAbsolutePath,
      "signature.test.base_dir" -> (sourceDirectory in Test).value.getAbsolutePath,
      "signature.akka-http.base_dir" -> (baseDirectory in ThisBuild).value.getAbsolutePath
    ),
    Formatting.docFormatSettings,
    additionalTasks in ValidatePR += paradox in Compile,
    deployRsyncArtifact := List((paradox in Compile).value -> s"www/docs/akka-http/${version.value}")
  )
  .settings(ParadoxSupport.paradoxWithSignatureDirective)

def hasCommitsAfterTag(description: Option[GitDescribeOutput]): Boolean = description.get.commitSuffix.distance > 0
