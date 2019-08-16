/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import Keys._

object Dependencies {
  import DependencyHelpers._

  lazy val scalaTestVersion = settingKey[String]("The version of ScalaTest to use.")
  lazy val scalaCheckVersion = settingKey[String]("The version of ScalaCheck to use.")
  lazy val java8CompatVersion = settingKey[String]("The version of scala-java8-compat to use.")

  val junitVersion = "4.12"
  val slf4jVersion = "1.7.25"
  val scalaXmlVersion = "1.0.6"
  // check agrona version when updating this
  val aeronVersion = "1.19.1"
  // needs to be inline with the aeron version
  val agronaVersion = "1.0.1"
  val nettyVersion = "3.10.6.Final"
  val jacksonVersion = "2.9.9"
  val jacksonDatabindVersion = "2.9.9.3"

  val scala212Version = "2.12.9"
  val scala213Version = "2.13.0"

  val sslConfigVersion = "0.3.8"

  val Versions = Seq(
    crossScalaVersions := Seq(scala212Version, scala213Version),
    scalaVersion := System.getProperty("akka.build.scalaVersion", crossScalaVersions.value.head),
    scalaCheckVersion := sys.props.get("akka.build.scalaCheckVersion").getOrElse("1.14.0"),
    scalaTestVersion := {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, n)) if n >= 13 => "3.0.8"
        case _                       => "3.0.7"
      }
    },
    java8CompatVersion := {
      CrossVersion.partialVersion(scalaVersion.value) match {
        // java8-compat is only used in a couple of places for 2.13,
        // it is probably possible to remove the dependency if needed.
        case Some((2, n)) if n >= 13 => "0.9.0"
        case _                       => "0.8.0"
      }
    })

  object Compile {
    // Compile

    val config = "com.typesafe" % "config" % "1.3.4" // ApacheV2
    val netty = "io.netty" % "netty" % nettyVersion // ApacheV2

    val scalaXml = "org.scala-lang.modules" %% "scala-xml" % scalaXmlVersion // Scala License
    val scalaReflect = ScalaVersionDependentModuleID.versioned("org.scala-lang" % "scala-reflect" % _) // Scala License

    val slf4jApi = "org.slf4j" % "slf4j-api" % slf4jVersion // MIT

    // mirrored in OSGi sample https://github.com/akka/akka-samples/tree/master/akka-sample-osgi-dining-hakkers
    val osgiCore = "org.osgi" % "org.osgi.core" % "4.3.1" // ApacheV2
    val osgiCompendium = "org.osgi" % "org.osgi.compendium" % "4.3.1" // ApacheV2

    val sigar = "org.fusesource" % "sigar" % "1.6.4" // ApacheV2

    val jctools = "org.jctools" % "jctools-core" % "2.1.2" // ApacheV2

    // reactive streams
    val reactiveStreams = "org.reactivestreams" % "reactive-streams" % "1.0.2" // CC0

    // ssl-config
    val sslConfigCore = Def.setting { "com.typesafe" %% "ssl-config-core" % sslConfigVersion } // ApacheV2

    val lmdb = "org.lmdbjava" % "lmdbjava" % "0.6.1" // ApacheV2, OpenLDAP Public License

    val junit = "junit" % "junit" % junitVersion // Common Public License 1.0

    // For Java 8 Conversions
    val java8Compat = Def.setting { "org.scala-lang.modules" %% "scala-java8-compat" % java8CompatVersion.value } // Scala License

    val aeronDriver = "io.aeron" % "aeron-driver" % aeronVersion // ApacheV2
    val aeronClient = "io.aeron" % "aeron-client" % aeronVersion // ApacheV2
    // Added explicitly for when artery tcp is used
    val agrona = "org.agrona" % "agrona" % agronaVersion // ApacheV2

    val jacksonCore = "com.fasterxml.jackson.core" % "jackson-core" % jacksonVersion // ApacheV2
    val jacksonAnnotations = "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonVersion // ApacheV2
    val jacksonDatabind = "com.fasterxml.jackson.core" % "jackson-databind" % jacksonDatabindVersion // ApacheV2
    val jacksonJdk8 = "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % jacksonVersion // ApacheV2
    val jacksonJsr310 = "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonVersion // ApacheV2
    val jacksonScala = "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion // ApacheV2
    val jacksonParameterNames = "com.fasterxml.jackson.module" % "jackson-module-parameter-names" % jacksonVersion // ApacheV2
    val jacksonCbor = "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % jacksonVersion // ApacheV2

    val protobufRuntime = "com.google.protobuf" % "protobuf-java" % "3.9.0"

    object Docs {
      val sprayJson = "io.spray" %% "spray-json" % "1.3.5" % "test"
      val gson = "com.google.code.gson" % "gson" % "2.8.5" % "test"
    }

    object Test {
      val commonsMath = "org.apache.commons" % "commons-math" % "2.2" % "test" // ApacheV2
      val commonsIo = "commons-io" % "commons-io" % "2.6" % "test" // ApacheV2
      val commonsCodec = "commons-codec" % "commons-codec" % "1.11" % "test" // ApacheV2
      val junit = "junit" % "junit" % junitVersion % "test" // Common Public License 1.0
      val logback = "ch.qos.logback" % "logback-classic" % "1.2.3" % "test" // EPL 1.0 / LGPL 2.1
      val mockito = "org.mockito" % "mockito-core" % "2.19.1" % "test" // MIT
      // changing the scalatest dependency must be reflected in akka-docs/rst/dev/multi-jvm-testing.rst
      val scalatest = Def.setting { "org.scalatest" %% "scalatest" % scalaTestVersion.value % "test" } // ApacheV2
      val scalacheck = Def.setting { "org.scalacheck" %% "scalacheck" % scalaCheckVersion.value % "test" } // New BSD
      val pojosr = "com.googlecode.pojosr" % "de.kalpatec.pojosr.framework" % "0.2.1" % "test" // ApacheV2
      val tinybundles = "org.ops4j.pax.tinybundles" % "tinybundles" % "1.0.0" % "test" // ApacheV2
      val log4j = "log4j" % "log4j" % "1.2.17" % "test" // ApacheV2
      val scalaXml = "org.scala-lang.modules" %% "scala-xml" % scalaXmlVersion % "test"

      // in-memory filesystem for file related tests
      val jimfs = "com.google.jimfs" % "jimfs" % "1.1" % "test" // ApacheV2

      // docker utils
      val dockerClient = "com.spotify" % "docker-client" % "8.13.1" % "test" // ApacheV2

      // metrics, measurements, perf testing
      val metrics = "io.dropwizard.metrics" % "metrics-core" % "3.2.5" % "test" // ApacheV2
      val metricsJvm = "io.dropwizard.metrics" % "metrics-jvm" % "3.2.5" % "test" // ApacheV2
      val latencyUtils = "org.latencyutils" % "LatencyUtils" % "1.0.5" % "test" // Free BSD
      val hdrHistogram = "org.hdrhistogram" % "HdrHistogram" % "2.1.10" % "test" // CC0
      val metricsAll = Seq(metrics, metricsJvm, latencyUtils, hdrHistogram)

      // sigar logging
      val slf4jJul = "org.slf4j" % "jul-to-slf4j" % slf4jVersion % "test" // MIT
      val slf4jLog4j = "org.slf4j" % "log4j-over-slf4j" % slf4jVersion % "test" // MIT

      // reactive streams tck
      val reactiveStreamsTck = "org.reactivestreams" % "reactive-streams-tck" % "1.0.3-RC1" % "test" // CC0

      val protobufRuntime = "com.google.protobuf" % "protobuf-java" % "3.9.0" % "test"
    }

    object Provided {
      // TODO remove from "test" config
      // If changed, update akka-docs/build.sbt as well
      val sigarLoader = "io.kamon" % "sigar-loader" % "1.6.6-rev002" % "optional;provided;test" // ApacheV2

      val activation = "com.sun.activation" % "javax.activation" % "1.2.0" % "provided;test"

      val levelDB = "org.iq80.leveldb" % "leveldb" % "0.10" % "optional;provided" // ApacheV2
      val levelDBmultiJVM = "org.iq80.leveldb" % "leveldb" % "0.10" % "optional;provided;multi-jvm" // ApacheV2
      val levelDBNative = "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8" % "optional;provided" // New BSD

      val junit = Compile.junit % "optional;provided;test"

      val scalatest = Def.setting { "org.scalatest" %% "scalatest" % scalaTestVersion.value % "optional;provided;test" } // ApacheV2

    }

  }

  import Compile._
  // TODO check if `l ++=` everywhere expensive?
  val l = libraryDependencies

  val actor = l ++= Seq(config, java8Compat.value)

  val discovery = l ++= Seq(Test.junit, Test.scalatest.value)

  val coordination = l ++= Seq(Test.junit, Test.scalatest.value)

  val testkit = l ++= Seq(Test.junit, Test.scalatest.value) ++ Test.metricsAll

  val actorTests = l ++= Seq(
        Test.junit,
        Test.scalatest.value,
        Test.commonsCodec,
        Test.commonsMath,
        Test.mockito,
        Test.scalacheck.value,
        Test.jimfs,
        Test.dockerClient,
        Provided.activation // dockerClient needs javax.activation.DataSource in JDK 11+
      )

  val actorTestkitTyped = l ++= Seq(Provided.junit, Provided.scalatest.value)

  val remoteDependencies = Seq(netty, aeronDriver, aeronClient)
  val remoteOptionalDependencies = remoteDependencies.map(_ % "optional")

  val remote = l ++= Seq(agrona, Test.junit, Test.scalatest.value, Test.jimfs, Test.protobufRuntime) ++ remoteOptionalDependencies

  val remoteTests = l ++= Seq(Test.junit, Test.scalatest.value, Test.scalaXml) ++ remoteDependencies

  val multiNodeTestkit = l ++= Seq(netty)

  val cluster = l ++= Seq(Test.junit, Test.scalatest.value)

  val clusterTools = l ++= Seq(Test.junit, Test.scalatest.value)

  val clusterSharding = l ++= Seq(
        Provided.levelDBmultiJVM,
        Provided.levelDBNative,
        Test.junit,
        Test.scalatest.value,
        Test.commonsIo)

  val clusterMetrics = l ++= Seq(Provided.sigarLoader, Test.slf4jJul, Test.slf4jLog4j, Test.logback, Test.mockito)

  val distributedData = l ++= Seq(lmdb, Test.junit, Test.scalatest.value)

  val slf4j = l ++= Seq(slf4jApi, Test.logback)

  val persistence = l ++= Seq(
        Provided.levelDB,
        Provided.levelDBNative,
        Test.scalatest.value,
        Test.junit,
        Test.commonsIo,
        Test.commonsCodec,
        Test.scalaXml)

  val persistenceQuery = l ++= Seq(
        Test.scalatest.value,
        Test.junit,
        Test.commonsIo,
        Provided.levelDB,
        Provided.levelDBNative)

  val persistenceTck = l ++= Seq(
        Test.scalatest.value.withConfigurations(Some("compile")),
        Test.junit.withConfigurations(Some("compile")),
        Provided.levelDB,
        Provided.levelDBNative)

  val persistenceShared = l ++= Seq(Provided.levelDB, Provided.levelDBNative)

  val jackson = l ++= Seq(
        jacksonCore,
        jacksonAnnotations,
        jacksonDatabind,
        jacksonScala,
        jacksonJdk8,
        jacksonJsr310,
        jacksonParameterNames,
        jacksonCbor,
        Test.junit,
        Test.scalatest.value)

  val osgi = l ++= Seq(
        osgiCore,
        osgiCompendium,
        Test.logback,
        Test.commonsIo,
        Test.pojosr,
        Test.tinybundles,
        Test.scalatest.value,
        Test.junit)

  val docs = l ++= Seq(Test.scalatest.value, Test.junit, Docs.sprayJson, Docs.gson, Provided.levelDB)

  val benchJmh = l ++= Seq(Provided.levelDB, Provided.levelDBNative, Compile.jctools)

  // akka stream

  lazy val stream = l ++= Seq[sbt.ModuleID](reactiveStreams, sslConfigCore.value, Test.scalatest.value)

  lazy val streamTestkit = l ++= Seq(Test.scalatest.value, Test.scalacheck.value, Test.junit)

  lazy val streamTests = l ++= Seq(Test.scalatest.value, Test.scalacheck.value, Test.junit, Test.commonsIo, Test.jimfs)

  lazy val streamTestsTck = l ++= Seq(Test.scalatest.value, Test.scalacheck.value, Test.junit, Test.reactiveStreamsTck)

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
    libraryDependencies ++= modules.flatMap(m => m.modules(scalaVersion.value))

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
}
