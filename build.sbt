import akka.{ AutomaticModuleName, CopyrightHeaderForBuild, Paradox, ParadoxSupport, ScalafixIgnoreFilePlugin }

enablePlugins(
  UnidocRoot,
  UnidocWithPrValidation,
  NoPublish,
  CopyrightHeader,
  CopyrightHeaderInPr,
  ScalafixIgnoreFilePlugin,
  JavaFormatterPlugin)
disablePlugins(MimaPlugin)
addCommandAlias(
  name = "fixall",
  value = ";scalafixEnable;compile:scalafix;test:scalafix;multi-jvm:scalafix;test:compile;reload")

import akka.AkkaBuild._
import akka.{ AkkaBuild, Dependencies, GitHub, OSGi, Protobuf, SigarLoader, VersionGenerator }
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import com.typesafe.tools.mima.plugin.MimaPlugin
import sbt.Keys.{ initialCommands, parallelExecution }
import spray.boilerplate.BoilerplatePlugin

initialize := {
  // Load system properties from a file to make configuration from Jenkins easier
  loadSystemProperties("project/akka-build.properties")
  initialize.value
}

akka.AkkaBuild.buildSettings
shellPrompt := { s =>
  Project.extract(s).currentProject.id + " > "
}
resolverSettings

def isScala213: Boolean = System.getProperty("akka.build.scalaVersion", "").startsWith("2.13")

// When this is updated the set of modules in ActorSystem.allModules should also be updated
lazy val aggregatedProjects: Seq[ProjectReference] = List[ProjectReference](
  actor,
  actorTests,
  actorTestkitTyped,
  actorTyped,
  actorTypedTests,
  benchJmh,
  cluster,
  clusterMetrics,
  clusterSharding,
  clusterShardingTyped,
  clusterTools,
  clusterTyped,
  coordination,
  discovery,
  distributedData,
  docs,
  jackson,
  multiNodeTestkit,
  osgi,
  persistence,
  persistenceQuery,
  persistenceShared,
  persistenceTck,
  persistenceTyped,
  protobuf,
  protobufV3,
  remote,
  remoteTests,
  slf4j,
  stream,
  streamTestkit,
  streamTests,
  streamTestsTck,
  streamTyped,
  testkit)

lazy val root = Project(id = "akka", base = file("."))
  .aggregate(aggregatedProjects: _*)
  .settings(rootSettings: _*)
  .settings(
    unidocRootIgnoreProjects := Seq(remoteTests, benchJmh, protobuf, protobufV3, akkaScalaNightly, docs))
  .settings(unmanagedSources in (Compile, headerCreate) := (baseDirectory.value / "project").**("*.scala").get)
  .enablePlugins(CopyrightHeaderForBuild)

lazy val actor = akkaModule("akka-actor")
  .settings(Dependencies.actor)
  .settings(OSGi.actor)
  .settings(AutomaticModuleName.settings("akka.actor"))
  .settings(unmanagedSourceDirectories in Compile += {
    val ver = scalaVersion.value.take(4)
    (scalaSource in Compile).value.getParentFile / s"scala-$ver"
  })
  .settings(VersionGenerator.settings)
  .enablePlugins(BoilerplatePlugin)

lazy val actorTests = akkaModule("akka-actor-tests")
  .dependsOn(testkit % "compile->compile;test->test")
  .settings(Dependencies.actorTests)
  .enablePlugins(NoPublish)
  .disablePlugins(MimaPlugin, WhiteSourcePlugin)

lazy val akkaScalaNightly = akkaModule("akka-scala-nightly")
  .aggregate(aggregatedProjects: _*)
  .disablePlugins(MimaPlugin)
  .disablePlugins(ValidatePullRequest, MimaPlugin, CopyrightHeaderInPr)

lazy val benchJmh = akkaModule("akka-bench-jmh")
  .dependsOn(Seq(actor, actorTyped, stream, streamTests, persistence, distributedData, jackson, testkit).map(
    _ % "compile->compile;compile->test"): _*)
  .settings(Dependencies.benchJmh)
  .settings(javacOptions += "-parameters") // for Jackson
  .enablePlugins(JmhPlugin, ScaladocNoVerificationOfDiagrams, NoPublish, CopyrightHeader)
  .disablePlugins(MimaPlugin, WhiteSourcePlugin, ValidatePullRequest, CopyrightHeaderInPr)

