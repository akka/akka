/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

import sbt._
import sbt.Keys._

/**
 * Generate the "index" pages of stream operators.
 */
object StreamOperatorsIndexGenerator extends AutoPlugin {

  override val projectSettings: Seq[Setting[_]] = inConfig(Compile)(Seq(
    resourceGenerators +=
      generateAlphabeticalIndex(sourceDirectory,
        _ / "paradox" / "stream" / "operators" / "index.md"
      )
  ))

  val categories = Seq(
    "Source stages",
    "Sink stages",
    "Additional Sink and Source converters",
    "File IO Sinks and Sources",
    "Simple processing stages",
    "Flow stages composed of Sinks and Sources",
    "Asynchronous processing stages",
    "Timer driven stages",
    "Backpressure aware stages",
    "Nesting and flattening stages",
    "Time aware stages",
    "Fan-in stages",
    // TODO these don't show up as def's yet so don't show up in the index..
//    "Fan-out stages",
    "Watching status stages"
  )

  def categoryId(name: String): String = name.toLowerCase.replace(' ', '-')

  val pendingSourceOrFlow = Seq(
    "to",
    "toMat",
    "via",
    "viaMat",
    "async",
    "upcast",
    "shape",
    "run",
    "runWith",
    "traversalBuilder",
    "runFold",
    "runFoldAsync",
    "runForeach",
    "runReduce",
    "named",
    "throttleEven",
    "actorPublisher",
    "addAttributes",
    "mapMaterializedValue",
    // *Graph:
    "concatGraph",
    "prependGraph",
    "mergeSortedGraph",
    "fromGraph",
    "interleaveGraph",
    "zipGraph",
    "mergeGraph",
    "wireTapGraph",
    "alsoToGraph",
    "orElseGraph",
    "divertToGraph",
    "zipWithGraph"
  )

  // FIXME document these methods as well
  val pendingTestCases = Map(
    "Source" -> (pendingSourceOrFlow ++ Seq(
      "preMaterialize"
    )),
    "Flow" -> (pendingSourceOrFlow ++ Seq(
      "lazyInit",
      "fromProcessorMat",
      "toProcessor",
      "fromProcessor",
      "of",
      "join",
      "joinMat",
      "fromFunction"
    )),
    "Sink" -> Seq(
      "lazyInit",
      "collection",
      "contramap",
      "named",
      "addAttributes",
      "async",
      "mapMaterializedValue",
      "runWith",
      "shape",
      "traversalBuilder",
      "fromGraph",
      "actorSubscriber",
      "foldAsync",
      "newOnCompleteStage"
    ),
    "FileIO" -> Seq(
      "fromFile",
      "toFile"
    )
  )

  val ignore =
    Set("equals", "hashCode", "notify", "notifyAll", "wait", "toString", "getClass") ++
    Set("productArity", "canEqual", "productPrefix", "copy", "productIterator", "productElement") ++
    Set("create", "apply", "ops", "appendJava", "andThen", "andThenMat", "isIdentity", "withAttributes", "transformMaterializing") ++
    Set("asScala", "asJava", "deprecatedAndThen", "deprecatedAndThenMat") ++
    Set("++")

  def isPending(element: String, opName: String) =
    pendingTestCases.get(element).exists(_.contains(opName))

  def generateAlphabeticalIndex(dir: SettingKey[File], locate: File ⇒ File) = Def.task[Seq[File]] {
    val file = locate(dir.value)

    val defs =
      List(
        "akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala",
        "akka-stream/src/main/scala/akka/stream/javadsl/Source.scala",
//        "akka-stream/src/main/scala/akka/stream/scaladsl/SubSource.scala",
//        "akka-stream/src/main/scala/akka/stream/javadsl/SubSource.scala",
        "akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala",
        "akka-stream/src/main/scala/akka/stream/javadsl/Flow.scala",
//        "akka-stream/src/main/scala/akka/stream/scaladsl/SubFlow.scala",
//        "akka-stream/src/main/scala/akka/stream/javadsl/SubFlow.scala",
//        "akka-stream/src/main/scala/akka/stream/scaladsl/RunnableFlow.scala",
//        "akka-stream/src/main/scala/akka/stream/javadsl/RunnableFlow.scala",
        "akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala",
        "akka-stream/src/main/scala/akka/stream/javadsl/Sink.scala",
        "akka-stream/src/main/scala/akka/stream/scaladsl/StreamConverters.scala",
        "akka-stream/src/main/scala/akka/stream/javadsl/StreamConverters.scala",
        "akka-stream/src/main/scala/akka/stream/scaladsl/FileIO.scala",
        "akka-stream/src/main/scala/akka/stream/javadsl/FileIO.scala",
      ).flatMap{ f ⇒
        val element = f.split("/")(7).split("\\.")(0)
        IO.read(new File(f)).split("\n")
          .map(_.trim).filter(_.startsWith("def "))
          .map(_.drop(4).takeWhile(c ⇒ c != '[' && c != '(' && c != ':'))
          .filter(op => !isPending(element, op))
          .filter(op => !ignore.contains(op))
          .map(_.replaceAll("Mat$", ""))
          .map(method ⇒ (element, method))
      }

    val sourceAndFlow = defs.collect { case ("Source", method) => method } intersect defs.collect { case ("Flow", method) => method }

    val groupedDefs =
      defs.map {
        case (element @ ("Source" | "Flow"), method) if sourceAndFlow.contains(method) =>
          ("Source/Flow", method, s"Source-or-Flow/$method.md")
        case (element, method) =>
          (element, method, s"$element/$method.md")
      }.distinct

    val tablePerCategory = groupedDefs.map { case (element, method, md) =>
      val (description, category) = getDetails(file.getParentFile / md)
      category -> (element, method, md, description)
    }
      .groupBy(_._1)
      .mapValues(lines =>
        "| |Operator|Description|\n" ++
          "|--|--|--|\n" ++
          lines
            .map(_._2)
            .sortBy(_._2)
            .map { case (element, method, md, description) => s"|$element|@ref[$method]($md)|$description|" }
            .mkString("\n")
      )

    val tables = categories.map { category =>
      s"## $category\n\n" ++
        IO.read(dir.value / "categories" / (categoryId(category) + ".md")) ++ "\n\n" ++
        tablePerCategory(category)
    }.mkString("\n\n")

    val content =
      "# Operators\n\n" + tables + "\n\n@@@ index\n\n" +
        groupedDefs.map { case (_, method, md) => s"* [$method]($md)" }.mkString("\n") + "\n\n@@@\n"

    if (!file.exists || IO.read(file) != content) IO.write(file, content)
    Seq(file)
  }

  def getDetails(file: File): (String, String) = {
    val contents = IO.read(file).dropWhile(_ != '\n').drop(2)
    // This forces the short description to be on a single line. We could make this smarter,
    // but 'forcing' the short description to be really short seems nice as well.
    val description = contents.takeWhile(_ != '\n')
    val categoryLink = contents.dropWhile(_ != '\n').drop(2).takeWhile(_ != '\n')
    require(categoryLink.startsWith("@ref"), s"category link in $file, saw $categoryLink")
    val categoryName = categoryLink.drop(5).takeWhile(_ != ']')
    val categoryLinkId = categoryLink.dropWhile(_ != '#').drop(1).takeWhile(_ != ')')
    require(categories.contains(categoryName), s"category $categoryName in $file should be known")
    require(categoryLinkId  == categoryId(categoryName), s"category id $categoryLinkId in $file")
    (description, categoryName)
  }

}
