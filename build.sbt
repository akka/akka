import akka.Dependencies.{ allScalaVersions, fortifySCAVersion, scalaFortifyVersion }
import akka.{ AutomaticModuleName, CopyrightHeaderForBuild, Paradox, ScalafixIgnoreFilePlugin }

import scala.language.postfixOps
import scala.sys.process._

ThisBuild / scalafixScalaBinaryVersion := scalaBinaryVersion.value

scalaVersion := allScalaVersions.head

enablePlugins(
  UnidocRoot,
  UnidocWithPrValidation,
  NoPublish,
  CopyrightHeader,
  CopyrightHeaderInPr,
  ScalafixIgnoreFilePlugin,
  JavaFormatterPlugin)
disablePlugins(MimaPlugin)

addCommandAlias("verifyCodeStyle", "scalafmtCheckAll; scalafmtSbtCheck; headerCheckAll")
addCommandAlias("applyCodeStyle", "headerCreateAll; scalafmtAll; scalafmtSbt")

addCommandAlias(
  name = "fixall",
  value = ";scalafixEnable; scalafixAll; scalafmtAll; test:compile; multi-jvm:compile; reload")

addCommandAlias(name = "sortImports", value = ";scalafixEnable; scalafixAll SortImports; scalafmtAll")

import akka.AkkaBuild._
import akka.{ AkkaBuild, Dependencies, Protobuf, SigarLoader, VersionGenerator }
import com.typesafe.sbt.MultiJvmPlugin.MultiJvmKeys.MultiJvm
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

// When this is updated the set of modules in ActorSystem.allModules should also be updated
lazy val userProjects: Seq[ProjectReference] = List[ProjectReference](
  actor,
  actorTestkitTyped,
  actorTyped,
  cluster,
  clusterMetrics,
  clusterSharding,
  clusterShardingTyped,
  clusterTools,
  clusterTyped,
  coordination,
  discovery,
  distributedData,
  jackson,
  multiNodeTestkit,
  persistence,
  persistenceQuery,
  persistenceTyped,
  persistenceTestkit,
  protobuf,
  protobufV3,
  pki,
  remote,
  slf4j,
  stream,
  streamTestkit,
  streamTyped,
  testkit)

lazy val aggregatedProjects: Seq[ProjectReference] = userProjects ++ List[ProjectReference](
    actorTests,
    actorTypedTests,
    benchJmh,
    docs,
    billOfMaterials,
    persistenceShared,
    persistenceTck,
    persistenceTypedTests,
    remoteTests,
    streamTests,
    streamTestsTck)

lazy val root = Project(id = "akka", base = file("."))
  .aggregate(aggregatedProjects: _*)
  .enablePlugins(PublishRsyncPlugin)
  .settings(rootSettings: _*)
  .settings(unidocRootIgnoreProjects := Seq(remoteTests, benchJmh, protobuf, protobufV3, akkaScalaNightly, docs))
  .settings(Compile / headerCreate / unmanagedSources := (baseDirectory.value / "project").**("*.scala").get)
  .settings(akka.AkkaBuild.welcomeSettings)
  .enablePlugins(CopyrightHeaderForBuild)

lazy val actor = akkaModule("akka-actor")
  .settings(Dependencies.actor)
  .settings(AutomaticModuleName.settings("akka.actor"))
  .settings(Compile / unmanagedSourceDirectories += {
    val ver = scalaVersion.value.take(4)
    (Compile / scalaSource).value.getParentFile / s"scala-$ver"
  })
  .settings(VersionGenerator.settings)
  .enablePlugins(BoilerplatePlugin)

lazy val actorTests = akkaModule("akka-actor-tests")
  .dependsOn(testkit % "compile->compile;test->test", actor)
  .settings(Dependencies.actorTests)
  .enablePlugins(NoPublish)
  .disablePlugins(MimaPlugin)