lazy val cluster = akkaModule("akka-cluster")
  .dependsOn(remote, remoteTests % "test->test", testkit % "test->test", jackson % "test->test")
  .settings(Dependencies.cluster)
  .settings(AutomaticModuleName.settings("akka.cluster"))
  .settings(OSGi.cluster)
  .settings(Protobuf.settings)
  .settings(parallelExecution in Test := false)
  .configs(MultiJvm)
  .enablePlugins(MultiNodeScalaTest)

lazy val clusterMetrics = akkaModule("akka-cluster-metrics")
  .dependsOn(
    cluster % "compile->compile;test->test;multi-jvm->multi-jvm",
    slf4j % "test->compile",
    jackson % "test->test")
  .settings(OSGi.clusterMetrics)
  .settings(Dependencies.clusterMetrics)
  .settings(AutomaticModuleName.settings("akka.cluster.metrics"))
  .settings(Protobuf.settings)
  .settings(SigarLoader.sigarSettings)
  .settings(parallelExecution in Test := false)
  .configs(MultiJvm)
  .enablePlugins(MultiNodeScalaTest)

lazy val clusterSharding = akkaModule("akka-cluster-sharding")
// TODO akka-persistence dependency should be provided in pom.xml artifact.
//      If I only use "provided" here it works, but then we can't run tests.
//      Scope "test" is alright in the pom.xml, but would have been nicer with
//      provided.
  .dependsOn(
    cluster % "compile->compile;test->test;multi-jvm->multi-jvm",
    distributedData,
    persistence % "compile->compile",
    clusterTools % "compile->compile;test->test",
    jackson % "test->test")
  .settings(Dependencies.clusterSharding)
  .settings(AutomaticModuleName.settings("akka.cluster.sharding"))
  .settings(OSGi.clusterSharding)
  .settings(Protobuf.settings)
  .configs(MultiJvm)
  .enablePlugins(MultiNode, ScaladocNoVerificationOfDiagrams)

lazy val clusterTools = akkaModule("akka-cluster-tools")
  .dependsOn(cluster % "compile->compile;test->test;multi-jvm->multi-jvm", coordination, jackson % "test->test")
  .settings(Dependencies.clusterTools)
  .settings(AutomaticModuleName.settings("akka.cluster.tools"))
  .settings(OSGi.clusterTools)
  .settings(Protobuf.settings)
  .configs(MultiJvm)
  .enablePlugins(MultiNode, ScaladocNoVerificationOfDiagrams)

lazy val distributedData = akkaModule("akka-distributed-data")
  .dependsOn(cluster % "compile->compile;test->test;multi-jvm->multi-jvm", jackson % "test->test")
  .settings(Dependencies.distributedData)
  .settings(AutomaticModuleName.settings("akka.cluster.ddata"))
  .settings(OSGi.distributedData)
  .settings(Protobuf.settings)
  .configs(MultiJvm)
  .enablePlugins(MultiNodeScalaTest)

lazy val docs = akkaModule("akka-docs")
  .dependsOn(
    actor,
    cluster,
    clusterMetrics,
    slf4j,
    osgi,
    persistenceTck,
    persistenceQuery,
    distributedData,
    stream,
    actorTyped,
    clusterTools % "compile->compile;test->test",
    clusterSharding % "compile->compile;test->test",
    testkit % "compile->compile;test->test",
    remote % "compile->compile;test->test",
    persistence % "compile->compile;test->test",
    actorTyped % "compile->compile;test->test",
    persistenceTyped % "compile->compile;test->test",
    clusterTyped % "compile->compile;test->test",
    clusterShardingTyped % "compile->compile;test->test",
    actorTypedTests % "compile->compile;test->test",
    streamTestkit % "compile->compile;test->test")
  .settings(Dependencies.docs)
  .settings(Paradox.settings)
  .settings(ParadoxSupport.paradoxWithCustomDirectives)
  .enablePlugins(
    AkkaParadoxPlugin,
    DeployRsync,
    NoPublish,
    ParadoxBrowse,
    ScaladocNoVerificationOfDiagrams,
    StreamOperatorsIndexGenerator)
  .disablePlugins(MimaPlugin, WhiteSourcePlugin)
  .disablePlugins(ScalafixPlugin)

lazy val jackson = akkaModule("akka-serialization-jackson")
  .dependsOn(
    actor,
    actorTyped % "optional->compile",
    stream % "optional->compile",
    actorTests % "test->test",
    testkit % "test->test")
  .settings(Dependencies.jackson)
  .settings(AutomaticModuleName.settings("akka.serialization.jackson"))
  .settings(OSGi.jackson)
  .settings(javacOptions += "-parameters")
  .enablePlugins(ScaladocNoVerificationOfDiagrams)
  .disablePlugins(MimaPlugin)

