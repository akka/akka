/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

import com.weiglewilczek.bnd4sbt.BNDPlugin
import java.io.File
import java.util.jar.Attributes
import java.util.jar.Attributes.Name._
import sbt._
import sbt.CompileOrder._
import spde._

class AkkaParentProject(info: ProjectInfo) extends DefaultProject(info) {

  // -------------------------------------------------------------------------------------------------------------------
  // Compile settings
  // -------------------------------------------------------------------------------------------------------------------

  val scalaCompileSettings =
    Seq("-deprecation",
        "-Xmigration",
        "-optimise",
        "-encoding", "utf8")

  val javaCompileSettings = Seq("-Xlint:unchecked")

  override def compileOptions     = super.compileOptions ++ scalaCompileSettings.map(CompileOption)
  override def javaCompileOptions = super.javaCompileOptions ++ javaCompileSettings.map(JavaCompileOption)

  // -------------------------------------------------------------------------------------------------------------------
  // Deploy/dist settings
  // -------------------------------------------------------------------------------------------------------------------
  val distName = "%s-%s".format(name, version)
  val distArchiveName = distName + ".zip"
  val deployPath = info.projectPath / "deploy"
  val distPath = info.projectPath / "dist"
  val distArchive = (distPath ##) / distArchiveName

  lazy override val `package` = task { None }

  //The distribution task, packages Akka into a zipfile and places it into the projectPath/dist directory
  lazy val dist = task {

    def transferFile(from: Path, to: Path) =
      if ( from.asFile.renameTo(to.asFile) ) None
      else Some("Couldn't transfer %s to %s".format(from,to))

    //Creates a temporary directory where we can assemble the distribution
    val genDistDir = Path.fromFile({
      val d = File.createTempFile("akka","dist")
      d.delete //delete the file
      d.mkdir  //Recreate it as a dir
      d
    }).## //## is needed to make sure that the zipped archive has the correct root folder

    //Temporary directory to hold the dist currently being generated
    val currentDist = genDistDir / distName

    FileUtilities.copy(allArtifacts.get, currentDist, log).left.toOption orElse //Copy all needed artifacts into the root archive
    FileUtilities.zip(List(currentDist), distArchiveName, true, log) orElse //Compress the root archive into a zipfile
    transferFile(info.projectPath / distArchiveName, distArchive) orElse //Move the archive into the dist folder
    FileUtilities.clean(genDistDir,log) //Cleanup the generated jars

  } dependsOn (`package`) describedAs("Zips up the distribution.")

  // -------------------------------------------------------------------------------------------------------------------
  // All repositories *must* go here! See ModuleConigurations below.
  // -------------------------------------------------------------------------------------------------------------------

  object Repositories {
    lazy val LocalMavenRepo         = MavenRepository("Local Maven Repo", (Path.userHome / ".m2" / "repository").asURL.toString)
    lazy val AkkaRepo               = MavenRepository("Akka Repository", "http://akka.io/repository")
    lazy val CodehausRepo           = MavenRepository("Codehaus Repo", "http://repository.codehaus.org")
    lazy val GuiceyFruitRepo        = MavenRepository("GuiceyFruit Repo", "http://guiceyfruit.googlecode.com/svn/repo/releases/")
    lazy val JBossRepo              = MavenRepository("JBoss Repo", "http://repository.jboss.org/nexus/content/groups/public/")
    lazy val JavaNetRepo            = MavenRepository("java.net Repo", "http://download.java.net/maven/2")
    lazy val SonatypeSnapshotRepo   = MavenRepository("Sonatype OSS Repo", "http://oss.sonatype.org/content/repositories/releases")
    lazy val GlassfishRepo          = MavenRepository("Glassfish Repo", "http://download.java.net/maven/glassfish")
    lazy val ScalaToolsRelRepo      = MavenRepository("Scala Tools Releases Repo", "http://scala-tools.org/repo-releases")
    lazy val DatabinderRepo         = MavenRepository("Databinder Repo", "http://databinder.net/repo")
    lazy val ScalaToolsSnapshotRepo = MavenRepository("Scala-Tools Snapshot Repo", "http://scala-tools.org/repo-snapshots")
  }

  // -------------------------------------------------------------------------------------------------------------------
  // ModuleConfigurations
  // Every dependency that cannot be resolved from the built-in repositories (Maven Central and Scala Tools Releases)
  // must be resolved from a ModuleConfiguration. This will result in a significant acceleration of the update action.
  // Therefore, if repositories are defined, this must happen as def, not as val.
  // -------------------------------------------------------------------------------------------------------------------

  import Repositories._
  lazy val jettyModuleConfig       = ModuleConfiguration("org.eclipse.jetty", sbt.DefaultMavenRepository)
  lazy val guiceyFruitModuleConfig = ModuleConfiguration("org.guiceyfruit", GuiceyFruitRepo)
  lazy val glassfishModuleConfig   = ModuleConfiguration("org.glassfish", GlassfishRepo)
  lazy val jbossModuleConfig       = ModuleConfiguration("org.jboss", JBossRepo)
  lazy val jerseyModuleConfig      = ModuleConfiguration("com.sun.jersey", JavaNetRepo)
  lazy val multiverseModuleConfig  = ModuleConfiguration("org.multiverse", CodehausRepo)
  lazy val nettyModuleConfig       = ModuleConfiguration("org.jboss.netty", JBossRepo)
  lazy val scalaTestModuleConfig   = ModuleConfiguration("org.scalatest", ScalaToolsSnapshotRepo)
  lazy val spdeModuleConfig        = ModuleConfiguration("us.technically.spde", DatabinderRepo)
  lazy val processingModuleConfig  = ModuleConfiguration("org.processing", DatabinderRepo)
  lazy val sjsonModuleConfig       = ModuleConfiguration("net.debasishg", ScalaToolsRelRepo)
  lazy val lzfModuleConfig         = ModuleConfiguration("voldemort.store.compress", "h2-lzf", AkkaRepo)
  lazy val vscaladocModuleConfig   = ModuleConfiguration("org.scala-tools", "vscaladoc", "1.1-md-3", AkkaRepo)
  lazy val aspectWerkzModuleConfig = ModuleConfiguration("org.codehaus.aspectwerkz", "aspectwerkz", "2.2.3", AkkaRepo)
  lazy val objenesisModuleConfig   = ModuleConfiguration("org.objenesis", sbt.DefaultMavenRepository)
  lazy val localMavenRepo          = LocalMavenRepo // Second exception, also fast! ;-)

  // -------------------------------------------------------------------------------------------------------------------
  // Versions
  // -------------------------------------------------------------------------------------------------------------------

  lazy val JACKSON_VERSION       = "1.7.1"
  lazy val JERSEY_VERSION        = "1.3"
  lazy val MULTIVERSE_VERSION    = "0.6.2"
  lazy val SCALATEST_VERSION     = "1.4-SNAPSHOT"
  lazy val JETTY_VERSION         = "7.2.2.v20101205"
  lazy val JAVAX_SERVLET_VERSION = "3.0"
  lazy val SLF4J_VERSION         = "1.6.0"

  // -------------------------------------------------------------------------------------------------------------------
  // Dependencies
  // -------------------------------------------------------------------------------------------------------------------

  object Dependencies {

    // Compile
    lazy val aopalliance = "aopalliance" % "aopalliance" % "1.0" % "compile" //Public domain

    lazy val aspectwerkz = "org.codehaus.aspectwerkz" % "aspectwerkz" % "2.2.3" % "compile" //ApacheV2

    lazy val commons_codec = "commons-codec" % "commons-codec" % "1.4" % "compile" //ApacheV2

    lazy val commons_io = "commons-io" % "commons-io" % "2.0.1" % "compile" //ApacheV2

    lazy val javax_servlet_30 = "org.glassfish" % "javax.servlet" % JAVAX_SERVLET_VERSION % "provided" //CDDL v1

    lazy val jetty         = "org.eclipse.jetty" % "jetty-server"  % JETTY_VERSION % "provided" //Eclipse license
    lazy val guicey = "org.guiceyfruit" % "guice-all" % "2.0" % "compile" //ApacheV2

    lazy val h2_lzf = "voldemort.store.compress" % "h2-lzf" % "1.0" % "compile" //ApacheV2

    lazy val jackson          = "org.codehaus.jackson" % "jackson-mapper-asl" % JACKSON_VERSION % "compile" //ApacheV2
    lazy val jackson_core     = "org.codehaus.jackson" % "jackson-core-asl"   % JACKSON_VERSION % "compile" //ApacheV2

    lazy val jersey_server  = "com.sun.jersey"          % "jersey-server" % JERSEY_VERSION % "provided" //CDDL v1

    lazy val jsr250 = "javax.annotation" % "jsr250-api" % "1.0" % "compile" //CDDL v1

    lazy val jsr311 = "javax.ws.rs" % "jsr311-api" % "1.1" % "compile" //CDDL v1

    lazy val multiverse      = "org.multiverse" % "multiverse-alpha" % MULTIVERSE_VERSION % "compile" //ApacheV2
    lazy val multiverse_test = "org.multiverse" % "multiverse-alpha" % MULTIVERSE_VERSION % "test" //ApacheV2

    lazy val netty = "org.jboss.netty" % "netty" % "3.2.3.Final" % "compile" //ApacheV2

    lazy val protobuf = "com.google.protobuf" % "protobuf-java" % "2.3.0" % "compile" //New BSD

    lazy val sjson      = "net.debasishg" % "sjson_2.9.0.RC1" % "0.10" % "compile" //ApacheV2
    lazy val sjson_test = "net.debasishg" % "sjson_2.9.0.RC1" % "0.10" % "test" //ApacheV2

    lazy val slf4j   = "org.slf4j"      % "slf4j-api"       % "1.6.0"
    lazy val logback = "ch.qos.logback" % "logback-classic" % "0.9.24"

    // Test

    lazy val commons_coll   = "commons-collections"    % "commons-collections" % "3.2.1"           % "test" //ApacheV2

    lazy val testJetty      = "org.eclipse.jetty"      % "jetty-server"        % JETTY_VERSION     % "test" //Eclipse license
    lazy val testJettyWebApp= "org.eclipse.jetty"      % "jetty-webapp"        % JETTY_VERSION     % "test" //Eclipse license

    lazy val junit          = "junit"                  % "junit"               % "4.5"             % "test" //Common Public License 1.0
    lazy val mockito        = "org.mockito"            % "mockito-all"         % "1.8.1"           % "test" //MIT
    lazy val scalatest      = "org.scalatest"          % "scalatest"           % SCALATEST_VERSION % "test" //ApacheV2
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Subprojects
  // -------------------------------------------------------------------------------------------------------------------

  lazy val akka_actor       = project("akka-actor",       "akka-actor",       new AkkaActorProject(_))
  lazy val akka_testkit     = project("akka-testkit",     "akka-testkit",     new AkkaTestkitProject(_),    akka_actor)
  lazy val akka_actor_tests = project("akka-actor-tests", "akka-actor-tests", new AkkaActorTestsProject(_), akka_testkit)
  lazy val akka_stm         = project("akka-stm",         "akka-stm",         new AkkaStmProject(_),        akka_actor)
  lazy val akka_typed_actor = project("akka-typed-actor", "akka-typed-actor", new AkkaTypedActorProject(_), akka_stm, akka_actor_tests)
  lazy val akka_remote      = project("akka-remote",      "akka-remote",      new AkkaRemoteProject(_),     akka_typed_actor)
  lazy val akka_http        = project("akka-http",        "akka-http",        new AkkaHttpProject(_),       akka_actor)
  lazy val akka_samples     = project("akka-samples",     "akka-samples",     new AkkaSamplesParentProject(_))
  lazy val akka_slf4j       = project("akka-slf4j",       "akka-slf4j",       new AkkaSlf4jProject(_),      akka_actor)
  lazy val akka_tutorials   = project("akka-tutorials",   "akka-tutorials",   new AkkaTutorialsParentProject(_),      akka_actor)

  // -------------------------------------------------------------------------------------------------------------------
  // Miscellaneous
  // -------------------------------------------------------------------------------------------------------------------
  override def disableCrossPaths = true

  override def packageOptions =
    manifestClassPath.map(cp => ManifestAttributes(
      (Attributes.Name.CLASS_PATH, cp),
      (IMPLEMENTATION_TITLE, "Akka"),
      (IMPLEMENTATION_URL, "http://akka.io"),
      (IMPLEMENTATION_VENDOR, "Scalable Solutions AB")
    )).toList

  //Exclude slf4j1.5.11 from the classpath, it's conflicting...
  override def fullClasspath(config: Configuration): PathFinder = {
    super.fullClasspath(config) ---
    (super.fullClasspath(config) ** "slf4j*1.5.11.jar")
  }

//  override def runClasspath = super.runClasspath +++ "config"

  // ------------------------------------------------------------
  // publishing
  override def managedStyle = ManagedStyle.Maven

  //override def defaultPublishRepository = Some(Resolver.file("maven-local", Path.userHome / ".m2" / "repository" asFile))
  val publishTo = Resolver.file("maven-local", Path.userHome / ".m2" / "repository" asFile)

  override def artifacts = Set(Artifact(artifactID, "pom", "pom"))

  override def deliverProjectDependencies =
    super.deliverProjectDependencies.toList - akka_samples.projectID

  // val sourceArtifact = Artifact(artifactID, "src", "jar", Some("sources"), Nil, None)
  // val docsArtifact   = Artifact(artifactID, "doc", "jar", Some("docs"), Nil, None)

  // Credentials(Path.userHome / ".akka_publish_credentials", log)

  // override def documentOptions = encodingUtf8.map(SimpleDocOption(_))
  // override def packageDocsJar = defaultJarPath("-docs.jar")
  // override def packageSrcJar= defaultJarPath("-sources.jar")
  // override def packageToPublishActions = super.packageToPublishActions ++ Seq(packageDocs, packageSrc)

  override def pomExtra =
    <inceptionYear>2009</inceptionYear>
    <url>http://akka.io</url>
    <organization>
      <name>Scalable Solutions AB</name>
      <url>http://scalablesolutions.se</url>
    </organization>
    <licenses>
      <license>
        <name>Apache 2</name>
        <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
        <distribution>repo</distribution>
      </license>
    </licenses>

  // publish to local mvn
  import Process._
  lazy val publishLocalMvn = runMvnInstall
  def runMvnInstall = task {
    for (absPath <- akkaArtifacts.getPaths) {
      val artifactRE = """(.*)/dist/(.*)-(\d.*)\.jar""".r
      val artifactRE(path, artifactId, artifactVersion) = absPath
      val command = "mvn install:install-file" +
                    " -Dfile=" + absPath +
                    " -DgroupId=se.scalablesolutions.akka" +
                    " -DartifactId=" + artifactId +
                    " -Dversion=" + version +
                    " -Dpackaging=jar -DgeneratePom=true"
      command ! log
    }
    None
  } dependsOn(dist) describedAs("Run mvn install for artifacts in dist.")


  // Build release

  val localReleasePath = outputPath / "release" / version.toString
  val localReleaseRepository = Resolver.file("Local Release", localReleasePath / "repository" asFile)
  val localReleaseDownloads = localReleasePath / "downloads"

  override def otherRepositories = super.otherRepositories ++ Seq(localReleaseRepository)

  lazy val publishRelease = {
    val releaseConfiguration = new DefaultPublishConfiguration(localReleaseRepository, "release", false)
    publishTask(publishIvyModule, releaseConfiguration) dependsOn (deliver, publishLocal, makePom)
  }

  lazy val buildRelease = task {
    FileUtilities.copy(Seq(distArchive), localReleaseDownloads, log).left.toOption
  } dependsOn (publishRelease, dist)

  // -------------------------------------------------------------------------------------------------------------------
  // akka-actor subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaActorProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    override def bndExportPackage = super.bndExportPackage ++ Seq("com.eaio.*;version=3.2")
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-stm subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaStmProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val multiverse = Dependencies.multiverse

    // testing
    val junit     = Dependencies.junit
    val scalatest = Dependencies.scalatest
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-typed-actor subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaTypedActorProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val aopalliance  = Dependencies.aopalliance
    val aspectwerkz  = Dependencies.aspectwerkz
    val guicey       = Dependencies.guicey

    // testing
    val junit     = Dependencies.junit
    val scalatest = Dependencies.scalatest
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-remote subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaRemoteProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val commons_codec = Dependencies.commons_codec
    val commons_io    = Dependencies.commons_io
    val guicey        = Dependencies.guicey
    val h2_lzf        = Dependencies.h2_lzf
    val jackson       = Dependencies.jackson
    val jackson_core  = Dependencies.jackson_core
    val netty         = Dependencies.netty
    val protobuf      = Dependencies.protobuf
    val sjson         = Dependencies.sjson

    // testing
    val junit     = Dependencies.junit
    val scalatest = Dependencies.scalatest

    lazy val networkTestsEnabled = systemOptional[Boolean]("akka.test.network", false)

    override def testOptions = super.testOptions ++ {
      if (!networkTestsEnabled.value) Seq(TestFilter(test => !test.endsWith("NetworkTest")))
      else Seq.empty
    }

    override def bndImportPackage = "javax.transaction;version=1.1" :: super.bndImportPackage.toList
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-http subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaHttpProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val jsr250           = Dependencies.jsr250
    val javax_servlet30  = Dependencies.javax_servlet_30
    val jetty            = Dependencies.jetty
    val jersey           = Dependencies.jersey_server
    val jsr311           = Dependencies.jsr311
    val commons_codec    = Dependencies.commons_codec

    // testing
    val junit     = Dependencies.junit
    val mockito   = Dependencies.mockito
    val scalatest = Dependencies.scalatest
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Examples
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaSampleAntsProject(info: ProjectInfo) extends DefaultSpdeProject(info) {
    override def disableCrossPaths = true
    override def spdeSourcePath = mainSourcePath / "spde"

    lazy val sourceArtifact = Artifact(this.artifactID, "src", "jar", Some("sources"), Nil, None)
    lazy val docsArtifact = Artifact(this.artifactID, "doc", "jar", Some("docs"), Nil, None)
    override def packageDocsJar = this.defaultJarPath("-docs.jar")
    override def packageSrcJar  = this.defaultJarPath("-sources.jar")
    override def packageToPublishActions = super.packageToPublishActions ++ Seq(this.packageDocs, this.packageSrc)

    lazy val publishRelease = {
      val releaseConfiguration = new DefaultPublishConfiguration(localReleaseRepository, "release", false)
      publishTask(publishIvyModule, releaseConfiguration) dependsOn (deliver, publishLocal, makePom)
    }
  }

  class AkkaSampleRemoteProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath)

  class AkkaSampleFSMProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath)