lazy val akkaScalaNightly = akkaModule("akka-scala-nightly")
  .aggregate(aggregatedProjects: _*)
  .disablePlugins(MimaPlugin)
  .disablePlugins(ValidatePullRequest, MimaPlugin, CopyrightHeaderInPr)

lazy val benchJmh = akkaModule("akka-bench-jmh")
  .enablePlugins(Jdk9)
  .dependsOn(Seq(actor, actorTyped, stream, streamTestkit, persistence, distributedData, jackson, testkit).map(
    _ % "compile->compile;compile->test"): _*)
  .settings(Dependencies.benchJmh)
  .settings(javacOptions += "-parameters") // for Jackson
  .enablePlugins(JmhPlugin, ScaladocNoVerificationOfDiagrams, NoPublish, CopyrightHeader)
  .disablePlugins(MimaPlugin, ValidatePullRequest, CopyrightHeaderInPr)

lazy val cluster = akkaModule("akka-cluster")
  .dependsOn(
    remote,
    coordination % "compile->compile;test->test",
    remoteTests % "test->test",
    testkit % "test->test",
    jackson % "test->test")
  .settings(Dependencies.cluster)
  .settings(AutomaticModuleName.settings("akka.cluster"))
  .settings(Protobuf.settings)
  .settings(Test / parallelExecution := false)
  .configs(MultiJvm)
  .enablePlugins(MultiNodeScalaTest)

lazy val clusterMetrics = akkaModule("akka-cluster-metrics")
  .dependsOn(
    cluster % "compile->compile;test->test;multi-jvm->multi-jvm",
    slf4j % "test->compile",
    jackson % "test->test")
  .settings(Dependencies.clusterMetrics)
  .settings(AutomaticModuleName.settings("akka.cluster.metrics"))
  .settings(Protobuf.settings)
  .settings(SigarLoader.sigarSettings)
  .settings(Test / parallelExecution := false)
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
  .settings(Protobuf.settings)
  .configs(MultiJvm)
  .enablePlugins(MultiNode, ScaladocNoVerificationOfDiagrams)
  .enablePlugins(Jdk9)

lazy val clusterTools = akkaModule("akka-cluster-tools")
  .dependsOn(
    cluster % "compile->compile;test->test;multi-jvm->multi-jvm",
    coordination % "compile->compile;test->test",
    jackson % "test->test")
  .settings(Dependencies.clusterTools)
  .settings(AutomaticModuleName.settings("akka.cluster.tools"))
  .settings(Protobuf.settings)
  .configs(MultiJvm)
  .enablePlugins(MultiNode, ScaladocNoVerificationOfDiagrams)

lazy val distributedData = akkaModule("akka-distributed-data")
  .dependsOn(cluster % "compile->compile;test->test;multi-jvm->multi-jvm", jackson % "test->test")
  .settings(Dependencies.distributedData)
  .settings(AutomaticModuleName.settings("akka.cluster.ddata"))
  .settings(Protobuf.settings)
  .configs(MultiJvm)
  .enablePlugins(MultiNodeScalaTest)

lazy val docs = akkaModule("akka-docs")
  .configs(akka.Jdk9.TestJdk9)
  .dependsOn(
    actor,
    cluster,
    clusterMetrics,
    slf4j,
    persistenceTck,
    persistenceQuery,
    distributedData,
    stream,
    stream % "TestJdk9->CompileJdk9",
    actorTyped,
    clusterTools % "compile->compile;test->test",
    clusterSharding % "compile->compile;test->test",
    discovery % "compile->compile;test->test",
    testkit % "compile->compile;test->test",
    remote % "compile->compile;test->test",
    persistence % "compile->compile;test->test",
    actorTyped % "compile->compile;test->test",
    persistenceTyped % "compile->compile;test->test",
    clusterTyped % "compile->compile;test->test",
    clusterShardingTyped % "compile->compile;test->test",
    actorTypedTests % "compile->compile;test->test",
    streamTestkit % "compile->compile;test->test",
    persistenceTestkit % "compile->compile;test->test")
  .settings(Dependencies.docs)
  .settings(AkkaDisciplinePlugin.docs)
  .settings(Paradox.settings)
  .settings(javacOptions += "-parameters") // for Jackson
  .enablePlugins(
    AkkaParadoxPlugin,
    PublishRsyncPlugin,
    NoPublish,
    ParadoxBrowse,
    ScaladocNoVerificationOfDiagrams,
    StreamOperatorsIndexGenerator,
    Jdk9)
  .disablePlugins(MimaPlugin)
  .disablePlugins((if (ScalafixSupport.fixTestScope) Nil else Seq(ScalafixPlugin)): _*)
  // TODO https://github.com/akka/akka/issues/30243
  .settings(crossScalaVersions -= akka.Dependencies.scala3Version)

