name := "scala-ssh-shell"

organization := "com.wymanit"

version := "0.0.2-SNAPSHOT"

scalaVersion := "2.10.3"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature", "-Ywarn-all")

javacOptions ++= Seq("-encoding", "UTF-8")

libraryDependencies <++= (scalaVersion) { scalaVersion => Seq(
  "org.scala-lang" % "scala-compiler" % scalaVersion,
  "org.scala-lang" % "scala-reflect" % scalaVersion,
  "org.scala-lang" % "scala-library" % scalaVersion,
  "org.scala-lang" % "jline" % scalaVersion,
  "org.clapper" %% "grizzled-slf4j" % "1.0.1",
  "org.slf4j" % "slf4j-simple" % "1.7.6",
  "org.bouncycastle" % "bcprov-jdk15on" % "1.50",
  "commons-codec" % "commons-codec" % "1.9",
  "org.apache.sshd" % "sshd-core" % "0.10.0"
)}
