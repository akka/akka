enablePlugins(akka.UnidocRoot, akka.TimeStampede, akka.UnidocWithPrValidation)
disablePlugins(MimaPlugin)
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import com.typesafe.tools.mima.plugin.MimaPlugin
import akka.AkkaBuild._

initialize := {
  // Load system properties from a file to make configuration from Jenkins easier
  loadSystemProperties("project/akka-build.properties")
  initialize.value
}

akka.AkkaBuild.buildSettings
shellPrompt := { s => Project.extract(s).currentProject.id + " > " }
resolverSettings

lazy val aggregatedProjects: Seq[ProjectReference] = Seq(
  actor, actorTests,
  agent,
  benchJmh,
  camel,
  cluster, clusterMetrics, clusterSharding, clusterTools,
  contrib,
  distributedData,
  docs,
  multiNodeTestkit,
  osgi,
  persistence, persistenceQuery, persistenceShared, persistenceTck,
  protobuf,
  remote, remoteTests,
  slf4j,
  stream, streamTestkit, streamTests, streamTestsTck,
  testkit,
  typed, typedTests, typedTestkit
)

lazy val root = Project(
  id = "akka",
  base = file(".")
).aggregate(aggregatedProjects: _*)
 .settings(rootSettings: _*)
 .settings(unidocRootIgnoreProjects := Seq(remoteTests, benchJmh, protobuf, akkaScalaNightly, docs))

lazy val actor = akkaModule("akka-actor")

lazy val actorTests = akkaModule("akka-actor-tests")
  .dependsOn(testkit % "compile->compile;test->test")

lazy val agent = akkaModule("akka-agent")
  .dependsOn(actor, testkit % "test->test")

lazy val akkaScalaNightly = akkaModule("akka-scala-nightly")
  // remove dependencies that we have to build ourselves (Scala STM)
  .aggregate(aggregatedProjects diff List[ProjectReference](agent, docs): _*)
  .disablePlugins(ValidatePullRequest, MimaPlugin)

lazy val benchJmh = akkaModule("akka-bench-jmh")
  .dependsOn(
    Seq(
      actor,
      stream, streamTests,
      persistence, distributedData,
      testkit
    ).map(_ % "compile->compile;compile->test;provided->provided"): _*
  ).disablePlugins(ValidatePullRequest)

lazy val camel = akkaModule("akka-camel")
  .dependsOn(actor, slf4j, testkit % "test->test")

lazy val cluster = akkaModule("akka-cluster")
  .dependsOn(remote, remoteTests % "test->test" , testkit % "test->test")
  .configs(MultiJvm)

lazy val clusterMetrics = akkaModule("akka-cluster-metrics")
  .dependsOn(cluster % "compile->compile;test->test;multi-jvm->multi-jvm", slf4j % "test->compile")
  .configs(MultiJvm)

lazy val clusterSharding = akkaModule("akka-cluster-sharding")
  // TODO akka-persistence dependency should be provided in pom.xml artifact.
  //      If I only use "provided" here it works, but then we can't run tests.
  //      Scope "test" is alright in the pom.xml, but would have been nicer with
  //      provided.
  .dependsOn(
  cluster % "compile->compile;test->test;multi-jvm->multi-jvm",
  distributedData,
  persistence % "compile->compile;test->provided",
  clusterTools)
  .configs(MultiJvm)

lazy val clusterTools = akkaModule("akka-cluster-tools")
  .dependsOn(cluster % "compile->compile;test->test;multi-jvm->multi-jvm")
  .configs(MultiJvm)

lazy val contrib = akkaModule("akka-contrib")
  .dependsOn(remote, remoteTests % "test->test", cluster, clusterTools, persistence % "compile->compile;test->provided")
  .configs(MultiJvm)

lazy val distributedData = akkaModule("akka-distributed-data")
  .dependsOn(cluster % "compile->compile;test->test;multi-jvm->multi-jvm")
  .configs(MultiJvm)

lazy val docs = akkaModule("akka-docs")
  .dependsOn(
    actor, cluster, clusterMetrics, slf4j, agent, camel, osgi, persistenceTck, persistenceQuery, distributedData, stream,
    clusterTools % "compile->compile;test->test",
    testkit % "compile->compile;test->test",
    remote % "compile->compile;test->test",
    persistence % "compile->compile;provided->provided;test->test",
    typed % "compile->compile;test->test",
    typedTests % "compile->compile;test->test",
    streamTestkit % "compile->compile;test->test"
  )

lazy val multiNodeTestkit = akkaModule("akka-multi-node-testkit")
  .dependsOn(remote, testkit)

lazy val osgi = akkaModule("akka-osgi")
  .dependsOn(actor)

lazy val persistence = akkaModule("akka-persistence")
  .dependsOn(actor, testkit % "test->test", protobuf)

lazy val persistenceQuery = akkaModule("akka-persistence-query")
  .dependsOn(
    stream,
    persistence % "compile->compile;provided->provided;test->test",
    streamTestkit % "test")

lazy val persistenceShared = akkaModule("akka-persistence-shared")
  .dependsOn(persistence % "test->test", testkit % "test->test", remote % "test", protobuf)

lazy val persistenceTck = akkaModule("akka-persistence-tck")
  .dependsOn(persistence % "compile->compile;provided->provided;test->test", testkit % "compile->compile;test->test")

lazy val protobuf = akkaModule("akka-protobuf")

lazy val remote = akkaModule("akka-remote")
  .dependsOn(actor, stream, actorTests % "test->test", testkit % "test->test", streamTestkit % "test", protobuf)

lazy val remoteTests = akkaModule("akka-remote-tests")
  .dependsOn(actorTests % "test->test", remote % "test->test", streamTestkit % "test", multiNodeTestkit)
  .configs(MultiJvm)

lazy val slf4j = akkaModule("akka-slf4j")
  .dependsOn(actor, testkit % "test->test")

lazy val stream = akkaModule("akka-stream")
  .dependsOn(actor)

lazy val streamTestkit = akkaModule("akka-stream-testkit")
  .dependsOn(stream, testkit % "compile->compile;test->test")

lazy val streamTests = akkaModule("akka-stream-tests")
  .dependsOn(streamTestkit % "test->test", stream)

lazy val streamTestsTck = akkaModule("akka-stream-tests-tck")
  .dependsOn(streamTestkit % "test->test", stream)

lazy val typed = akkaModule("akka-typed")
  .dependsOn(testkit % "compile->compile;test->test")

lazy val typedTests = akkaModule("akka-typed-tests")
  .dependsOn(typed, typedTestkit % "compile->compile;test->test")

lazy val typedTestkit = akkaModule("akka-typed-testkit")
  .dependsOn(typed, testkit % "compile->compile;test->test")

lazy val testkit = akkaModule("akka-testkit")
  .dependsOn(actor)




def akkaModule(name: String): Project =
  Project(id = name, base = file(name))
    .settings(akka.AkkaBuild.buildSettings)
