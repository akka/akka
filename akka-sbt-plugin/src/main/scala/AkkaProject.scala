import sbt._

object AkkaRepositories {
  val AkkaRepo             = MavenRepository("Akka Repository", "http://scalablesolutions.se/akka/repository")
  val GuiceyFruitRepo      = MavenRepository("GuiceyFruit Repo", "http://guiceyfruit.googlecode.com/svn/repo/releases/")
  val JBossRepo            = MavenRepository("JBoss Repo", "https://repository.jboss.org/nexus/content/groups/public/")
  val SunJDMKRepo          = MavenRepository("Sun JDMK Repo", "http://wp5.e-taxonomy.eu/cdmlib/mavenrepo")
  val JavaNetRepo          = MavenRepository("java.net Repo", "http://download.java.net/maven/2")
  val CodehausSnapshotRepo = MavenRepository("Codehaus Snapshots", "http://snapshots.repository.codehaus.org")
}

trait AkkaBaseProject extends BasicScalaProject {
  import AkkaRepositories._

  // Every dependency that cannot be resolved from the built-in repositories (Maven Central and Scala Tools Releases)
  // is resolved from a ModuleConfiguration. This will result in a significant acceleration of the update action.

  // for development version resolve to .ivy2/local
  // val akkaModuleConfig        = ModuleConfiguration("se.scalablesolutions.akka", AkkaRepo)
  val netLagModuleConfig      = ModuleConfiguration("net.lag", AkkaRepo)
  val sbinaryModuleConfig     = ModuleConfiguration("sbinary", AkkaRepo)
  val redisModuleConfig       = ModuleConfiguration("com.redis", AkkaRepo)
  val atmosphereModuleConfig  = ModuleConfiguration("org.atmosphere", AkkaRepo)
  val facebookModuleConfig    = ModuleConfiguration("com.facebook", AkkaRepo)
  val jsr166xModuleConfig     = ModuleConfiguration("jsr166x", AkkaRepo)
  val sjsonModuleConfig       = ModuleConfiguration("sjson.json", AkkaRepo)
  val voldemortModuleConfig   = ModuleConfiguration("voldemort.store.compress", AkkaRepo)
  val cassandraModuleConfig   = ModuleConfiguration("org.apache.cassandra", AkkaRepo)
  val guiceyFruitModuleConfig = ModuleConfiguration("org.guiceyfruit", GuiceyFruitRepo)
  val jbossModuleConfig       = ModuleConfiguration("org.jboss", JBossRepo)
  val nettyModuleConfig       = ModuleConfiguration("org.jboss.netty", JBossRepo)
  val jgroupsModuleConfig     = ModuleConfiguration("jgroups", JBossRepo)
  val jmsModuleConfig         = ModuleConfiguration("javax.jms", SunJDMKRepo)
  val jdmkModuleConfig        = ModuleConfiguration("com.sun.jdmk", SunJDMKRepo)
  val jmxModuleConfig         = ModuleConfiguration("com.sun.jmx", SunJDMKRepo)
  val jerseyModuleConfig      = ModuleConfiguration("com.sun.jersey", JavaNetRepo)
  val jerseyContrModuleConfig = ModuleConfiguration("com.sun.jersey.contribs", JavaNetRepo)
  val grizzlyModuleConfig     = ModuleConfiguration("com.sun.grizzly", JavaNetRepo)
  val multiverseModuleConfig  = ModuleConfiguration("org.multiverse", CodehausSnapshotRepo) // only while snapshot version
  val liftModuleConfig        = ModuleConfiguration("net.liftweb", ScalaToolsSnapshots)
}

trait AkkaProject extends AkkaBaseProject {
  val akkaVersion = "1.0-SNAPSHOT"

  // convenience method
  def akkaModule(module: String) = "se.scalablesolutions.akka" %% ("akka-" + module) % akkaVersion

  // akka remote dependency by default
  val akkaRemote = akkaModule("remote")
}
