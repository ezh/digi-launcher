//
// Digi-Launcher - OSGi framework launcher for Equinox environment.
//
// Copyright (c) 2013-2014 Alexey Aksenov ezh@ezh.msk.ru
// All rights reserved.
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU Lesser General Public License as published by the Free
// Software Foundation, either version 3 of the License, or (at your option) any
// later version.
//
// This program is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
// FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
// details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with this program. If not, see <http://www.gnu.org/licenses/>.
//

// DEVELOPMENT CONFIGURATION

import sbt.osgi.manager._

OSGiManager // ++ sbt.scct.ScctPlugin.instrumentSettings - ScctPlugin is broken, have no time to fix

name := "Digi-Launcher"

description := "OSGi framework launcher for Equinox environment"

licenses := Seq("GNU Lesser General Public License, Version 3.0" -> url("http://www.gnu.org/licenses/lgpl-3.0.txt"))

organization := "org.digimead"

organizationHomepage := Some(url("http://digimead.org"))

homepage := Some(url("https://github.com/ezh/digi-launcher"))

version <<= (baseDirectory) { (b) => scala.io.Source.fromFile(b / "version").mkString.trim }

inConfig(OSGiConf)({
  import OSGiKey._
  Seq[Project.Setting[_]](
    osgiBndBundleSymbolicName := "org.digimead.digi.launcher",
    osgiBndBundleCopyright := "Copyright © 2013-2014 Alexey B. Aksenov/Ezh. All rights reserved.",
    osgiBndExportPackage := List("org.digimead.*"),
    osgiBndImportPackage := List("!org.aspectj.*", "*"),
    osgiBndBundleLicense := "http://www.gnu.org/licenses/lgpl-3.0.txt;description=GNU Lesser General Public License, Version 3.0"
  )
})

crossScalaVersions := Seq("2.11.1")

scalaVersion := "2.11.1"

scalacOptions ++= Seq("-encoding", "UTF-8", "-deprecation", "-unchecked", "-Xcheckinit", "-feature")

// http://vanillajava.blogspot.ru/2012/02/using-java-7-to-target-much-older-jvms.html
javacOptions ++= Seq("-Xlint:unchecked", "-Xlint:deprecation", "-source", "1.7", "-target", "1.7")

javacOptions in doc := Seq("-source", "1.7")

if (sys.env.contains("XBOOTCLASSPATH")) Seq(javacOptions += "-Xbootclasspath:" + sys.env("XBOOTCLASSPATH")) else Seq()

//
// Custom local options
//

resolvers += "digimead-maven" at "http://storage.googleapis.com/maven.repository.digimead.org/"

libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % "2.11.1",
    "org.digimead" %% "digi-lib" % "0.3.0.0-SNAPSHOT",
    "org.digimead" %% "digi-lib-test" % "0.3.0.0-SNAPSHOT" % "test",
    "org.eclipse" % "org.eclipse.osgi" % "3.8.0.v20120529-1548",
    "org.osgi" % "org.osgi.core" % "5.0.0",
    "org.osgi" % "org.osgi.compendium" % "4.3.1"
  )

//
// Testing
//

parallelExecution in Test := false

testGrouping in Test <<= (definedTests in Test) map { tests =>
  tests map { test =>
    new Tests.Group(
      name = test.name,
      tests = Seq(test),
      runPolicy = Tests.SubProcess(javaOptions = Seq.empty[String]))
  }
}

//logLevel := Level.Debug
