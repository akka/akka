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

  val jmhGenerator = taskKey[Seq[File]]("Instrumented JMH code, from compiled scala code")

  val settings = Seq(
    sourceGenerators in Compile <+= jmhGenerator in Compile,

    fork in Compile := false,

    jmhGenerator in Compile := {
      val out = target.value

      val jmhDir = out / "generated-sources" / "jmh"
      val compiledBytecodeDirectory = out / "classes"
      val outputSourceDirectory = jmhDir
      val outputResourceDirectory = compiledBytecodeDirectory

      val micro = classOf[GenerateMicroBenchmark]
      Thread.currentThread().setContextClassLoader(micro.getClassLoader)

      JmhBytecodeGenerator.main(Array(compiledBytecodeDirectory, outputSourceDirectory, outputResourceDirectory).map(_.toString))

      (outputSourceDirectory ** "*.java").filter(_.isFile).get
    }
  )

}