lazy val jackson = akkaModule("akka-serialization-jackson")
  .dependsOn(
    actor,
    actorTyped % "optional->compile",
    stream % "optional->compile",
    actorTests % "test->test",
    testkit % "test->test")
  .settings(Dependencies.jackson)
  .settings(AutomaticModuleName.settings("akka.serialization.jackson"))
  .settings(javacOptions += "-parameters")
  .enablePlugins(ScaladocNoVerificationOfDiagrams)

lazy val multiNodeTestkit = akkaModule("akka-multi-node-testkit")
  .dependsOn(remote, testkit)
  .settings(Dependencies.multiNodeTestkit)
  .settings(Protobuf.settings)
  .settings(AutomaticModuleName.settings("akka.remote.testkit"))
  .settings(AkkaBuild.mayChangeSettings)

lazy val persistence = akkaModule("akka-persistence")
  .dependsOn(actor, stream, testkit % "test->test")
  .settings(Dependencies.persistence)
  .settings(AutomaticModuleName.settings("akka.persistence"))
  .settings(Protobuf.settings)
  .settings(Test / fork := true)

lazy val persistenceQuery = akkaModule("akka-persistence-query")
  .dependsOn(
    stream,
    persistence % "compile->compile;test->test",
    remote % "provided",
    protobufV3,
    streamTestkit % "test")
  .settings(Dependencies.persistenceQuery)
  .settings(AutomaticModuleName.settings("akka.persistence.query"))
  .settings(Protobuf.settings)
  // To be able to import ContainerFormats.proto
  .settings(Protobuf.importPath := Some(baseDirectory.value / ".." / "akka-remote" / "src" / "main" / "protobuf"))
  .settings(Test / fork := true)
  .enablePlugins(ScaladocNoVerificationOfDiagrams)

lazy val persistenceShared = akkaModule("akka-persistence-shared")
  .dependsOn(persistence % "test->test", testkit % "test->test", remote % "test")
  .settings(Dependencies.persistenceShared)
  .settings(AutomaticModuleName.settings("akka.persistence.shared"))
  .settings(Test / fork := true)
  .enablePlugins(NoPublish)
  .disablePlugins(MimaPlugin)

lazy val persistenceTck = akkaModule("akka-persistence-tck")
  .dependsOn(persistence % "compile->compile;test->test", testkit % "compile->compile;test->test")
  .settings(Dependencies.persistenceTck)
  .settings(AutomaticModuleName.settings("akka.persistence.tck"))
  .settings(Test / fork := true)
  .disablePlugins(MimaPlugin)

lazy val persistenceTestkit = akkaModule("akka-persistence-testkit")
  .dependsOn(
    persistenceTyped % "compile->compile;provided->provided;test->test",
    testkit % "compile->compile;test->test",
    actorTestkitTyped,
    persistenceTck % "test")
  .settings(Dependencies.persistenceTestKit)
  .settings(AutomaticModuleName.settings("akka.persistence.testkit"))
  .disablePlugins(MimaPlugin)