  class AkkaSamplesParentProject(info: ProjectInfo) extends ParentProject(info) {
    override def disableCrossPaths = true

    lazy val akka_sample_ants = project("akka-sample-ants", "akka-sample-ants",
      new AkkaSampleAntsProject(_), akka_stm)
    lazy val akka_sample_fsm = project("akka-sample-fsm", "akka-sample-fsm",
      new AkkaSampleFSMProject(_), akka_actor)
    lazy val akka_sample_remote = project("akka-sample-remote", "akka-sample-remote",
      new AkkaSampleRemoteProject(_), akka_remote)

    lazy val publishRelease = {
      val releaseConfiguration = new DefaultPublishConfiguration(localReleaseRepository, "release", false)
      publishTask(publishIvyModule, releaseConfiguration) dependsOn (deliver, publishLocal, makePom)
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Tutorials
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaTutorialPiSbtProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath)

  class AkkaTutorialsParentProject(info: ProjectInfo) extends ParentProject(info) {
    override def disableCrossPaths = true

    lazy val akka_tutorial_pi_sbt = project("akka-tutorial-pi-sbt", "akka-tutorial-pi-sbt",
      new AkkaTutorialPiSbtProject(_), akka_actor)

    lazy val publishRelease = {
      val releaseConfiguration = new DefaultPublishConfiguration(localReleaseRepository, "release", false)
      publishTask(publishIvyModule, releaseConfiguration) dependsOn (deliver, publishLocal, makePom)
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-testkit subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaTestkitProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath)

