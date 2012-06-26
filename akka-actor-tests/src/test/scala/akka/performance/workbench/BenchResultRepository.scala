package akka.performance.workbench

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import scala.collection.mutable.{ Map ⇒ MutableMap }
import akka.actor.ActorSystem
import akka.event.Logging

trait BenchResultRepository {
  def add(stats: Stats)

  def get(name: String): Seq[Stats]

  def get(name: String, load: Int): Option[Stats]

  def getWithHistorical(name: String, load: Int): Seq[Stats]

  def isBaseline(stats: Stats): Boolean

  def saveHtmlReport(content: String, name: String): Unit

  def htmlReportUrl(name: String): String

}

object BenchResultRepository {
  private val repository = new FileBenchResultRepository
  def apply(): BenchResultRepository = repository
}

class FileBenchResultRepository extends BenchResultRepository {
  private val statsByName = MutableMap[String, Seq[Stats]]()
  private val baselineStats = MutableMap[Key, Stats]()
  private val historicalStats = MutableMap[Key, Seq[Stats]]()
  private def resultDir = BenchmarkConfig.config.getString("benchmark.resultDir")
  private val serDir = resultDir + "/ser"
  private def serDirExists: Boolean = new File(serDir).exists
  private val htmlDir = resultDir + "/html"
  private def htmlDirExists: Boolean = new File(htmlDir).exists
  protected val maxHistorical = 7

  case class Key(name: String, load: Int)

  def add(stats: Stats): Unit = synchronized {
    val values = statsByName.getOrElseUpdate(stats.name, IndexedSeq.empty)
    statsByName(stats.name) = values :+ stats
    save(stats)
  }

  def get(name: String): Seq[Stats] = synchronized {
    statsByName.getOrElse(name, IndexedSeq.empty)
  }

  def get(name: String, load: Int): Option[Stats] = synchronized {
    get(name).find(_.load == load)
  }

  def isBaseline(stats: Stats): Boolean = synchronized {
    baselineStats.get(Key(stats.name, stats.load)) == Some(stats)
  }

  def getWithHistorical(name: String, load: Int): Seq[Stats] = synchronized {
    val key = Key(name, load)
    val historical = historicalStats.getOrElse(key, IndexedSeq.empty)
    val baseline = baselineStats.get(key)
    val current = get(name, load)

    val limited = (IndexedSeq.empty ++ historical ++ baseline ++ current).takeRight(maxHistorical)
    limited.sortBy(_.timestamp)
  }

  private def loadFiles() {
    if (serDirExists) {
      val files =
        for {
          f ← new File(serDir).listFiles
          if f.isFile
          if f.getName.endsWith(".ser")
        } yield f

      val (baselineFiles, historicalFiles) = files.partition(_.getName.startsWith("baseline-"))
      val baselines = load(baselineFiles)
      for (stats ← baselines) {
        baselineStats(Key(stats.name, stats.load)) = stats
      }
      val historical = load(historicalFiles)
      for (h ← historical) {
        val values = historicalStats.getOrElseUpdate(Key(h.name, h.load), IndexedSeq.empty)
        historicalStats(Key(h.name, h.load)) = values :+ h
      }
    }
  }

  private def save(stats: Stats) {
    new File(serDir).mkdirs
    if (serDirExists) {
      val timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date(stats.timestamp))
      val name = stats.name + "--" + timestamp + "--" + stats.load + ".ser"
      val f = new File(serDir, name)
      var out: ObjectOutputStream = null
      try {
        out = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(f)))
        out.writeObject(stats)
      } catch {
        case e: Exception ⇒
          val errMsg = "Failed to save [%s] to [%s], due to [%s]".format(stats, f.getAbsolutePath, e.getMessage)
          throw new RuntimeException(errMsg)
      } finally {
        if (out ne null) try { out.close() } catch { case ignore: Exception ⇒ }
      }
    }
  }

  private def load(files: Iterable[File]): Seq[Stats] = {
    val result =
      for (f ← files) yield {
        var in: ObjectInputStream = null
        try {
          in = new ObjectInputStream(new BufferedInputStream(new FileInputStream(f)))
          val stats = in.readObject.asInstanceOf[Stats]
          Some(stats)
        } catch {
          case e: Throwable ⇒
            None
        } finally {
          if (in ne null) try { in.close() } catch { case ignore: Exception ⇒ }
        }
      }

    result.flatten.toSeq.sortBy(_.timestamp)
  }

  loadFiles()

  def saveHtmlReport(content: String, fileName: String) {
    new File(htmlDir).mkdirs
    if (htmlDirExists) {
      val f = new File(htmlDir, fileName)
      var writer: PrintWriter = null
      try {
        writer = new PrintWriter(new FileWriter(f))
        writer.print(content)
        writer.flush()
      } catch {
        case e: Exception ⇒
          val errMsg = "Failed to save report to [%s], due to [%s]".format(f.getAbsolutePath, e.getMessage)
          throw new RuntimeException(errMsg)
      } finally {
        if (writer ne null) try { writer.close() } catch { case ignore: Exception ⇒ }
      }
    }
  }

  def htmlReportUrl(fileName: String): String = new File(htmlDir, fileName).getAbsolutePath
}

