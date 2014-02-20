import sbt._
import sbt.Keys._
import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm

object AkkaSampleMultiNodeBuild extends Build {

  val akkaVersion = "2.3-SNAPSHOT"

  lazy val akkaSampleMultiNode = Project(
    id = "akka-sample-multi-node-scala",
    base = file("."),
    settings = Project.defaultSettings ++ SbtMultiJvm.multiJvmSettings ++ Seq(
      name := "akka-sample-multi-node-scala",
      version := "1.0",
      scalaVersion := "2.10.3",
      libraryDependencies ++= Seq(
        "com.typesafe.akka" %% "akka-remote" % akkaVersion,
        "com.typesafe.akka" %% "akka-multi-node-testkit" % akkaVersion,
        "org.scalatest" %% "scalatest" % "2.0" % "test"),
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
}
