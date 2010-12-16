import sbt._

object AkkaRepositories {
  val AkkaRepo             = MavenRepository("Akka Repository", "http://scalablesolutions.se/akka/repository")
  val CasbahRepo           = MavenRepository("Casbah Repo", "http://repo.bumnetworks.com/releases")
  val CasbahSnapshotRepo   = MavenRepository("Casbah Snapshots", "http://repo.bumnetworks.com/snapshots")
  val ClojarsRepo          = MavenRepository("Clojars Repo", "http://clojars.org/repo")
  val CodehausRepo         = MavenRepository("Codehaus Repo", "http://repository.codehaus.org")
  val GuiceyFruitRepo      = MavenRepository("GuiceyFruit Repo", "http://guiceyfruit.googlecode.com/svn/repo/releases/")
  val JBossRepo            = MavenRepository("JBoss Repo", "http://repository.jboss.org/nexus/content/groups/public/")
  val JavaNetRepo          = MavenRepository("java.net Repo", "http://download.java.net/maven/2")
  val SonatypeSnapshotRepo = MavenRepository("Sonatype OSS Repo", "http://oss.sonatype.org/content/repositories/releases")
  val SunJDMKRepo          = MavenRepository("Sun JDMK Repo", "http://wp5.e-taxonomy.eu/cdmlib/mavenrepo")
  val ZookeeperRepo        = MavenRepository("Zookeeper Repo", "http://lilycms.org/maven/maven2/deploy/")
}

trait AkkaBaseProject extends BasicScalaProject {
  import AkkaRepositories._

  // Every dependency that cannot be resolved from the built-in repositories (Maven Central and Scala Tools Releases)
  // is resolved from a ModuleConfiguration. This will result in a significant acceleration of the update action.

  // for development version resolve to .ivy2/local
  // val akkaModuleConfig        = ModuleConfiguration("se.scalablesolutions.akka", AkkaRepo)

  val aspectwerkzModuleConfig = ModuleConfiguration("org.codehaus.aspectwerkz", AkkaRepo)
  val cassandraModuleConfig   = ModuleConfiguration("org.apache.cassandra", AkkaRepo)
  val eaioModuleConfig        = ModuleConfiguration("com.eaio", AkkaRepo)
  val facebookModuleConfig    = ModuleConfiguration("com.facebook", AkkaRepo)
  val h2lzfModuleConfig       = ModuleConfiguration("voldemort.store.compress", AkkaRepo)
  val hbaseModuleConfig       = ModuleConfiguration("org.apache.hbase", AkkaRepo)
  val jsr166xModuleConfig     = ModuleConfiguration("jsr166x", AkkaRepo)
  val netLagModuleConfig      = ModuleConfiguration("net.lag", AkkaRepo)
  val redisModuleConfig       = ModuleConfiguration("com.redis", AkkaRepo)
  val sbinaryModuleConfig     = ModuleConfiguration("sbinary", AkkaRepo)
  val sjsonModuleConfig       = ModuleConfiguration("sjson.json", AkkaRepo)
  val triforkModuleConfig     = ModuleConfiguration("com.trifork", AkkaRepo)
  val vscaladocModuleConfig   = ModuleConfiguration("org.scala-tools", "vscaladoc", "1.1-md-3", AkkaRepo)

  val atmosphereModuleConfig  = ModuleConfiguration("org.atmosphere", SonatypeSnapshotRepo)
  val casbahModuleConfig      = ModuleConfiguration("com.novus", CasbahRepo)
  val grizzlyModuleConfig     = ModuleConfiguration("com.sun.grizzly", JavaNetRepo)
  val guiceyFruitModuleConfig = ModuleConfiguration("org.guiceyfruit", GuiceyFruitRepo)
  val jbossModuleConfig       = ModuleConfiguration("org.jboss", JBossRepo)
  val jdmkModuleConfig        = ModuleConfiguration("com.sun.jdmk", SunJDMKRepo)
  val jmsModuleConfig         = ModuleConfiguration("javax.jms", SunJDMKRepo)
  val jmxModuleConfig         = ModuleConfiguration("com.sun.jmx", SunJDMKRepo)
  val jerseyContrModuleConfig = ModuleConfiguration("com.sun.jersey.contribs", JavaNetRepo)
  val jerseyModuleConfig      = ModuleConfiguration("com.sun.jersey", JavaNetRepo)
  val jgroupsModuleConfig     = ModuleConfiguration("jgroups", JBossRepo)
  val multiverseModuleConfig  = ModuleConfiguration("org.multiverse", CodehausRepo)
  val nettyModuleConfig       = ModuleConfiguration("org.jboss.netty", JBossRepo)
  val timeModuleConfig        = ModuleConfiguration("org.scala-tools", "time", CasbahSnapshotRepo)
  val voldemortModuleConfig   = ModuleConfiguration("voldemort", ClojarsRepo)
  val zookeeperModuleConfig   = ModuleConfiguration("org.apache.hadoop.zookeeper", ZookeeperRepo)
}

trait AkkaProject extends AkkaBaseProject {
  val akkaVersion = "1.1-SNAPSHOT"

  // convenience method
  def akkaModule(module: String) = "se.scalablesolutions.akka" % ("akka-" + module) % akkaVersion

  // akka actor dependency by default
  val akkaActor = akkaModule("actor")
}
