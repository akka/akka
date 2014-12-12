import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm

val project = Project(
  id = "akka-sample-cluster-scala",
  base = file("."),
  settings = Project.defaultSettings ++ SbtMultiJvm.multiJvmSettings ++ Seq(
    name := "akka-sample-cluster-scala",
    version := "15v01p01",
    // scalaVersion := provided by Typesafe Reactive Platform
    scalacOptions in Compile ++= Seq("-encoding", "UTF-8", "-target:jvm-1.6", "-deprecation", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint"),
    javacOptions in Compile ++= Seq("-source", "1.6", "-target", "1.6", "-Xlint:unchecked", "-Xlint:deprecation"),
    libraryDependencies ++= Seq(
      TypesafeLibrary.akkaCluster.value,
      TypesafeLibrary.akkaMultiNodeTestkit.value,
      "com.typesafe.akka" %% "akka-contrib" % "2.3.7", // akka-contrib is not part of the RP
      "org.scalatest" %% "scalatest" % "2.2.1" % "test",
      "org.fusesource" % "sigar" % "1.6.4"),
    javaOptions in run ++= Seq(
      "-Djava.library.path=./sigar",
      "-Xms128m", "-Xmx1024m"),
    Keys.fork in run := true,  
    mainClass in (Compile, run) := Some("sample.cluster.simple.SimpleClusterApp"),
    // make sure that MultiJvm test are compiled by the default test compilation
    compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test),
    // disable parallel tests
    parallelExecution in Test := false,
    // make sure that MultiJvm tests are executed by the default test target, 
    // and combine the results from ordinary test and multi-jvm tests
    executeTests in Test <<= (executeTests in Test, executeTests in MultiJvm) map {
      case (testResults, multiNodeResults)  =>
        val overall =
          if (testResults.overall.id < multiNodeResults.overall.id)
            multiNodeResults.overall
          else
            testResults.overall
        Tests.Output(overall,
          testResults.events ++ multiNodeResults.events,
          testResults.summaries ++ multiNodeResults.summaries)
    }
  )
) configs (MultiJvm)