lazy val persistenceTypedTests = akkaModule("akka-persistence-typed-tests")
  .dependsOn(
    persistenceTyped,
    persistenceTestkit % "test",
    actorTestkitTyped % "test",
    persistence % "test->test", // for SteppingInMemJournal
    jackson % "test->test")
  .settings(AkkaBuild.mayChangeSettings)
  .settings(Dependencies.persistenceTypedTests)
  .settings(javacOptions += "-parameters") // for Jackson
  .disablePlugins(MimaPlugin)
  .enablePlugins(NoPublish)

lazy val protobuf = akkaModule("akka-protobuf")
  .settings(AutomaticModuleName.settings("akka.protobuf"))
  .enablePlugins(ScaladocNoVerificationOfDiagrams)
  .disablePlugins(MimaPlugin)
  .settings(autoScalaLibrary := false) // Pure java project

lazy val protobufV3 = akkaModule("akka-protobuf-v3")
  .settings(AutomaticModuleName.settings("akka.protobuf.v3"))
  .enablePlugins(ScaladocNoVerificationOfDiagrams)
  .disablePlugins(MimaPlugin)
  .settings(
    libraryDependencies += Dependencies.Compile.Provided.protobufRuntime,
    assembly / assemblyShadeRules := Seq(
        ShadeRule
          .rename("com.google.protobuf.**" -> "akka.protobufv3.internal.@1")
          // https://github.com/sbt/sbt-assembly/issues/400
          .inLibrary(Dependencies.Compile.Provided.protobufRuntime)
          .inProject),
    assembly / assemblyOption := (assembly / assemblyOption).value.withIncludeScala(false).withIncludeBin(false),
    autoScalaLibrary := false, // do not include scala dependency in pom
    exportJars := true, // in dependent projects, use assembled and shaded jar
    makePomConfiguration := makePomConfiguration.value
        .withConfigurations(Vector(Compile)), // prevent original dependency to be added to pom as runtime dep
    Compile / packageBin := ReproducibleBuildsPlugin
        .postProcessJar((Compile / assembly).value), // package by running assembly
    // Prevent cyclic task dependencies, see https://github.com/sbt/sbt-assembly/issues/365
    assembly / fullClasspath := (Runtime / managedClasspath).value, // otherwise, there's a cyclic dependency between packageBin and assembly
    assembly / test := {}, // assembly runs tests for unknown reason which introduces another cyclic dependency to packageBin via exportedJars
    description := "Akka Protobuf V3 is a shaded version of the protobuf runtime. Original POM: https://github.com/protocolbuffers/protobuf/blob/v3.9.0/java/pom.xml")

lazy val pki =
  akkaModule("akka-pki")
    .dependsOn(actor) // this dependency only exists for "@ApiMayChange"
    .settings(Dependencies.pki)
    .settings(AutomaticModuleName.settings("akka.pki"))

lazy val remote =
  akkaModule("akka-remote")
    .dependsOn(
      actor,
      stream,
      pki,
      protobuf % "test",
      actorTests % "test->test",
      testkit % "test->test",
      streamTestkit % "test",
      jackson % "test->test")
    .settings(Dependencies.remote)
    .settings(AutomaticModuleName.settings("akka.remote"))
    .settings(Protobuf.settings)
    .settings(Test / parallelExecution := false)
    .enablePlugins(Jdk9)

lazy val remoteTests = akkaModule("akka-remote-tests")
  .dependsOn(
    actorTests % "test->test",
    remote % "compile->CompileJdk9;test->test",
    streamTestkit % "test",
    multiNodeTestkit,
    jackson % "test->test")
  .settings(Dependencies.remoteTests)
  .settings(Protobuf.settings)
  .settings(Test / parallelExecution := false)
  .configs(MultiJvm)
  .enablePlugins(MultiNodeScalaTest, NoPublish)
  .disablePlugins(MimaPlugin)

lazy val slf4j = akkaModule("akka-slf4j")
  .dependsOn(actor, testkit % "test->test")
  .settings(Dependencies.slf4j)
  .settings(AutomaticModuleName.settings("akka.slf4j"))

