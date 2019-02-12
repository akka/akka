/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import Keys._

object Dependencies {
  
  import DependencyHelpers._

  object Compile {
    val aeronClient = "io.aeron" % "aeron-client" % Versions.Aeron // ApacheV2

    val aeronDriver = "io.aeron" % "aeron-driver" % Versions.Aeron // ApacheV2

    val camelCore = "org.apache.camel" % "camel-core" % Versions.Camel exclude("org.slf4j", "slf4j-api") // ApacheV2

    // when updating config version, update links ActorSystem ScalaDoc to link to the updated version
    val config = "com.typesafe" % "config" % Versions.Config // ApacheV2

    // For Java 8 Conversions
    val java8Compat = Def.setting { "org.scala-lang.modules" %% "scala-java8-compat" %  Versions.java8CompatVersion.value } // Scala License

    val jctools = "org.jctools" % "jctools-core" % Versions.Jctools // ApacheV2

    val junit = "junit" % "junit" % Versions.Junit // Common Public License 1.0

    val lmdb = "org.lmdbjava" % "lmdbjava" % Versions.Lmdb // ApacheV2, OpenLDAP Public License

    val netty = "io.netty" % "netty" % Versions.Netty // ApacheV2

    // mirrored in OSGi sample https://github.com/akka/akka-samples/tree/master/akka-sample-osgi-dining-hakkers
    val osgiCore = "org.osgi" % "org.osgi.core" % Versions.OsgiCore // ApacheV2
    val osgiCompendium = "org.osgi" % "org.osgi.compendium" % Versions.OsgiCompendium // ApacheV2

    val reactiveStreams = "org.reactivestreams" % "reactive-streams" % Versions.ReactiveStreams // CC0

    val scalaXml = "org.scala-lang.modules" %% "scala-xml" % Versions.ScalaXml // Scala License

    val scalaStm = Def.setting { "org.scala-stm" %% "scala-stm" % Versions.scalaStmVersion.value  } // Modified BSD (Scala)

    val scalaReflect = ScalaVersionDependentModuleID.versioned("org.scala-lang" % "scala-reflect" % _) // Scala License

    val sigar = "org.fusesource" % "sigar" % Versions.Sigar // ApacheV2

    val slf4jApi = "org.slf4j" % "slf4j-api" % Versions.Slf4j // MIT

    val sslConfigCore = "com.typesafe" %% "ssl-config-core" % Versions.SslConfig // ApacheV2

    object Docs {
      val gson = "com.google.code.gson" % "gson" % Versions.Gson % "test"
      val sprayJson = "io.spray" %% "spray-json" % Versions.SprayJson % "test"
    }

    object Test {
      val commonsCodec = "commons-codec" % "commons-codec" % Versions.CommonsCodec % "test" // ApacheV2
      val commonsIo = "commons-io" % "commons-io" % Versions.CommonsIo % "test" // ApacheV2
      val commonsMath = "org.apache.commons" % "commons-math" % Versions.CommonsMath % "test" // ApacheV2
      // docker utils
      val dockerClient = "com.spotify" % "docker-client" % Versions.DockerClient % "test" // ApacheV2
      // in-memory filesystem for file related tests
      val jimfs = "com.google.jimfs" % "jimfs" % Versions.Jimfs % "test" // ApacheV2
      val junit = "junit" % "junit" % Versions.Junit % "test" // Common Public License 1.0
      val log4j = "log4j" % "log4j" % Versions.Log4j % "test" // ApacheV2
      val logback = "ch.qos.logback" % "logback-classic" % Versions.Logback % "test" // EPL 1.0 / LGPL 2.1
      val mockito = "org.mockito" % "mockito-core" % Versions.Mockito % "test" // MIT
      val pojosr = "com.googlecode.pojosr" % "de.kalpatec.pojosr.framework" % Versions.Pojosr % "test" // ApacheV2
      val reactiveStreamsTck = "org.reactivestreams" % "reactive-streams-tck" % Versions.ReactiveStreamsTck % "test" // CC0
      // changing the scalatest dependency must be reflected in akka-docs/rst/dev/multi-jvm-testing.rst
      val scalacheck = Def.setting { "org.scalacheck" %% "scalacheck" % Versions.scalaCheckVersion.value % "test" } // New BSD
      val scalatest = Def.setting { "org.scalatest" %% "scalatest" % Versions.scalaTestVersion.value % "test" } // ApacheV2
      val tinybundles = "org.ops4j.pax.tinybundles" % "tinybundles" % Versions.TinyBundles % "test" // ApacheV2
      val scalaXml = "org.scala-lang.modules" %% "scala-xml" % Versions.ScalaXml % "test"

