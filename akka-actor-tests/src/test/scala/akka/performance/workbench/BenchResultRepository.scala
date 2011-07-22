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

import akka.event.EventHandler

trait BenchResultRepository {
  def add(stats: Stats)

  def get(name: String): Seq[Stats]

  def get(name: String, load: Int): Option[Stats]

  def getWithHistorical(name: String, load: Int): Seq[Stats]

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
  private val serDir = System.getProperty("benchmark.resultDir", "target/benchmark") + "/ser"
  private def serDirExists: Boolean = new File(serDir).exists
  private val htmlDir = System.getProperty("benchmark.resultDir", "target/benchmark") + "/html"
  private def htmlDirExists: Boolean = new File(htmlDir).exists
  protected val maxHistorical = 7

  case class Key(name: String, load: Int)

  def add(stats: Stats) {
    val values = statsByName.getOrElseUpdate(stats.name, IndexedSeq.empty)
    statsByName(stats.name) = values :+ stats
    save(stats)
  }

  def get(name: String): Seq[Stats] = {
    statsByName.getOrElse(name, IndexedSeq.empty)
  }

  def get(name: String, load: Int): Option[Stats] = {
    get(name).find(_.load == load)
  }

  def getWithHistorical(name: String, load: Int): Seq[Stats] = {
    val key = Key(name, load)
    val historical = historicalStats.getOrElse(key, IndexedSeq.empty)
    val baseline = baselineStats.get(key)
    val current = get(name, load)

    (IndexedSeq.empty ++ historical ++ baseline ++ current).takeRight(maxHistorical)
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
    if (!serDirExists) return
    val timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date(stats.timestamp))
    val name = stats.name + "--" + timestamp + "--" + stats.load + ".ser"
    val f = new File(serDir, name)
    var out: ObjectOutputStream = null
    try {
      out = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(f)))
      out.writeObject(stats)
    } catch {
      case e: Exception ⇒
        EventHandler.error(this, "Failed to save [%s] to [%s], due to [%s]".
          format(stats, f.getAbsolutePath, e.getMessage))
    }
    finally {
      if (out ne null) try { out.close() } catch { case ignore: Exception ⇒ }
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
            EventHandler.error(this, "Failed to load from [%s], due to [%s]".
              format(f.getAbsolutePath, e.getMessage))
            None
        }
        finally {
          if (in ne null) try { in.close() } catch { case ignore: Exception ⇒ }
        }
      }

    result.flatten.toSeq.sortBy(_.timestamp)
  }

  loadFiles()

  def saveHtmlReport(content: String, fileName: String) {
    new File(htmlDir).mkdirs
    if (!htmlDirExists) return
    val f = new File(htmlDir, fileName)
    var writer: PrintWriter = null
    try {
      writer = new PrintWriter(new FileWriter(f))
      writer.print(content)
      writer.flush()
    } catch {
      case e: Exception ⇒
        EventHandler.error(this, "Failed to save report to [%s], due to [%s]".
          format(f.getAbsolutePath, e.getMessage))
    }
    finally {
      if (writer ne null) try { writer.close() } catch { case ignore: Exception ⇒ }
    }
  }

  def htmlReportUrl(fileName: String): String = new File(htmlDir, fileName).getAbsolutePath
}

