/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka

import sbt._
import Keys._
import org.openjdk.jmh.generators.bytecode._
import org.openjdk.jmh.annotations.GenerateMicroBenchmark
import sbt.File

object Jmh {

  val jmhGenerator = taskKey[Seq[File]]("Generate instrumented JMH code")

  val settings = Seq(
    sourceGenerators in Compile <+= jmhGenerator in Compile,

    mainClass in (Compile, run) := Some("org.openjdk.jmh.Main"),

    fork in (Compile, run) := true, // manages classpath for JMH when forking

    jmhGenerator in Compile := {
      val out = target.value

      val compiledBytecodeDirectory = out / "classes"
      val outputSourceDirectory = out / "generated-sources" / "jmh"
      val outputResourceDirectory = compiledBytecodeDirectory

      val micro = classOf[GenerateMicroBenchmark]
      Thread.currentThread().setContextClassLoader(micro.getClassLoader)

      JmhBytecodeGenerator.main(Array(compiledBytecodeDirectory, outputSourceDirectory, outputResourceDirectory).map(_.toString))

      (outputSourceDirectory ** "*").filter(_.isFile).get
    }
  )

}