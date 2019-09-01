organization := "xyz.kamyar"

version := "0.1"

name := "sha3"

scalaVersion := "2.12.8"

libraryDependencies ++= Seq(
  "edu.berkeley.cs" %% "chisel-testers2" % "0.1-SNAPSHOT" % Test,
  "edu.berkeley.cs" %% "chisel-iotesters" % "1.3-SNAPSHOT" % Test,
  "org.scalatest" %% "scalatest" % "3.0.+" % Test,
  "org.scalacheck" %% "scalacheck" % "1.14.+" % Test,
  "org.bouncycastle" % "bcprov-jdk15on" % "1.+" % Test,
)