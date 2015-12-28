import scalariform.formatter.preferences._

name := "solarium"

version := scala.io.Source.fromFile("version").mkString.trim

organization := "io.wasted"

scalaVersion := "2.11.7"

crossScalaVersions := Seq("2.10.6", "2.11.7")

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature", "-language:postfixOps", "-language:implicitConversions")

libraryDependencies ++= {
  val wastedVer = "0.11.2"
  val liftVer = "2.6.2"
  Seq(
    "net.liftweb"               %% "lift-record"          % liftVer       % "compile",
    "net.liftweb"               %% "lift-json"            % liftVer       % "compile",
    "io.wasted"                 %% "wasted-util"          % wastedVer     % "compile",
    "com.fasterxml.jackson.core" % "jackson-databind"     % "2.5.3"       % "compile",
    "org.mongodb"                % "bson"                 % "3.0.1"       % "compile"
      exclude("org.slf4j", "slf4j-api"),
    "org.elasticsearch"          % "elasticsearch"        % "1.5.2"       % "compile"
      exclude("log4j", "log4j") exclude("log4j", "apache-log4j-extras")
  )
}


// For testing
libraryDependencies ++= Seq(
  "junit" % "junit" % "4.12" % "test",
  "org.scalatest" %% "scalatest" % "2.2.2" % "test"
)


publishTo := Some("wasted.io/repo" at "http://repo.wasted.io/mvn")

scalariformSettings

ScalariformKeys.preferences := FormattingPreferences().setPreference(AlignParameters, true)

sourceGenerators in Compile <+= buildInfo

buildInfoSettings

buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)

buildInfoPackage := "io.wasted.solarium.build"

net.virtualvoid.sbt.graph.Plugin.graphSettings

site.settings

site.includeScaladoc()

ghpages.settings

git.remoteRepo := "git@github.com:wasted/solarium.git"

resolvers ++= Seq(
  "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.m2/repository",
  "wasted.io/repo" at "http://repo.wasted.io/mvn",
  "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
  "Maven Repo" at "http://repo1.maven.org/maven2/",
  "Typesafe Ivy Repo" at "http://repo.typesafe.com/typesafe/ivy-releases",
  "Typesafe Maven Repo" at "http://repo.typesafe.com/typesafe/releases/",
  "Java.net Maven2 Repository" at "http://download.java.net/maven/2/"
)