      // sigar logging
      val slf4jJul = "org.slf4j" % "jul-to-slf4j" % Versions.Slf4j % "test" // MIT
      val slf4jLog4j = "org.slf4j" % "log4j-over-slf4j" % Versions.Slf4j % "test" // MIT

      // testkit: metrics, measurements, perf testing
      val metrics = "io.dropwizard.metrics" % "metrics-core" % Versions.DropwizardMetrics % "test" // ApacheV2
      val metricsJvm = "io.dropwizard.metrics" % "metrics-jvm" % Versions.DropwizardMetrics % "test" // ApacheV2
      val latencyUtils = "org.latencyutils" % "LatencyUtils" % Versions.LatencyUtils % "test" // Free BSD
      val hdrHistogram = "org.hdrhistogram" % "HdrHistogram" % Versions.HdrHistogram % "test" // CC0
    }

    object Provided {
      // Camel: Non-default module in Java9, removed in Java11.
      val activation = "com.sun.activation" % "javax.activation" % Versions.JavaxActivation % "provided;test"
      val jaxb = "javax.xml.bind" % "jaxb-api" % Versions.JaxbApi % "provided;test"

      val junit = Compile.junit % "optional;provided;test"
      val levelDB = "org.iq80.leveldb" % "leveldb" % Versions.Iq80LevelDB % "optional;provided" // ApacheV2
      val levelDBmultiJVM = "org.iq80.leveldb" % "leveldb" % Versions.Iq80LevelDB % "optional;provided;multi-jvm" // ApacheV2
      val levelDBNative = "org.fusesource.leveldbjni" % "leveldbjni-all" % Versions.LevelDBJni % "optional;provided" // New BSD
      val scalatest = Def.setting { "org.scalatest" %% "scalatest" % Versions.scalaTestVersion.value % "optional;provided;test" } // ApacheV2

      // TODO remove from "test" config
      // If changed, update akka-docs/build.sbt as well
      val sigarLoader = "io.kamon" % "sigar-loader" % Versions.KamonSigar % "optional;provided;test" // ApacheV2
    }

  }

  import Compile._
  
  val actor = libraryDependencies ++= Seq(config, java8Compat.value)

  val discovery = libraryDependencies ++= Seq(Test.junit, Test.scalatest.value)
 
  val testkit = libraryDependencies ++= Seq(Test.metrics, Test.metricsJvm, Test.latencyUtils, Test.hdrHistogram, Test.junit, Test.scalatest.value)

  val actorTests = libraryDependencies ++= Seq(
    Test.junit, Test.scalatest.value, Test.commonsCodec, Test.commonsMath,
    Test.mockito, Test.scalacheck.value, Test.jimfs,
    Test.dockerClient, Provided.activation // dockerClient needs javax.activation.DataSource in JDK 11+
  )

  val actorTestkitTyped = libraryDependencies ++= Seq(Provided.junit, Provided.scalatest.value)

  val remote = libraryDependencies ++= Seq(netty, aeronDriver, aeronClient, Test.junit, Test.scalatest.value, Test.jimfs)

  val remoteTests = libraryDependencies ++= Seq(Test.junit, Test.scalatest.value, Test.scalaXml)

  val cluster = libraryDependencies ++= Seq(Test.junit, Test.scalatest.value)

  val clusterTools = libraryDependencies ++= Seq(Test.junit, Test.scalatest.value)

  val clusterSharding = libraryDependencies ++= Seq(Provided.levelDBmultiJVM, Provided.levelDBNative, Test.junit, Test.scalatest.value, Test.commonsIo)

  val clusterMetrics = libraryDependencies ++= Seq(Provided.sigarLoader, Test.logback, Test.mockito, Test.slf4jJul, Test.slf4jLog4j)