lazy val multiNodeTestkit = akkaModule("akka-multi-node-testkit")
  .dependsOn(remote, testkit)
  .settings(Dependencies.multiNodeTestkit)
  .settings(Protobuf.settings)
  .settings(AutomaticModuleName.settings("akka.remote.testkit"))
  .settings(AkkaBuild.mayChangeSettings)

lazy val osgi = akkaModule("akka-osgi")
  .dependsOn(actor)
  .settings(Dependencies.osgi)
  .settings(AutomaticModuleName.settings("akka.osgi"))
  .settings(OSGi.osgi)
  .settings(parallelExecution in Test := false)

lazy val persistence = akkaModule("akka-persistence")
  .dependsOn(actor, stream, testkit % "test->test")
  .settings(Dependencies.persistence)
  .settings(AutomaticModuleName.settings("akka.persistence"))
  .settings(OSGi.persistence)
  .settings(Protobuf.settings)
  .settings(fork in Test := true)

lazy val persistenceQuery = akkaModule("akka-persistence-query")
  .dependsOn(stream, persistence % "compile->compile;test->test", streamTestkit % "test")
  .settings(Dependencies.persistenceQuery)
  .settings(AutomaticModuleName.settings("akka.persistence.query"))
  .settings(OSGi.persistenceQuery)
  .settings(fork in Test := true)
  .enablePlugins(ScaladocNoVerificationOfDiagrams)

lazy val persistenceShared = akkaModule("akka-persistence-shared")
  .dependsOn(persistence % "test->test", testkit % "test->test", remote % "test")
  .settings(Dependencies.persistenceShared)
  .settings(AutomaticModuleName.settings("akka.persistence.shared"))
  .settings(fork in Test := true)
  .enablePlugins(NoPublish)
  .disablePlugins(MimaPlugin, WhiteSourcePlugin)

lazy val persistenceTck = akkaModule("akka-persistence-tck")
  .dependsOn(persistence % "compile->compile;test->test", testkit % "compile->compile;test->test")
  .settings(Dependencies.persistenceTck)
  .settings(AutomaticModuleName.settings("akka.persistence.tck"))
  //.settings(OSGi.persistenceTck) TODO: we do need to export this as OSGi bundle too?
  .settings(fork in Test := true)
  .disablePlugins(MimaPlugin)

lazy val protobuf = akkaModule("akka-protobuf")
  .settings(OSGi.protobuf)
  .settings(AutomaticModuleName.settings("akka.protobuf"))
  .enablePlugins(ScaladocNoVerificationOfDiagrams)
  .disablePlugins(MimaPlugin)

lazy val protobufV3 = akkaModule("akka-protobuf-v3")
  .settings(AutomaticModuleName.settings("akka.protobuf.v3"))
  .enablePlugins(ScaladocNoVerificationOfDiagrams)
  .disablePlugins(MimaPlugin)
  .settings(
    libraryDependencies += Dependencies.Compile.protobufRuntime,
    assemblyShadeRules in assembly := Seq(
        ShadeRule
          .rename("com.google.protobuf.**" -> "akka.protobufv3.internal.@1")
          .inLibrary(Dependencies.Compile.protobufRuntime)),
    assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = false, includeBin = false),
    crossPaths := false,
    autoScalaLibrary := false, // do not include scala dependency in pom
    exportJars := true, // in dependent projects, use assembled and shaded jar
    makePomConfiguration := makePomConfiguration.value
        .withConfigurations(Vector(Compile)), // prevent original dependency to be added to pom as runtime dep
    packageBin in Compile := (assembly in Compile).value, // package by running assembly
    // Prevent cyclic task dependencies, see https://github.com/sbt/sbt-assembly/issues/365
    fullClasspath in assembly := (managedClasspath in Runtime).value, // otherwise, there's a cyclic dependency between packageBin and assembly
    test in assembly := {}, // assembly runs tests for unknown reason which introduces another cyclic dependency to packageBin via exportedJars
    description := "Akka Protobuf V3 is a shaded version of the protobuf runtime. Original POM: https://github.com/protocolbuffers/protobuf/blob/v3.9.0/java/pom.xml")

lazy val remote =
  akkaModule("akka-remote")
    .dependsOn(
      actor,
      stream,
      protobuf % "test",
      actorTests % "test->test",
      testkit % "test->test",
      streamTestkit % "test",
      jackson % "test->test")
    .settings(Dependencies.remote)
    .settings(AutomaticModuleName.settings("akka.remote"))
    .settings(OSGi.remote)
    .settings(Protobuf.settings)
    .settings(parallelExecution in Test := false)

