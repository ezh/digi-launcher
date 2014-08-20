/**
 * Digi-Launcher - OSGi framework launcher for Equinox environment.
 *
 * Copyright (c) 2013-2014 Alexey Aksenov ezh@ezh.msk.ru
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

package org.digimead.digi.launcher.osgi

import com.escalatesoft.subcut.inject.BindingModule
import java.io.File
import java.net.{ URISyntaxException, URL, URLClassLoader, URLDecoder }
import org.digimead.digi.lib.log.api.XLoggable
import org.eclipse.core.runtime.adaptor.LocationManager
import org.osgi.framework.Bundle
import org.osgi.framework.wiring.BundleWiring
import scala.language.reflectiveCalls

/** OSGi framework DI initializer */
class DI extends XLoggable {
  /** Evaluate DI from script with DI class loader */
  def evaluate(script: File, classLoader: DI.ClassLoader): Option[BindingModule] = {
    log.debug(s"Evaluate DI settings with classloader which is included ${classLoader.bundleClassLoaders.length} subloader(s).")
    val savedTCCL = Thread.currentThread().getContextClassLoader()
    try {
      Thread.currentThread().setContextClassLoader(classLoader)
      if (!script.exists() || !script.isFile()) {
        log.warn("Unable to find DI script: " + script.getCanonicalPath())
        return None
      }
      val scriptClazz = classLoader.loadClass("org.digimead.digi.launcher.osgi.Script")
      val scriptMethod = scriptClazz.getDeclaredMethod("apply", classOf[File])
      // None -> in memory compilation
      val eval = scriptClazz.newInstance()
      // Eval script as class Evaluator__... extends (() => Any) { def apply() = { SCRIPT } }
      val di = scriptMethod.invoke(eval, script).asInstanceOf[BindingModule]
      log.debug(s"DI file ${script} compiles successful.")
      Some(di)
    } finally Thread.currentThread().setContextClassLoader(savedTCCL)
  }
  /** Create DI consolidated class loader. */
  def initialize(framework: Framework): Option[DI.ClassLoader] = {
    log.debug("Initialize dependency injection.")
    val bundleContext = framework.getSystemBundleContext().getBundles().flatMap(bundle ⇒
      Option(try {
        Option(bundle.adapt(classOf[BundleWiring])) match {
          case Some(adapted) ⇒
            (bundle, adapted.getClassLoader())
          case None ⇒
            log.debug(s"Skip bundle ${bundle}: the classloader is unavailable.")
            null
        }
      } catch {
        case e: Throwable ⇒
          // Is it a BUG in OSGi implementation?
          if (bundle.getBundleId() != 0)
            log.debug(s"Unable to get bundle ${bundle} class loader: " + e.getMessage(), e)
          null
      }))
    if (bundleContext.isEmpty) {
      log.error("Unable to initialize dependency injection: there are no bundle class loaders discovered.")
      return None
    }
    log.debug("Create DI classloader with subloaders in order: \n\t" + bundleContext.map(_._1).mkString("\n\t"))
    // The original class loader from the outer world
    val delegationLoader = this.getClass().getClassLoader().
      asInstanceOf[{ val delegationLoader: ClassLoader }].delegationLoader.asInstanceOf[URLClassLoader]
    val urls = delegationLoader.getURLs() ++ framework.getSystemBundleContext().getBundles().flatMap(_.getLocation() match {
      case file if file.startsWith("initial@reference:file:") ⇒
        val urlUnfiltered = new URL(LocationManager.getInstallLocation().getURL() + file.drop(23))
        val fileUnfiltered = try new File(urlUnfiltered.toURI()) catch {
          case e: URISyntaxException ⇒
            new File(URLDecoder.decode(urlUnfiltered.getPath(), "UTF-8"))
        }
        Some(fileUnfiltered.getCanonicalFile().toURI.toURL)
      case other ⇒
        log.debug("Skip classpath entry " + other)
        None
    })
    Some(new DI.ClassLoader(getClass.getClassLoader(), urls.toSet.toArray, bundleContext.map(_._2)))
  }
}

object DI extends XLoggable {
  /**
   * Standard parent-first class loader with additional search over bundleClassLoaders
   */
  class ClassLoader(parent: java.lang.ClassLoader, urls: Array[URL],
    val bundleClassLoaders: Seq[java.lang.ClassLoader]) extends URLClassLoader(urls, null) {
    // It is never returns null, as the specification defines
    /** Loads the class with the specified binary name. */
    override protected def loadClass(name: String, resolve: Boolean): Class[_] = {
      // Try to load from this entry point.
      if (name.startsWith("org.digimead.digi.launcher.osgi.Script"))
        try {
          log.debug("Loading " + name)
          return super.loadClass(name, resolve)
        } catch {
          case _: ClassNotFoundException ⇒
        }
      log.debug("Try to load DI class " + name)

      // Try to load from parent loader.
      if (parent != null)
        try {
          val clazz = parent.loadClass(name)
          log.debug("Loading via parent(FWK) loader " + clazz)
          return clazz
        } catch {
          case _: ClassNotFoundException ⇒
        }

      // Try to load from the MOST SPECIFIC collected bundle class loader
      val applicant = bundleClassLoaders.map {
        case null ⇒
          None
        case bundleClassLoader if bundleClassLoader.getClass().getMethods.exists(_.getName() == "getBundle") ⇒
          val prefix = bundleClassLoader.asInstanceOf[{ def getBundle(): Bundle }].getBundle().
            getSymbolicName().takeWhile(c ⇒ c != '-' && c != '_')
          if (name.startsWith(prefix))
            Some(prefix.length(), bundleClassLoader)
          else
            None
        case bundleClassLoader ⇒
          log.error(s"Unknown class loader ${bundleClassLoader}: " + bundleClassLoader.getClass())
          None
      }
      applicant.flatten.sortBy(-_._1).foreach {
        case (prefixLength, bundleClassLoader) ⇒
          try {
            val clazz = bundleClassLoader.loadClass(name)
            log.debug(s"Loading via bundle loader ${bundleClassLoader}: " + clazz)
            return clazz
          } catch {
            case _: ClassNotFoundException ⇒
          }
      }

      // Try to load from ANY collected bundle class loader
      bundleClassLoaders.foreach { bundleClassLoader ⇒
        if (bundleClassLoader != null)
          try {
            val clazz = bundleClassLoader.loadClass(name)
            log.debug(s"Loading via bundle loader ${bundleClassLoader}: " + clazz)
            return clazz
          } catch {
            case _: ClassNotFoundException ⇒
          }
      }

      // Try to load from this loader as a last chance.
      try {
        val clazz = super.loadClass(name, resolve)
        log.debug("Loading directly from jar: " + clazz)
        return clazz
      } catch {
        case _: ClassNotFoundException ⇒
      }

      throw new ClassNotFoundException(name)
    }
  }
}
