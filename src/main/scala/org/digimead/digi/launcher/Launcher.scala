/**
 * Digi-Launcher - OSGi framework launcher for Equinox environment.
 *
 * Copyright (c) 2013 Alexey Aksenov ezh@ezh.msk.ru
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.digimead.digi.launcher

import java.io.File
import java.net.URI
import java.net.URL
import java.net.URLClassLoader

import org.digimead.digi.launcher.report.ExceptionHandler
import org.digimead.digi.launcher.report.Report
import org.digimead.digi.lib.Activator
import org.digimead.digi.lib.aop.log
import org.digimead.digi.lib.api.DependencyInjection
import org.digimead.digi.lib.log.api.Loggable

import com.escalatesoft.subcut.inject.BindingModule
import com.escalatesoft.subcut.inject.Injectable

import language.reflectiveCalls

/**
 * Lightweight OSGi wrapper that starts Digi application.
 * There are no private/final members like
 *   public _final_ class EclipseStorageHook crap from Equinox team
 * Rewrite or extend it if needed. It is easy.
 */
class Launcher(implicit val bindingModule: BindingModule)
  extends Launcher.Interface with Injectable with Loggable {
  //
  // DI parameters
  //
  /** Path to the directory with OSGi bundles. */
  val bundles: File = inject[File]("Launcher.Bundles")
  /** Path to the directory with application data. */
  val data: File = inject[File]("Launcher.Data")
  //
  // Implementation variables
  //
  /** Path to launcher jar. */
  lazy val locationLauncher = getLauncherURL
  /** Path to OSGi framework */
  lazy val locationOSGi = getPackageURL(Launcher.OSGiPackage) getOrElse { throw new RuntimeException(s"${Launcher.OSGiPackage} not found") }
  /** Pack of URLs with boot bundle location. */
  lazy val rootClassLoaderBoot = Array(locationLauncher, locationOSGi)
  /** The framework class loader with bootPath URLs and delegation class loader */
  lazy val rootClassLoader = {
    val urls = rootClassLoaderBoot // The URLs from which to load classes and resources.
    val parentClassLoader = null // The parent class loader for default delegation
    val factory = null // The URLStreamHandlerFactory to use when creating URLs.
    val bootDelegationClassLoader = getClass.getClassLoader() // The boot delegation class loader for custom delegation expression
    new RootClassLoader(urls, parentClassLoader, factory, bootDelegationClassLoader)
  }
  /** The application launcher instance that is instantiated from the root classloader */
  lazy val applicationLauncher = {
    val clazz = rootClassLoader.loadClass(classOf[ApplicationLauncher].getName())
    val constructor = clazz.getConstructor(classOf[BindingModule])
    // Check that directory with bundles contains OSGi framework itself
    val frameworkBundleName = new File(locationOSGi.toURI).getName
    // This is the only println() in launcher. We haven't logging yet.
    System.out.println("Construct application environment:")
    System.out.println("\t launcher: " + locationLauncher)
    System.out.println("\t OSGi framework: " + locationOSGi)
    System.out.println("\t OSGi bundles: " + bundles)
    System.out.println("\t data directory: " + data)
    constructor.newInstance(bindingModule)
  }.asInstanceOf[{
    def getBundleClass(bundleSymbolicName: String, singletonClassName: String): Class[_]
    def initialize(applicationDI: Option[File], report: org.digimead.digi.launcher.report.api.Report)
    def run(waitForTermination: Boolean, shutdownHandler: Option[Runnable])
  }]
  assert(bundles.isDirectory() && bundles.canRead() && bundles.isAbsolute(), s"Bundles directory '${bundles}' is inaccessable or relative.")
  assert(data.isDirectory() && data.canRead() && data.isAbsolute(), s"Data directory '${data}' is inaccessable or relative.")

  /** Prepare OSGi framework settings. */
  @log
  def initialize(applicationDIScript: Option[File]) = {
    uncaughtExceptionHandler.register() // skip, if already registered
    applicationLauncher.initialize(applicationDIScript, Report)
  }
  /** Run OSGi framework and application. */
  @log
  def run(waitForTermination: Boolean, shutdownHandler: Option[Runnable]) =
    applicationLauncher.run(waitForTermination, shutdownHandler)

  /** Get to location that contains org.digimead.digi.launcher */
  protected def getLauncherURL(): URL = {
    val loader = classOf[ApplicationLauncher].getClassLoader()
    val classPath = classOf[ApplicationLauncher].getName.replaceAll("""\.""", "/") + ".class"
    classOf[ApplicationLauncher].getClassLoader.getResource(classPath) match {
      case url if url.getProtocol() == "jar" =>
        new URL(url.getFile().takeWhile(_ != '!'))
      case url =>
        val path = url.toURI().toString()
        new URI(path.substring(0, path.length() - classPath.length())).toURL
    }
  }
  /** Get to location that contains org.eclipse.osgi */
  protected def getPackageURL(packageName: String): Option[URL] = {
    val prefix = packageName + "-"
    getClass.getClassLoader() match {
      case loader: URLClassLoader =>
        val urls = loader.getURLs()
        urls.find { url =>
          url.getPath().split("/").lastOption match {
            case Some(name) =>
              name.startsWith(prefix)
            case None =>
              false
          }
        }
      case loader =>
        throw new IllegalStateException(s"Unable get location of ${Launcher.OSGiPackage} from unknown class loader " + loader.getClass())
    }
  }
  /** Get bundle class. */
  def getBundleClass(bundleSymbolicName: String, singletonClassName: String): Class[_] =
    applicationLauncher.getBundleClass(bundleSymbolicName, singletonClassName)
}