lazy val remoteTests = akkaModule("akka-remote-tests")
  .dependsOn(
    actorTests % "test->test",
    remote % "test->test",
    streamTestkit % "test",
    multiNodeTestkit,
    jackson % "test->test")
  .settings(Dependencies.remoteTests)
  .settings(Protobuf.settings)
  .settings(parallelExecution in Test := false)
  .configs(MultiJvm)
  .enablePlugins(MultiNodeScalaTest, NoPublish)
  .disablePlugins(MimaPlugin, WhiteSourcePlugin)

lazy val slf4j = akkaModule("akka-slf4j")
  .dependsOn(actor, testkit % "test->test")
  .settings(Dependencies.slf4j)
  .settings(AutomaticModuleName.settings("akka.slf4j"))
  .settings(OSGi.slf4j)

lazy val stream = akkaModule("akka-stream")
  .dependsOn(actor, protobufV3)
  .settings(Dependencies.stream)
  .settings(AutomaticModuleName.settings("akka.stream"))
  .settings(OSGi.stream)
  .settings(Protobuf.settings)
  .enablePlugins(BoilerplatePlugin, Jdk9)

lazy val streamTestkit = akkaModule("akka-stream-testkit")
  .dependsOn(stream, testkit % "compile->compile;test->test")
  .settings(Dependencies.streamTestkit)
  .settings(AutomaticModuleName.settings("akka.stream.testkit"))
  .settings(OSGi.streamTestkit)
  .disablePlugins(MimaPlugin)

lazy val streamTests = akkaModule("akka-stream-tests")
  .dependsOn(streamTestkit % "test->test", remote % "test->test", stream)
  .settings(Dependencies.streamTests)
  .enablePlugins(NoPublish)
  .disablePlugins(MimaPlugin, WhiteSourcePlugin)

lazy val streamTestsTck = akkaModule("akka-stream-tests-tck")
  .dependsOn(streamTestkit % "test->test", stream)
  .settings(Dependencies.streamTestsTck)
  .settings(
    // These TCK tests are using System.gc(), which
    // is causing long GC pauses when running with G1 on
    // the CI build servers. Therefore we fork these tests
    // to run with small heap without G1.
    fork in Test := true)
  .enablePlugins(NoPublish)
  .disablePlugins(MimaPlugin, WhiteSourcePlugin)

lazy val testkit = akkaModule("akka-testkit")
  .dependsOn(actor)
  .settings(Dependencies.testkit)
  .settings(AutomaticModuleName.settings("akka.actor.testkit"))
  .settings(OSGi.testkit)
  .settings(initialCommands += "import akka.testkit._")

lazy val actorTyped = akkaModule("akka-actor-typed")
  .dependsOn(actor)
  .settings(AutomaticModuleName.settings("akka.actor.typed")) // fine for now, eventually new module name to become typed.actor
  .settings(Dependencies.actorTyped)
  .settings(OSGi.actorTyped)
  .settings(initialCommands :=
    """
      import akka.actor.typed._
      import akka.actor.typed.scaladsl.Behaviors
      import scala.concurrent._
      import scala.concurrent.duration._
      import akka.util.Timeout
      implicit val timeout = Timeout(5.seconds)
    """)

lazy val persistenceTyped = akkaModule("akka-persistence-typed")
  .dependsOn(
    actorTyped,
    persistence % "compile->compile;test->test",
    persistenceQuery % "test",
    actorTypedTests % "test->test",
    actorTestkitTyped % "test->test",
    jackson % "test->test")
  .settings(javacOptions += "-parameters") // for Jackson
  .settings(Dependencies.persistenceShared)
  .settings(AutomaticModuleName.settings("akka.persistence.typed"))
  .settings(OSGi.persistenceTyped)

lazy val clusterTyped = akkaModule("akka-cluster-typed")
  .dependsOn(
    actorTyped,
    cluster % "compile->compile;test->test;multi-jvm->multi-jvm",
    clusterTools,
    distributedData,
    persistence % "test->test",
    persistenceTyped % "test->test",
    actorTestkitTyped % "test->test",
    actorTypedTests % "test->test",
    remoteTests % "test->test",
    jackson % "test->test")
  .settings(AutomaticModuleName.settings("akka.cluster.typed"))
  .configs(MultiJvm)
  .enablePlugins(MultiNodeScalaTest)

