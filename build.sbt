name := "scala-ssh-shell"

organization := "com.wymanit"

version := "0.0.1-SNAPSHOT"

scalaVersion := "2.10.3"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

javacOptions ++= Seq("-encoding", "UTF-8")

libraryDependencies <++= (scalaVersion) { scalaVersion => Seq(
  "org.scala-lang" % "scala-compiler" % scalaVersion,
  "org.scala-lang" % "jline" % scalaVersion,
  "org.clapper" %% "grizzled-slf4j" % "1.0.1",
  "org.slf4j" % "slf4j-simple" % "1.7.6",
  "org.bouncycastle" % "bcprov-jdk15on" % "1.50",
  "org.apache.sshd" % "sshd-core" % "0.10.0"
)}