lazy val stream = akkaModule("akka-stream")
  .dependsOn(actor, protobufV3)
  .settings(Dependencies.stream)
  .settings(AutomaticModuleName.settings("akka.stream"))
  .settings(Protobuf.settings)
  .enablePlugins(BoilerplatePlugin, Jdk9)

lazy val streamTestkit = akkaModule("akka-stream-testkit")
  .dependsOn(stream, testkit % "compile->compile;test->test")
  .settings(Dependencies.streamTestkit)
  .settings(AutomaticModuleName.settings("akka.stream.testkit"))

lazy val streamTests = akkaModule("akka-stream-tests")
  .configs(akka.Jdk9.TestJdk9)
  .dependsOn(streamTestkit % "test->test", remote % "test->test", stream % "TestJdk9->CompileJdk9")
  .settings(Dependencies.streamTests)
  .enablePlugins(NoPublish, Jdk9)
  .disablePlugins(MimaPlugin)

lazy val streamTestsTck = akkaModule("akka-stream-tests-tck")
  .dependsOn(streamTestkit % "test->test", stream)
  .settings(Dependencies.streamTestsTck)
  .settings(
    // These TCK tests are using System.gc(), which
    // is causing long GC pauses when running with G1 on
    // the CI build servers. Therefore we fork these tests
    // to run with small heap without G1.
    Test / fork := true)
  .enablePlugins(NoPublish)
  .disablePlugins(MimaPlugin)

lazy val testkit = akkaModule("akka-testkit")
  .dependsOn(actor)
  .settings(Dependencies.testkit)
  .settings(AutomaticModuleName.settings("akka.actor.testkit"))
  .settings(initialCommands += "import akka.testkit._")

lazy val actorTyped = akkaModule("akka-actor-typed")
  .dependsOn(actor, slf4j)
  .settings(AutomaticModuleName.settings("akka.actor.typed"))
  .settings(Dependencies.actorTyped)
  .settings(initialCommands :=
    """
      import akka.actor.typed._
      import akka.actor.typed.scaladsl.Behaviors
      import scala.concurrent._
      import scala.concurrent.duration._
      import akka.util.Timeout
      implicit val timeout = Timeout(5.seconds)
    """)
  .enablePlugins(Jdk9)

lazy val persistenceTyped = akkaModule("akka-persistence-typed")
  .dependsOn(
    actorTyped,
    streamTyped,
    remote,
    persistence % "compile->compile;test->test",
    persistenceQuery,
    actorTestkitTyped % "test->test",
    clusterTyped % "test->test",
    actorTestkitTyped % "test->test",
    jackson % "test->test")
  .settings(javacOptions += "-parameters") // for Jackson
  .settings(Dependencies.persistenceShared)
  .settings(AutomaticModuleName.settings("akka.persistence.typed"))
  .settings(Protobuf.settings)
  // To be able to import ContainerFormats.proto
  .settings(Protobuf.importPath := Some(baseDirectory.value / ".." / "akka-remote" / "src" / "main" / "protobuf"))

lazy val clusterTyped = akkaModule("akka-cluster-typed")
  .dependsOn(
    actorTyped,
    cluster % "compile->compile;test->test;multi-jvm->multi-jvm",
    clusterTools,
    distributedData,
    actorTestkitTyped % "test->test",
    actorTypedTests % "test->test",
    remoteTests % "test->test",
    jackson % "test->test")
  .settings(Protobuf.settings)
  // To be able to import ContainerFormats.proto
  .settings(Protobuf.importPath := Some(baseDirectory.value / ".." / "akka-remote" / "src" / "main" / "protobuf"))
  .settings(AutomaticModuleName.settings("akka.cluster.typed"))
  .settings(Protobuf.settings)
  // To be able to import ContainerFormats.proto
  .settings(Protobuf.importPath := Some(baseDirectory.value / ".." / "akka-remote" / "src" / "main" / "protobuf"))
  .configs(MultiJvm)
  .enablePlugins(MultiNodeScalaTest)

