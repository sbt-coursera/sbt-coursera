sbtPlugin := true

name := "sbt-coursera"

organization := "ch.epfl.lamp"

version := "0.7"

description := "An sbt plugin for grading course assignments on coursera."

scalaVersion := "2.10.4"

// Actual settings
scalacOptions ++= Seq("-deprecation", "-feature")

resolvers += "Spray Repository" at "http://repo.spray.io"

resolvers += "OSSH" at "https://oss.sonatype.org/content/groups/public"

libraryDependencies += "org.scala-lang.modules" %% "scala-pickling" % "0.10.0"

libraryDependencies += "net.databinder" %% "dispatch-http" % "0.8.10"

libraryDependencies += "org.scalastyle" %% "scalastyle" % "0.3.2"

libraryDependencies += "io.spray" %% "spray-json" % "1.3.1"

// need scalatest also as a build dependency: the build implements a custom reporter
libraryDependencies += "org.scalatest" %% "scalatest" % "1.9.1"

libraryDependencies += "org.apache.commons" % "commons-lang3" % "3.1"

// sbteclipse depends on scalaz 7.0.2, so we can't use 7.1.0
libraryDependencies += "org.scalaz" %% "scalaz-core" % "7.0.2"

addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "3.0.0")

licenses := Seq("New BSD" -> url("https://raw2.github.com/vjovanov/sbt-coursera/master/LICENSE"))

homepage := Some(url("https://github.com/sbt-coursera/sbt-coursera/"))

organizationHomepage := Some(url("http://lamp.epfl.ch"))

scmInfo := Some(ScmInfo(
  url("https://github.com/sbt-coursera/sbt-coursera.git"),
      "git://github.com/sbt-coursera/sbt-coursera.git"))

pomExtra := (
  <developers>
    <developer>
      <id>vjovanov</id>
      <name>Vojin Jovanovic</name>
      <url>https://github.com/vjovanov</url>
    </developer>
  </developers>)

publishMavenStyle := true

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

publishArtifact in Test := false // just to be safe

scalariformSettings