/**
 * Launcher that starts application.
 * launcher.Launcher --rootClassLoader-->       (Level1. Start simple launcher with logging and caching from non OSGi world.)
 *   launcher.ApplicationLauncher -->           (Level2. Start sandbox environment with overloaded FWK/classloader.)
 *     launcher.FrameworkLauncher -->           (Level3. Start OSGi launcher.)
 *       FrameworkAdaptor(BaseAdaptor) --FWK--> (Level4. Create platform specific support for the OSGi framework.)
 *         internal.core.Framework -->          (Level5. Start OSGi framework: Equinox.)
 *           EclipsePlatform -->                (Level6. Start base application platform.)
 *             DigiApplication                  (Level7. Bingo!)
 */
object Launcher extends Loggable {
  /** Current launcher instance. */
  @volatile private var launcher: Option[Launcher.Interface] = None
  /** Name of base package/jar with OSGi framework */
  val OSGiPackage = "org.eclipse.osgi"

  /**
   * Main application entry
   * There is only one implementation as simple as possible.
   * End users may copy'n'paste this code to modify startup sequence.
   * Our application will have: resultDI = applicationDI ~ launcherDI
   * @param launcherDI Consolidated dependency injection information for launcher.
   * @param applicationDI Consolidated dependency injection information for OSGi bundles.
   */
  def main[T](wait: Boolean, launcherDI: => BindingModule,
    bootstrapRegExp: Seq[String], applicationDIScript: Option[File] = None)(shutdownHook: => T) = synchronized {
    // Initialize DI, that may contains code with implicit OSGi initialization.
    // But this is not significant because we will have clean context from our framework loader
    // 1st DI - WINNER
    org.digimead.digi.lib.DependencyInjection.reset()
    org.digimead.digi.lib.DependencyInjection(launcherDI)
    // Start JVM wide logging/caching
    Activator.start()
    val bootstrap = DI.implementation
    launcher = Some(bootstrap)
    // Add bootstrap classes to FWK class loader.
    // For example:
    //   """^scala\..*"""
    //   """^com\.escalatesoft\..*"""
    //   """^.*\.api\..*"""
    bootstrapRegExp.foreach(bootstrap.rootClassLoader.addBootDelegationExpression)
    // We always propagate protocol handlers
    Option(System.getProperty("java.protocol.handler.pkgs")).foreach(_.split("""|""").foreach { pkg =>
      val pkgRegEx = "^" + pkg.trim.replaceAll("""\.""", """\.""")
      log.debug(s"Pass protocol handler '${pkg}' -> '${pkgRegEx}'")
      bootstrap.rootClassLoader.addBootDelegationExpression(pkgRegEx)
    })
    // Initialize application launcher within rootClassLoader context.
    bootstrap.initialize(applicationDIScript)
    // Run application launcher within rootClassLoader context.
    if (wait) {
      // Start synchronous.
      bootstrap.run(true, None)
      // Stop JVM wide logging/caching
      launcher = None
      Activator.stop()
      shutdownHook
    } else {
      // Start asynchronous.
      bootstrap.run(false, Some(new Runnable {
        // Stop JVM wide logging/caching
        def run = {
          launcher = None
          Activator.stop()
          shutdownHook
        }
      }))
    }
  }
  /** Get bundle singleton. */
  def singleton(bundleSymbolicName: String, singletonClassName: String): AnyRef = {
    assert(singletonClassName.endsWith("$"), "Incorrect singleton class name: " + singletonClassName)
    val launcher = this.launcher getOrElse
      { throw new IllegalStateException("Launcher is not initialized.") }
    val clazz = launcher.getBundleClass(bundleSymbolicName, singletonClassName)
    val declaredFields = clazz.getDeclaredFields().toList
    declaredFields.find(field => field.getName() == "MODULE$") match {
      case Some(modField) => modField.get(null)
      case None => throw new IllegalStateException(singletonClassName + " MODULE$ field not found.\n Singleton '" + clazz.getName + "' fields: " + declaredFields.mkString(","))
    }
  }

  /**
   * Launcher interface
   */
  trait Interface {
    /** global exception handler */
    lazy val uncaughtExceptionHandler = new ExceptionHandler()
    /** Application root class loader. */
    val rootClassLoader: RootClassLoader.Interface

    /** Prepare OSGi framework settings. */
    def initialize(applicationDIScript: Option[File])
    /** Run OSGi framework and application. */
    def run(waitForTermination: Boolean, shutdownHandler: Option[Runnable])
    /** Get bundle class. */
    def getBundleClass(bundleSymbolicName: String, singletonClassName: String): Class[_]
  }
  /**
   * Dependency injection routines.
   */
  private object DI extends DependencyInjection.PersistentInjectable {
    /** Launcher implementation. */
    lazy val implementation = inject[Interface]
  }
}
