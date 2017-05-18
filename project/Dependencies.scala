package akka

import sbt._
import sbt.Keys._
import scala.language.implicitConversions

object Dependencies {
  import DependencyHelpers._

  val junitVersion = "4.12"
  val h2specVersion = "1.5.0"
  val h2specName = s"h2spec_${DependencyHelpers.osName}_amd64"
  val h2specExe = "h2spec" + DependencyHelpers.exeIfWindows
  val h2specUrl = s"https://github.com/summerwind/h2spec/releases/download/v${h2specVersion}/${h2specName}.zip"

  lazy val akkaVersion = settingKey[String]("The version of Akka to use.")
  lazy val scalaTestVersion = settingKey[String]("The version of ScalaTest to use.")
  lazy val specs2Version = settingKey[String]("The version of Specs2 to use")
  lazy val scalaCheckVersion = settingKey[String]("The version of ScalaCheck to use.")

  val Versions = Seq(
    crossScalaVersions := Seq("2.11.11", "2.12.1"),
    scalaVersion := crossScalaVersions.value.head,
    akkaVersion := System.getProperty("akka.build.version", "2.4.18"),
    scalaCheckVersion := System.getProperty("akka.build.scalaCheckVersion", "1.13.4"),
    scalaTestVersion := "3.0.3",
    specs2Version := "3.8.6"
  )
  import Versions._


  object Compile {
    val scalaXml      = "org.scala-lang.modules"      %% "scala-xml"                   % "1.0.6" // Scala License
    val scalaReflect  = ScalaVersionDependentModuleID.versioned("org.scala-lang" % "scala-reflect" % _) // Scala License

    // ssl-config
    val sslConfigAkka = "com.typesafe"               %% "ssl-config-akka"              % "0.2.2"       // ApacheV2

    // For akka-http spray-json support
    val sprayJson   = "io.spray"                     %% "spray-json"                   % "1.3.3"       // ApacheV2

    // For akka-http-jackson support
    val jackson     = "com.fasterxml.jackson.core"    % "jackson-databind"             % "2.7.6"       // ApacheV2

    // For akka-http-testkit-java
    val junit       = "junit"                         % "junit"                        % junitVersion  // Common Public License 1.0

    val hpack       = "com.twitter"                   % "hpack"                        % "1.0.2"       // ApacheV2

    val alpnApi     = "org.eclipse.jetty.alpn"        % "alpn-api"                     % "1.1.3.v20160715" // ApacheV2

    object Docs {
      val sprayJson   = Compile.sprayJson                                                                    % "test"
      val gson        = "com.google.code.gson"        % "gson"                         % "2.3.1"             % "test"
    }

    object Test {
      val junit        = Compile.junit                                                                       % "test" // Common Public License 1.0
      val scalatest    = Def.setting { "org.scalatest"  %% "scalatest"   % scalaTestVersion.value   % "test" }      // ApacheV2
      val specs2       = Def.setting { "org.specs2"     %% "specs2-core" % specs2Version.value      % "test" }      // MIT
      val scalacheck   = Def.setting { "org.scalacheck" %% "scalacheck"  % scalaCheckVersion.value  % "test" }      // New BSD
      val junitIntf    = "com.novocode"                % "junit-interface"              % "0.11"             % "test" // MIT
      val sprayJson    = Compile.sprayJson                                                                   % "test" // ApacheV2

      // HTTP/2
      val alpnAgent    = "org.mortbay.jetty.alpn"      % "jetty-alpn-agent"             % "2.0.6"            % "test" // ApacheV2
      val h2spec       = "io.github.summerwind"        % h2specName                     % h2specVersion      % "test" from(h2specUrl) // MIT
    }
  }

  import Compile._

  lazy val l = libraryDependencies

  lazy val parsing = Seq(
    DependencyHelpers.versionDependentDeps(
      Dependencies.Compile.scalaReflect % "provided"
    ),
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.fullMapped(nominalScalaVersion))
  )

  lazy val httpCore = l ++= Seq(
    Test.sprayJson, // for WS Autobahn test metadata
    Test.scalatest.value, Test.scalacheck.value, Test.junit)

  lazy val http = l ++= Seq()

  lazy val http2 = l ++= Seq(hpack, alpnApi)

  lazy val http2Support = l ++= Seq(Test.h2spec)

  lazy val httpTestkit = l ++= Seq(
    Test.junit, Test.junitIntf, Compile.junit % "provided",
    Test.scalatest.value.copy(configurations = Some("provided; test")),
    Test.specs2.value.copy(configurations = Some("provided; test"))
  )

  lazy val httpTests = l ++= Seq(Test.junit, Test.scalatest.value, Test.junitIntf)

  lazy val httpXml = versionDependentDeps(scalaXml)

  lazy val httpSprayJson = Seq(
    versionDependentDeps(sprayJson),
    libraryDependencies += Test.scalatest.value
  )

  lazy val httpJackson = l ++= Seq(jackson)

  lazy val docs = l ++= Seq(Docs.sprayJson, Docs.gson)
}


object DependencyHelpers {
  case class ScalaVersionDependentModuleID(modules: String => Seq[ModuleID]) {
    def %(config: String): ScalaVersionDependentModuleID =
      ScalaVersionDependentModuleID(version => modules(version).map(_ % config))
  }
  object ScalaVersionDependentModuleID {
    implicit def liftConstantModule(mod: ModuleID): ScalaVersionDependentModuleID = versioned(_ => mod)

    def versioned(f: String => ModuleID): ScalaVersionDependentModuleID = ScalaVersionDependentModuleID(v => Seq(f(v)))
    def fromPF(f: PartialFunction[String, ModuleID]): ScalaVersionDependentModuleID =
      ScalaVersionDependentModuleID(version => if (f.isDefinedAt(version)) Seq(f(version)) else Nil)
  }

  /**
   * Use this as a dependency setting if the dependencies contain both static and Scala-version
   * dependent entries.
   */
  def versionDependentDeps(modules: ScalaVersionDependentModuleID*): Def.Setting[Seq[ModuleID]] =
    libraryDependencies ++= scalaVersion(version => modules.flatMap(m => m.modules(version))).value

  val ScalaVersion = """\d\.\d+\.\d+(?:-(?:M|RC)\d+)?""".r
  val nominalScalaVersion: String => String = {
    // matches:
    // 2.12.0-M1
    // 2.12.0-RC1
    // 2.12.0
    case version @ ScalaVersion() => version
    // transforms 2.12.0-custom-version to 2.12.0
    case version => version.takeWhile(_ != '-')
  }

  // OS name for Go binaries
  def osName = {
    val os = System.getProperty("os.name").toLowerCase()
    if (os startsWith "mac") "darwin"
    else if (os startsWith "win") "windows"
    else "linux"
  }

  def exeIfWindows = {
    val os = System.getProperty("os.name").toLowerCase()
    if (os startsWith "win") ".exe"
    else ""
  }
}
