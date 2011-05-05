import sbt._

trait DocParentProject extends ParentProject {
  def docOutputPath = outputPath / "doc" / "main" / "api"

  def docProjectDependencies = topologicalSort.dropRight(1)

  def docMainSources =
    docProjectDependencies.map {
      case sp: ScalaPaths => sp.mainSources
      case _ => Path.emptyPathFinder
    }.foldLeft(Path.emptyPathFinder)(_ +++ _)

  def docCompileClasspath =
    docProjectDependencies.map {
      case bsp: BasicScalaProject => bsp.compileClasspath
      case _ => Path.emptyPathFinder
    }.foldLeft(Path.emptyPathFinder)(_ +++ _)

  def docLabel = "main"

  def docMaxErrors = 100

  def docOptions: Seq[String] = Seq.empty

  lazy val doc = docAction describedAs ("Create combined scaladoc for all subprojects")

  def docAction = task {
    val scaladoc = new Scaladoc(docMaxErrors, buildCompiler)
    scaladoc(docLabel, docMainSources.get, docCompileClasspath.get, docOutputPath, docOptions, log)
  }
}
