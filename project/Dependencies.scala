/*
 * Copyright (C) 2016-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import Keys._
import scala.language.implicitConversions

object Dependencies {
  import DependencyHelpers._

  lazy val java8CompatVersion = settingKey[String]("The version of scala-java8-compat to use.")
    .withRank(KeyRanks.Invisible) // avoid 'unused key' warning

  val junitVersion = "4.13.2"
  val slf4jVersion = "1.7.36"
  // check agrona version when updating this
  val aeronVersion = "1.40.0"
  // needs to be inline with the aeron version, check
  // https://github.com/real-logic/aeron/blob/1.x.y/build.gradle
  val agronaVersion = "1.17.1"
  val nettyVersion = "3.10.6.Final"
  val protobufJavaVersion = "3.16.1"
  val logbackVersion = "1.4.5"
  val scalaFortifyVersion = "1.0.22"
  val fortifySCAVersion = "22.1"
  val jacksonCoreVersion = "2.13.4"
  val jacksonDatabindVersion = "2.13.4.2"

  val scala212Version = "2.12.17"
  val scala213Version = "2.13.10"
  // To get the fix for https://github.com/lampepfl/dotty/issues/13106
  // and restored static forwarders
  val scala3Version = "3.1.3"
  val allScalaVersions = Seq(scala213Version, scala212Version, scala3Version)

  val reactiveStreamsVersion = "1.0.4"

  val sslConfigVersion = Def.setting {
    if (scalaVersion.value.startsWith("3.")) {
      "0.6.1"
    } else {
      "0.4.3"
    }
  }

  val scalaTestVersion = Def.setting {
    if (scalaVersion.value.startsWith("3.")) {
      "3.2.9"
    } else {
      "3.1.4"
    }
  }

  val scalaTestScalaCheckVersion = Def.setting {
    if (scalaVersion.value.startsWith("3.")) {
      "1-15"
    } else {
      "1-14"
    }
  }

  val scalaCheckVersion = "1.15.1"

  val Versions =
    Seq(crossScalaVersions := allScalaVersions, scalaVersion := allScalaVersions.head, java8CompatVersion := {
      CrossVersion.partialVersion(scalaVersion.value) match {
        // java8-compat is only used in a couple of places for 2.13,
        // it is probably possible to remove the dependency if needed.
        case Some((3, _))            => "1.0.0"
        case Some((2, n)) if n >= 13 => "1.0.0"
        case _                       => "0.8.0"
      }
    })

  object Compile {
    // Compile

    val config = "com.typesafe" % "config" % "1.4.2" // ApacheV2
    val netty = "io.netty" % "netty" % nettyVersion // ApacheV2

    val scalaReflect = ScalaVersionDependentModuleID.versioned("org.scala-lang" % "scala-reflect" % _) // Scala License

    val slf4jApi = "org.slf4j" % "slf4j-api" % slf4jVersion // MIT

    val sigar = "org.fusesource" % "sigar" % "1.6.4" // ApacheV2

    val jctools = "org.jctools" % "jctools-core" % "3.3.0" // ApacheV2

    // reactive streams
    val reactiveStreams = "org.reactivestreams" % "reactive-streams" % reactiveStreamsVersion // MIT-0

    // ssl-config
    val sslConfigCore = Def.setting {
      "com.typesafe" %% "ssl-config-core" % sslConfigVersion.value // ApacheV2
    }

    val lmdb = "org.lmdbjava" % "lmdbjava" % "0.7.0" // ApacheV2, OpenLDAP Public License

    val junit = "junit" % "junit" % junitVersion // Common Public License 1.0

    // For Java 8 Conversions
    val java8Compat = Def.setting {
      ("org.scala-lang.modules" %% "scala-java8-compat" % java8CompatVersion.value)
    } // Scala License

    val aeronDriver = "io.aeron" % "aeron-driver" % aeronVersion // ApacheV2
    val aeronClient = "io.aeron" % "aeron-client" % aeronVersion // ApacheV2
    // Added explicitly for when artery tcp is used
    val agrona = "org.agrona" % "agrona" % agronaVersion // ApacheV2

    val asnOne = ("com.hierynomus" % "asn-one" % "0.6.0").exclude("org.slf4j", "slf4j-api") // ApacheV2

    val jacksonCore = "com.fasterxml.jackson.core" % "jackson-core" % jacksonCoreVersion // ApacheV2
    val jacksonAnnotations = "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonCoreVersion // ApacheV2
    val jacksonDatabind = "com.fasterxml.jackson.core" % "jackson-databind" % jacksonDatabindVersion // ApacheV2
    val jacksonJdk8 = "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % jacksonCoreVersion // ApacheV2
    val jacksonJsr310 = "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonCoreVersion // ApacheV2
    val jacksonScala = "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonCoreVersion // ApacheV2
    val jacksonParameterNames = "com.fasterxml.jackson.module" % "jackson-module-parameter-names" % jacksonCoreVersion // ApacheV2
    val jacksonCbor = "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % jacksonCoreVersion // ApacheV2
    val lz4Java = "org.lz4" % "lz4-java" % "1.8.0" // ApacheV2

    val logback = "ch.qos.logback" % "logback-classic" % logbackVersion // EPL 1.0

    object Docs {
      val sprayJson = "io.spray" %% "spray-json" % "1.3.6" % Test
      val gson = "com.google.code.gson" % "gson" % "2.9.1" % Test
    }

    object TestDependencies {
      val commonsMath = "org.apache.commons" % "commons-math" % "2.2" % Test // ApacheV2
      val commonsIo = "commons-io" % "commons-io" % "2.11.0" % Test // ApacheV2
      val commonsCodec = "commons-codec" % "commons-codec" % "1.15" % Test // ApacheV2
      val junit = "junit" % "junit" % junitVersion % "test" // Common Public License 1.0
      val logback = Compile.logback % Test // EPL 1.0

      val scalatest = Def.setting { "org.scalatest" %% "scalatest" % scalaTestVersion.value % Test } // ApacheV2

      // The 'scalaTestPlus' projects are independently versioned,
      // but the version of each module starts with the scalatest
      // version it was intended to work with
      val scalatestJUnit = Def.setting {
        "org.scalatestplus" %% "junit-4-13" % (scalaTestVersion.value + ".0") % Test
      } // ApacheV2
      val scalatestTestNG = Def.setting {
        "org.scalatestplus" %% "testng-6-7" % (scalaTestVersion.value + ".0") % Test
      } // ApacheV2
      val scalatestScalaCheck = Def.setting {
        "org.scalatestplus" %% s"scalacheck-${scalaTestScalaCheckVersion.value}" % (scalaTestVersion.value + ".0") % Test
      } // ApacheV2
      val scalatestMockito = Def.setting {
        "org.scalatestplus" %% "mockito-3-4" % (scalaTestVersion.value + ".0") % Test
      } // ApacheV2

      val log4j = "log4j" % "log4j" % "1.2.17" % Test // ApacheV2

      // in-memory filesystem for file related tests
      val jimfs = "com.google.jimfs" % "jimfs" % "1.1" % Test // ApacheV2

      // docker utils
      val dockerClient = "com.spotify" % "docker-client" % "8.16.0" % Test // ApacheV2

      // metrics, measurements, perf testing
      val metrics = "io.dropwizard.metrics" % "metrics-core" % "4.2.12" % Test // ApacheV2
      val metricsJvm = "io.dropwizard.metrics" % "metrics-jvm" % "4.2.12" % Test // ApacheV2
      val latencyUtils = "org.latencyutils" % "LatencyUtils" % "2.0.3" % Test // Free BSD
      val hdrHistogram = "org.hdrhistogram" % "HdrHistogram" % "2.1.12" % Test // CC0
      val metricsAll = Seq(metrics, metricsJvm, latencyUtils, hdrHistogram)

      // sigar logging
      val slf4jJul = "org.slf4j" % "jul-to-slf4j" % slf4jVersion % Test // MIT
      val slf4jLog4j = "org.slf4j" % "log4j-over-slf4j" % slf4jVersion % Test // MIT

      // reactive streams tck
      val reactiveStreamsTck = ("org.reactivestreams" % "reactive-streams-tck" % reactiveStreamsVersion % Test)
        .exclude("org.testng", "testng") // MIT-0

      val protobufRuntime = "com.google.protobuf" % "protobuf-java" % protobufJavaVersion % Test

      // YCSB (Yahoo Cloud Serving Benchmark https://ycsb.site)
      val ycsb = "site.ycsb" % "core" % "0.17.0" % Test // ApacheV2
    }

    object Provided {
      // TODO remove from "test" config
      val sigarLoader = "io.kamon" % "sigar-loader" % "1.6.6-rev002" % "optional;provided;test" // ApacheV2

      val activation = "com.sun.activation" % "javax.activation" % "1.2.0" % "provided;test"

      val levelDB = "org.iq80.leveldb" % "leveldb" % "0.12" % "optional;provided" // ApacheV2
      val levelDBmultiJVM = "org.iq80.leveldb" % "leveldb" % "0.12" % "optional;provided;multi-jvm;test" // ApacheV2
      val levelDBNative = "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8" % "optional;provided" // New BSD

      val junit = Compile.junit % "optional;provided;test"

      val scalatest = Def.setting { "org.scalatest" %% "scalatest" % scalaTestVersion.value % "optional;provided;test" } // ApacheV2

      val logback = Compile.logback % "optional;provided;test" // EPL 1.0

      val protobufRuntime = "com.google.protobuf" % "protobuf-java" % protobufJavaVersion % "optional;provided"

    }

  }

  import Compile._
  // TODO check if `l ++=` everywhere expensive?
  val l = libraryDependencies

  val actor = l ++= Seq(config, java8Compat.value)

  val actorTyped = l ++= Seq(slf4jApi)

  val discovery = l ++= Seq(TestDependencies.junit, TestDependencies.scalatest.value)

  val coordination = l ++= Seq(TestDependencies.junit, TestDependencies.scalatest.value)

  val testkit = l ++= Seq(TestDependencies.junit, TestDependencies.scalatest.value) ++ TestDependencies.metricsAll

  val actorTests = l ++= Seq(
        TestDependencies.junit,
        TestDependencies.scalatest.value,
        TestDependencies.scalatestJUnit.value,
        TestDependencies.scalatestScalaCheck.value,
        TestDependencies.commonsCodec,
        TestDependencies.commonsMath,
        TestDependencies.jimfs,
        TestDependencies.dockerClient,
        Provided.activation // dockerClient needs javax.activation.DataSource in JDK 11+
      )

  val actorTestkitTyped = l ++= Seq(
        Provided.logback,
        Provided.junit,
        Provided.scalatest.value,
        TestDependencies.scalatestJUnit.value)

  val pki = l ++=
      Seq(
        asnOne,
        // pull up slf4j version from the one provided transitively in asnOne to fix unidoc
        Compile.slf4jApi,
        TestDependencies.scalatest.value)

  val remoteDependencies = Seq(aeronDriver, aeronClient)
  val remoteOptionalDependencies = remoteDependencies.map(_ % "optional")

  val remote = l ++= Seq(
        agrona,
        TestDependencies.junit,
        TestDependencies.scalatest.value,
        TestDependencies.jimfs,
        TestDependencies.protobufRuntime) ++ remoteOptionalDependencies

  val remoteTests = l ++= Seq(TestDependencies.junit, TestDependencies.scalatest.value) ++ remoteDependencies

  val multiNodeTestkit = l ++= Seq(netty)

  val cluster = l ++= Seq(TestDependencies.junit, TestDependencies.scalatest.value)

  val clusterTools = l ++= Seq(TestDependencies.junit, TestDependencies.scalatest.value)

  val clusterSharding = l ++= Seq(
        Provided.levelDBmultiJVM,
        Provided.levelDBNative,
        TestDependencies.junit,
        TestDependencies.scalatest.value,
        TestDependencies.commonsIo,
        TestDependencies.ycsb)

  val clusterMetrics = l ++= Seq(
        Provided.sigarLoader,
        TestDependencies.slf4jJul,
        TestDependencies.slf4jLog4j,
        TestDependencies.logback,
        TestDependencies.scalatestMockito.value)

  val distributedData = l ++= Seq(lmdb, TestDependencies.junit, TestDependencies.scalatest.value)

  val slf4j = l ++= Seq(slf4jApi, TestDependencies.logback)

  val persistence = l ++= Seq(
        Provided.levelDB,
        Provided.levelDBNative,
        TestDependencies.scalatest.value,
        TestDependencies.scalatestJUnit.value,
        TestDependencies.junit,
        TestDependencies.commonsIo,
        TestDependencies.commonsCodec)

  val persistenceQuery = l ++= Seq(
        TestDependencies.scalatest.value,
        TestDependencies.junit,
        TestDependencies.commonsIo,
        Provided.levelDB,
        Provided.levelDBNative)

  val persistenceTck = l ++= Seq(
        TestDependencies.scalatest.value.withConfigurations(Some("compile")),
        TestDependencies.junit.withConfigurations(Some("compile")),
        Provided.levelDB,
        Provided.levelDBNative)

  val persistenceTestKit = l ++= Seq(TestDependencies.scalatest.value, TestDependencies.logback)

  val persistenceTypedTests = l ++= Seq(TestDependencies.scalatest.value, TestDependencies.logback)

  val persistenceShared = l ++= Seq(Provided.levelDB, Provided.levelDBNative, TestDependencies.logback)

  val jackson = l ++= Seq(
        jacksonCore,
        jacksonAnnotations,
        jacksonDatabind,
        jacksonJdk8,
        jacksonJsr310,
        jacksonParameterNames,
        jacksonCbor,
        jacksonScala,
        lz4Java,
        TestDependencies.junit,
        TestDependencies.scalatest.value)

  val docs = l ++= Seq(
        TestDependencies.scalatest.value,
        TestDependencies.junit,
        Docs.sprayJson,
        Docs.gson,
        Provided.levelDB)

  val benchJmh = l ++= Seq(logback, Provided.levelDB, Provided.levelDBNative, Compile.jctools)

  // akka stream

  lazy val stream = l ++= Seq[sbt.ModuleID](reactiveStreams, sslConfigCore.value, TestDependencies.scalatest.value)

  lazy val streamTestkit = l ++= Seq(
        TestDependencies.scalatest.value,
        TestDependencies.scalatestScalaCheck.value,
        TestDependencies.junit)

  lazy val streamTests = l ++= Seq(
        TestDependencies.scalatest.value,
        TestDependencies.scalatestScalaCheck.value,
        TestDependencies.junit,
        TestDependencies.commonsIo,
        TestDependencies.jimfs)

  lazy val streamTestsTck = l ++= Seq(
        TestDependencies.scalatest.value,
        TestDependencies.scalatestTestNG.value,
        TestDependencies.scalatestScalaCheck.value,
        TestDependencies.junit,
        TestDependencies.reactiveStreamsTck)

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