lazy val clusterShardingTyped = akkaModule("akka-cluster-sharding-typed")
  .dependsOn(
    actorTyped % "compile->CompileJdk9",
    clusterTyped % "compile->compile;test->test;multi-jvm->multi-jvm",
    clusterSharding % "compile->compile;compile->CompileJdk9;multi-jvm->multi-jvm",
    actorTestkitTyped % "test->test",
    actorTypedTests % "test->test",
    persistenceTyped % "optional->compile;test->test",
    persistenceTestkit % "test->test",
    remote % "compile->CompileJdk9;test->test",
    remoteTests % "test->test",
    remoteTests % "test->test;multi-jvm->multi-jvm",
    jackson % "test->test")
  .settings(javacOptions += "-parameters") // for Jackson
  .settings(AutomaticModuleName.settings("akka.cluster.sharding.typed"))
  // To be able to import ContainerFormats.proto
  .settings(Protobuf.settings)
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
  .dependsOn(actorTyped % "compile->CompileJdk9", actorTestkitTyped % "compile->compile;test->test", actor)
  .settings(AkkaBuild.mayChangeSettings)
  .disablePlugins(MimaPlugin)
  .enablePlugins(NoPublish)

lazy val discovery = akkaModule("akka-discovery")
  .dependsOn(actor, testkit % "test->test", actorTests % "test->test")
  .settings(Dependencies.discovery)
  .settings(AutomaticModuleName.settings("akka.discovery"))

lazy val coordination = akkaModule("akka-coordination")
  .dependsOn(actor, testkit % "test->test", actorTests % "test->test")
  .settings(Dependencies.coordination)
  .settings(AutomaticModuleName.settings("akka.coordination"))

lazy val billOfMaterials = Project("akka-bill-of-materials", file("akka-bill-of-materials"))
  .enablePlugins(BillOfMaterialsPlugin)
  .disablePlugins(MimaPlugin, AkkaDisciplinePlugin)
  // buildSettings and defaultSettings configure organization name, licenses, etc...
  .settings(AkkaBuild.buildSettings)
  .settings(AkkaBuild.defaultSettings)
  .settings(
    name := "akka-bom",
    bomIncludeProjects := userProjects,
    description := s"${description.value} (depending on Scala ${CrossVersion.binaryScalaVersion(scalaVersion.value)})")

lazy val doesFortifyLicenseExist: Boolean = {
  import java.nio.file.Files
  val home = System.getProperty("user.home")
  val fortifyLicense = new File(s"$home/.lightbend/fortify.license")
  Files.exists(fortifyLicense.toPath)
}

def fortifySettings(name: String) = {
  if (doesFortifyLicenseExist) {
    Seq(
      addCompilerPlugin(("com.lightbend" %% "scala-fortify" % scalaFortifyVersion).cross(CrossVersion.patch)),
      scalacOptions ++= Seq(s"-P:fortify:scaversion=$fortifySCAVersion", s"-P:fortify:build=$name"))
  } else {
    Seq()
  }
}

def akkaModule(name: String): Project =
  Project(id = name, base = file(name))
    .enablePlugins(ReproducibleBuildsPlugin)
    .disablePlugins(WelcomePlugin)
    .settings(akka.AkkaBuild.buildSettings)
    .settings(akka.AkkaBuild.defaultSettings)
    .settings(fortifySettings(name))
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

// Convenience task allowing all Fortify steps to be done in the sbt shell
lazy val analyzeSource = taskKey[Unit]("Analyzing NST files emitted by Fortify  SCA")
analyzeSource := {
  val s = streams.value
  val shell = Seq("bash", "-c")
  val scan = shell :+ s"./scripts/runSourceAnalyzer.sh $fortifySCAVersion"
  if (doesFortifyLicenseExist) {
    s.log.info("Analyzing NST files emitted by Fortify SCA.")
    scan !
  } else {
    s.log.info("Fortify license not found so NST files creation was skipped.")
  }
}

addCommandAlias("runSourceAnalyzer", "; clean; compile; analyzeSource")
