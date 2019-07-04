#!/bin/sh
exec scala "$0" "$@"
!#

/*
 * Usage:
 *    authors.scala tag1 tag2
 *
 * or if on non unixy os:
 *    scala authors.scala tag1 tag2
 *
 * requires scala 2.13.x+ and command line git on path
 */

import scala.sys.process._

require(args.length == 2, "usage: authors prevTag currTag")

val gitCmd = "git log --no-merges --shortstat -z --minimal -w -C " + args(0) + ".." + args(1)

case class Stats(name: String, email: String, commits: Int = 0, inserts: Int = 0, deletes: Int = 0, filesChanged: Int = 0)

val AuthorExp = """Author: (.*) <([^>]+)>""".r
val FilesExp = """(\d+)\sfile[s]? changed""".r
val InsertionsExp = """(\d+)\sinsertion[s]?\(\+\)""".r
val DeletionsExp = """(\d+)\sdeletion[s]?\(-\)""".r

val entries = gitCmd.lazyLines_!.foldLeft("")(_ + "\n" + _).split('\u0000')

val map = entries.foldLeft(Map.empty[String, Stats]) { (map, entry) =>
  val lines = entry.trim.split('\n')
  val authorLine = lines(1)
  val summary = lines.last

  val statsEntry = authorLine match {
    case AuthorExp(name, email) =>
      map.get(name.toLowerCase).orElse {
        // look for same email, but different name
        map.values.find(_.email.equalsIgnoreCase(email)).orElse {
          Some(Stats(name, email))
        }
      }
    case _ =>
      println(s"Unparseable author line: \n$authorLine\n in entry $entry")
      None
  }

  val updatedEntry =
    statsEntry.map(entry => summary.trim.split(',').map(_.trim).foldLeft(entry.copy(commits = entry.commits + 1)) {
      case (entry, FilesExp(f)) => entry.copy(filesChanged = entry.filesChanged + f.toInt)
      case (entry, InsertionsExp(a)) => entry.copy(inserts = entry.inserts + a.toInt)
      case (entry, DeletionsExp(d)) => entry.copy(deletes = entry.deletes + d.toInt)
      case (entry, uff) =>
        println(s"Couldn't parse summary section for $entry '$uff'")
        entry
    })

  updatedEntry.fold(
    map
  )(entry => map + (entry.name.toLowerCase -> entry))
}

val sorted = map.values.toSeq.sortBy(s => (s.commits, s.inserts + s.deletes)).reverse

println("commits  added  removed")
sorted.foreach { entry =>
  println("%7d%7d%9d %s".format(entry.commits, entry.inserts, entry.deletes, entry.name))
}