lazy val clusterShardingTyped = akkaModule("akka-cluster-sharding-typed")
  .dependsOn(
    clusterTyped % "compile->compile;test->test;multi-jvm->multi-jvm",
    clusterSharding,
    actorTestkitTyped % "test->test",
    actorTypedTests % "test->test",
    persistenceTyped % "test->test",
    remoteTests % "test->test",
    jackson % "test->test")
  .settings(javacOptions += "-parameters") // for Jackson
  .settings(AutomaticModuleName.settings("akka.cluster.sharding.typed"))
  // To be able to import ContainerFormats.proto
  .settings(Protobuf.importPath := Some(baseDirectory.value / ".." / "akka-remote" / "src" / "main" / "protobuf"))
  .configs(MultiJvm)
  .enablePlugins(MultiNodeScalaTest)

lazy val streamTyped = akkaModule("akka-stream-typed")
  .dependsOn(
    actorTyped,
    stream,
    streamTestkit % "test->test",
    actorTestkitTyped % "test->test",
    actorTypedTests % "test->test")
  .settings(AutomaticModuleName.settings("akka.stream.typed"))
  .enablePlugins(ScaladocNoVerificationOfDiagrams)

lazy val actorTestkitTyped = akkaModule("akka-actor-testkit-typed")
  .dependsOn(actorTyped, slf4j, testkit % "compile->compile;test->test")
  .settings(AutomaticModuleName.settings("akka.actor.testkit.typed"))
  .settings(Dependencies.actorTestkitTyped)

lazy val actorTypedTests = akkaModule("akka-actor-typed-tests")
  .dependsOn(actorTyped, actorTestkitTyped % "compile->compile;test->test")
  .settings(AkkaBuild.mayChangeSettings)
  .disablePlugins(MimaPlugin)
  .enablePlugins(NoPublish)

lazy val discovery = akkaModule("akka-discovery")
  .dependsOn(actor, testkit % "test->test", actorTests % "test->test")
  .settings(Dependencies.discovery)
  .settings(AutomaticModuleName.settings("akka.discovery"))
  .settings(OSGi.discovery)

lazy val coordination = akkaModule("akka-coordination")
  .dependsOn(actor, testkit % "test->test", actorTests % "test->test", cluster % "test->test")
  .settings(Dependencies.coordination)
  .settings(AutomaticModuleName.settings("akka.coordination"))
  .settings(OSGi.coordination)

def akkaModule(name: String): Project =
  Project(id = name, base = file(name))
    .enablePlugins(ReproducibleBuildsPlugin)
    .settings(akka.AkkaBuild.buildSettings)
    .settings(akka.AkkaBuild.defaultSettings)
    .enablePlugins(BootstrapGenjavadoc)

/* Command aliases one can run locally against a module
  - where three or more tasks should be checked for faster turnaround
  - to avoid another push and CI cycle should mima or paradox fail.
  - the assumption is the user has already run tests, hence the test:compile. */
def commandValue(p: Project, externalTest: Option[Project] = None) = {
  val test = externalTest.getOrElse(p)
  val optionalMima = if (p.id.endsWith("-typed")) "" else s";${p.id}/mimaReportBinaryIssues"
  val optionalExternalTestFormat = externalTest.map(t => s";${t.id}/scalafmtAll").getOrElse("")
  s";${p.id}/scalafmtAll$optionalExternalTestFormat;${test.id}/test:compile$optionalMima;${docs.id}/paradox;${test.id}:validateCompile"
}
addCommandAlias("allActor", commandValue(actor, Some(actorTests)))
addCommandAlias("allRemote", commandValue(remote, Some(remoteTests)))
addCommandAlias("allClusterCore", commandValue(cluster))
addCommandAlias("allClusterMetrics", commandValue(clusterMetrics))
addCommandAlias("allClusterSharding", commandValue(clusterSharding))
addCommandAlias("allClusterTools", commandValue(clusterTools))
addCommandAlias(
  "allCluster",
  Seq(commandValue(cluster), commandValue(distributedData), commandValue(clusterSharding), commandValue(clusterTools)).mkString)
addCommandAlias("allCoordination", commandValue(coordination))
addCommandAlias("allDistributedData", commandValue(distributedData))
addCommandAlias("allPersistence", commandValue(persistence))
addCommandAlias("allStream", commandValue(stream, Some(streamTests)))
addCommandAlias("allDiscovery", commandValue(discovery))
addCommandAlias(
  "allTyped",
  Seq(
    commandValue(actorTyped, Some(actorTypedTests)),
    commandValue(actorTestkitTyped),
    commandValue(clusterTyped),
    commandValue(clusterShardingTyped),
    commandValue(persistenceTyped),
    commandValue(streamTyped)).mkString)