  val distributedData = libraryDependencies ++= Seq(lmdb, Test.junit, Test.scalatest.value)

  val slf4j = libraryDependencies ++= Seq(slf4jApi, Test.logback)

  val agent = libraryDependencies ++= Seq(scalaStm.value, Test.scalatest.value, Test.junit)

  val persistence = libraryDependencies ++= Seq(Provided.levelDB, Provided.levelDBNative, Test.scalatest.value, Test.junit, Test.commonsIo, Test.commonsCodec, Test.scalaXml)

  val persistenceQuery = libraryDependencies ++= Seq(Test.scalatest.value, Test.junit, Test.commonsIo, Provided.levelDB, Provided.levelDBNative)

  val persistenceTck = libraryDependencies ++= Seq(Test.scalatest.value.withConfigurations(Some("compile")), Test.junit.withConfigurations(Some("compile")), Provided.levelDB, Provided.levelDBNative)

  val persistenceShared = libraryDependencies ++= Seq(Provided.levelDB, Provided.levelDBNative)

  val camel = libraryDependencies ++= Seq(camelCore, Provided.jaxb, Provided.activation, Test.scalatest.value, Test.junit, Test.mockito, Test.logback, Test.commonsIo)

  val osgi = libraryDependencies ++= Seq(osgiCore, osgiCompendium, Test.logback, Test.commonsIo, Test.pojosr, Test.tinybundles, Test.scalatest.value, Test.junit)

  val docs = libraryDependencies ++= Seq(Test.scalatest.value, Test.junit, Docs.sprayJson, Docs.gson, Provided.levelDB)

  val contrib = libraryDependencies ++= Seq(Test.commonsIo)

  val benchJmh = libraryDependencies ++= Seq(Provided.levelDB, Provided.levelDBNative, Compile.jctools)

  // akka stream

  lazy val stream = libraryDependencies ++= Seq[sbt.ModuleID](
    reactiveStreams,
    sslConfigCore,
    Test.scalatest.value)

  lazy val streamTestkit = libraryDependencies ++= Seq(Test.scalatest.value, Test.scalacheck.value, Test.junit)

  lazy val streamTests = libraryDependencies ++= Seq(Test.scalatest.value, Test.scalacheck.value, Test.junit, Test.commonsIo, Test.jimfs)

  lazy val streamTestsTck = libraryDependencies ++= Seq(Test.scalatest.value, Test.scalacheck.value, Test.junit, Test.reactiveStreamsTck)

}

object DependencyHelpers {

  case class ScalaVersionDependentModuleID(modules: String ⇒ Seq[ModuleID]) {
    def %(config: String): ScalaVersionDependentModuleID =
      ScalaVersionDependentModuleID(version ⇒ modules(version).map(_ % config))
  }

  object ScalaVersionDependentModuleID {
    implicit def liftConstantModule(mod: ModuleID): ScalaVersionDependentModuleID = versioned(_ ⇒ mod)

    def versioned(f: String ⇒ ModuleID): ScalaVersionDependentModuleID = ScalaVersionDependentModuleID(v ⇒ Seq(f(v)))

    def fromPF(f: PartialFunction[String, ModuleID]): ScalaVersionDependentModuleID =
      ScalaVersionDependentModuleID(version ⇒ if (f.isDefinedAt(version)) Seq(f(version)) else Nil)
  }

  /**
    * Use this as a dependency setting if the dependencies contain both static and Scala-version
    * dependent entries.
    */
  def versionDependentDeps(modules: ScalaVersionDependentModuleID*): Def.Setting[Seq[ModuleID]] =
    libraryDependencies ++= modules.flatMap(m ⇒ m.modules(scalaVersion.value))

  val ScalaVersion = """\d\.\d+\.\d+(?:-(?:M|RC)\d+)?""".r
  // is this even used?
  val nominalScalaVersion: String ⇒ String = {
    // matches:
    // 2.12.0-M1
    // 2.12.0-RC1
    // 2.12.0
    case version@ScalaVersion() ⇒ version
    // transforms 2.12.0-custom-version to 2.12.0
    case version ⇒ version.takeWhile(_ != '-')
  }
}
