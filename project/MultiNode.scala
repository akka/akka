package akka

import akka.TestExtras.Filter
import akka.TestExtras.Filter.Keys._
import com.typesafe.sbt.{SbtScalariform, SbtMultiJvm}
import sbt._
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys._
import sbt.Keys._
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

object MultiNode {

  val multiNodeEnabled = sys.props.get("akka.test.multi-node").getOrElse("false").toBoolean

  lazy val defaultMultiJvmOptions: Seq[String] = {
    import scala.collection.JavaConverters._
    // multinode.D= and multinode.X= makes it possible to pass arbitrary
    // -D or -X arguments to the forked jvm, e.g.
    // -Dmultinode.Djava.net.preferIPv4Stack=true -Dmultinode.Xmx512m -Dmultinode.XX:MaxPermSize=256M
    // -DMultiJvm.akka.cluster.Stress.nrOfNodes=15
    val MultinodeJvmArgs = "multinode\\.(D|X)(.*)".r
    val knownPrefix = Set("multnode.", "akka.", "MultiJvm.")
    val akkaProperties = System.getProperties.propertyNames.asScala.toList.collect {
      case MultinodeJvmArgs(a, b) =>
        val value = System.getProperty("multinode." + a + b)
        "-" + a + b + (if (value == "") "" else "=" + value)
      case key: String if knownPrefix.exists(pre => key.startsWith(pre)) => "-D" + key + "=" + System.getProperty(key)
    }

    "-Xmx256m" :: akkaProperties :::
      (if (sys.props.get("sbt.log.noformat").getOrElse("false").toBoolean) List("-Dakka.test.nocolor=true") else Nil)
  }

  lazy val defaultMultiJvmScalatestOptions = Def.setting {
    Seq("-C", "org.scalatest.extra.QuietReporter") ++
      (if (excludeTestTags.value.isEmpty) Seq.empty else Seq("-l", if (multiNodeEnabled) excludeTestTags.value.mkString("\"", " ", "\"") else excludeTestTags.value.mkString(" "))) ++
      (if (onlyTestTags.value.isEmpty) Seq.empty else Seq("-n", if (multiNodeEnabled) onlyTestTags.value.mkString("\"", " ", "\"") else onlyTestTags.value.mkString(" ")))
  }

  lazy val multiJvmSettings = SbtMultiJvm.multiJvmSettings ++ inConfig(MultiJvm)(SbtScalariform.configScalariformSettings) ++ Seq(
    jvmOptions in MultiJvm := defaultMultiJvmOptions,
    compileInputs in(MultiJvm, compile) <<= (compileInputs in(MultiJvm, compile)) dependsOn (ScalariformKeys.format in MultiJvm),
    compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test)) ++
    Option(System.getProperty("akka.test.multi-node.hostsFileName")).map(x => Seq(multiNodeHostsFileName in MultiJvm := x)).getOrElse(Seq.empty) ++
    Option(System.getProperty("akka.test.multi-node.java")).map(x => Seq(multiNodeJavaName in MultiJvm := x)).getOrElse(Seq.empty) ++
    Option(System.getProperty("akka.test.multi-node.targetDirName")).map(x => Seq(multiNodeTargetDirName in MultiJvm := x)).getOrElse(Seq.empty) ++
    // make sure that MultiJvm tests are executed by the default test target, 
    // and combine the results from ordinary test and multi-jvm tests
    (if (multiNodeEnabled) {
        executeTests in Test <<= (executeTests in Test, multiNodeExecuteTests in MultiJvm) map {
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
    } else { 
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
    })

}