  // -------------------------------------------------------------------------------------------------------------------
  // akka-actor-tests subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaActorTestsProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    // testing
    val junit           = Dependencies.junit
    val scalatest       = Dependencies.scalatest
    val multiverse_test = Dependencies.multiverse_test // StandardLatch
  }
  
  // -------------------------------------------------------------------------------------------------------------------
  // akka-slf4j subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaSlf4jProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val sjson   = Dependencies.slf4j
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------------------------------------------------

  def removeDupEntries(paths: PathFinder) = Path.lazyPathFinder {
    val mapped = paths.get map { p => (p.relativePath, p) }
    (Map() ++ mapped).values.toList
  }

  def allArtifacts = {
    Path.fromFile(buildScalaInstance.libraryJar) +++
    (removeDupEntries(runClasspath filter ClasspathUtilities.isArchive) +++
    ((outputPath ##) / defaultJarName) +++
    mainResources +++
    mainDependencies.scalaJars +++
    descendents(info.projectPath / "scripts", "run_akka.sh") +++
    descendents(info.projectPath / "scripts", "akka-init-script.sh") +++
    descendents(info.projectPath / "dist", "*.jar") +++
    descendents(info.projectPath / "deploy", "*.jar") +++
    descendents(path("lib") ##, "*.jar") +++
    descendents(configurationPath(Configurations.Compile) ##, "*.jar"))
    .filter(jar => // remove redundant libs
      !jar.toString.endsWith("stax-api-1.0.1.jar") ||
      !jar.toString.endsWith("scala-library-2.7.7.jar")
    )
  }

  def akkaArtifacts = descendents(info.projectPath / "dist", "*-" + version + ".jar")

  // ------------------------------------------------------------
  class AkkaDefaultProject(info: ProjectInfo, val deployPath: Path) extends DefaultProject(info) 
    with DeployProject with OSGiProject with McPom {
    override def disableCrossPaths = true

    override def compileOptions = super.compileOptions ++ scalaCompileSettings.map(CompileOption)
    override def javaCompileOptions = super.javaCompileOptions ++ javaCompileSettings.map(JavaCompileOption)

    lazy val sourceArtifact = Artifact(this.artifactID, "src", "jar", Some("sources"), Nil, None)
    lazy val docsArtifact = Artifact(this.artifactID, "doc", "jar", Some("docs"), Nil, None)
    override def runClasspath = super.runClasspath +++ (AkkaParentProject.this.info.projectPath / "config")
    override def testClasspath = super.testClasspath +++ (AkkaParentProject.this.info.projectPath / "config")
    override def packageDocsJar = this.defaultJarPath("-docs.jar")
    override def packageSrcJar  = this.defaultJarPath("-sources.jar")
    override def packageToPublishActions = super.packageToPublishActions ++ Seq(this.packageDocs, this.packageSrc)
    override def pomPostProcess(node: scala.xml.Node): scala.xml.Node = mcPom(AkkaParentProject.this.moduleConfigurations)(super.pomPostProcess(node))

    lazy val excludeTestsProperty = systemOptional[String]("akka.test.exclude", "")

    def excludeTests = {
      val exclude = excludeTestsProperty.value
      if (exclude.isEmpty) Seq.empty else exclude.split(",").toSeq
    }

    override def testOptions = super.testOptions ++ excludeTests.map(exclude => TestFilter(test => !test.contains(exclude)))

    lazy val publishRelease = {
      val releaseConfiguration = new DefaultPublishConfiguration(localReleaseRepository, "release", false)
      publishTask(publishIvyModule, releaseConfiguration) dependsOn (deliver, publishLocal, makePom)
    }
  }
}

trait DeployProject { self: BasicScalaProject =>
  // defines where the deployTask copies jars to
  def deployPath: Path

  lazy val dist = deployTask(jarPath, packageDocsJar, packageSrcJar, deployPath, true, true, true) dependsOn(
    `package`, packageDocs, packageSrc) describedAs("Deploying")

  def deployTask(jar: Path, docs: Path, src: Path, toDir: Path,
                 genJar: Boolean, genDocs: Boolean, genSource: Boolean) = task {
    def gen(jar: Path, toDir: Path, flag: Boolean, msg: String): Option[String] =
    if (flag) {
      log.info(msg + " " + jar)
      FileUtilities.copyFile(jar, toDir / jar.name, log)
    } else None

    gen(jar, toDir, genJar, "Deploying bits") orElse
    gen(docs, toDir, genDocs, "Deploying docs") orElse
    gen(src, toDir, genSource, "Deploying sources")
  }
}

trait OSGiProject extends BNDPlugin { self: DefaultProject =>
  override def bndExportPackage = Seq("akka.*;version=%s".format(projectVersion.value))
}

trait McPom { self: DefaultProject =>
  import scala.xml._

  def mcPom(mcs: Set[ModuleConfiguration])(node: Node): Node = {

    def cleanUrl(url: String) = url match {
      case null                => ""
      case ""                  => ""
      case u if u endsWith "/" => u
      case u                   => u + "/"
    }

    val oldRepos = 
      (node \\ "project" \ "repositories" \ "repository").map { n => 
        cleanUrl((n \ "url").text) -> (n \ "name").text
      }.toList

    val newRepos = 
      mcs.filter(_.resolver.isInstanceOf[MavenRepository]).map { m =>
        val r = m.resolver.asInstanceOf[MavenRepository]
        cleanUrl(r.root) -> r.name
      }

    val repos = Map((oldRepos ++ newRepos): _*).map { pair =>
      <repository>
        <id>{pair._2.toSeq.filter(_.isLetterOrDigit).mkString}</id>
        <name>{pair._2}</name>
        <url>{pair._1}</url>
      </repository>
    }

    def rewrite(pf: PartialFunction[Node, Node])(ns: Seq[Node]): Seq[Node] = for(subnode <- ns) yield subnode match {
      case e: Elem =>
        if (pf isDefinedAt e) pf(e)
        else Elem(e.prefix, e.label, e.attributes, e.scope, rewrite(pf)(e.child):_*)
      case other => other
    }

    val rule: PartialFunction[Node,Node] = if ((node \\ "project" \ "repositories" ).isEmpty) {
      case Elem(prefix, "project", attribs, scope, children @ _*) =>
           Elem(prefix, "project", attribs, scope, children ++ <repositories>{repos}</repositories>:_*)
    } else {
      case Elem(prefix, "repositories", attribs, scope, children @ _*) =>
           Elem(prefix, "repositories", attribs, scope, repos.toList: _*)
    }

    rewrite(rule)(node.theSeq)(0)
  }
}
