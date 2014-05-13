/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.jmh

import org.openjdk.jmh
import org.openjdk.jmh.ForkedMain
import org.openjdk.jmh.runner.{Runner, MicroBenchmarkList}
import org.openjdk.jmh.output.format.{TextReportFormat, OutputFormat}
import java.util.Collections
import org.openjdk.jmh.runner.options.{OptionsBuilder, VerboseMode}

object JmhRunnerApp extends App {

  val fm = classOf[ForkedMain]
  Thread.currentThread.setContextClassLoader(fm.getClassLoader)

  println("args = " + args.toList)

  val format = new TextReportFormat(System.out, VerboseMode.NORMAL)
  println("MicroBenchmarkList.defaultList() = " + MicroBenchmarkList.defaultList().getAll(format, Collections.emptyList()))

  jmh.Main.main(args)
}
