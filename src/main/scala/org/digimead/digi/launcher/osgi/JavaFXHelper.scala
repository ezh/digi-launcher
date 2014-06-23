/**
 * Digi-Launcher - OSGi framework launcher for Equinox environment.
 *
 * Copyright (c) 2014 Alexey Aksenov ezh@ezh.msk.ru
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

import java.io.File
import java.net.{ URL, URLClassLoader }
import java.security.ProtectionDomain
import java.util.ArrayList
import org.digimead.digi.lib.log.api.XLoggable
import org.eclipse.osgi.baseadaptor.{ BaseData, HookConfigurator, HookRegistry }
import org.eclipse.osgi.baseadaptor.bundlefile.BundleEntry
import org.eclipse.osgi.baseadaptor.hooks.ClassLoadingHook
import org.eclipse.osgi.baseadaptor.loader.{ BaseClassLoader, ClasspathEntry, ClasspathManager }
import org.eclipse.osgi.framework.adaptor.{ BundleProtectionDomain, ClassLoaderDelegate }
import org.eclipse.osgi.internal.baseadaptor.DefaultClassLoader

/**
 * Hook that attach JavaFX library to SWT bundle
 */
class JavaFXHelper(library: File) extends ClassLoadingHook with XLoggable {
  log.debug("Inject JavaFX OSGi Helper")
  /** URL class loader with jfxrt.jar */
  val rtLoader = new URLClassLoader(Array(library.getCanonicalFile().toURI().toURL()), null)

  /**
   * Gets called by a classpath manager before defining a class.  This method allows a class loading hook
   * to process the bytes of a class that is about to be defined.
   * @param name the name of the class being defined
   * @param classbytes the bytes of the class being defined
   * @param classpathEntry the ClasspathEntry where the class bytes have been read from.
   * @param entry the BundleEntry source of the class bytes
   * @param manager the class path manager used to define the requested class
   * @return a modified array of classbytes or null if the original bytes should be used.
   */
  def processClass(name: String, classbytes: Array[Byte], classpathEntry: ClasspathEntry, entry: BundleEntry, manager: ClasspathManager): Array[Byte] = null
  /**
   * Gets called by a classpath manager when looking for ClasspathEntry objects.  This method allows
   * a classloading hook to add additional ClasspathEntry objects
   * @param cpEntries the list of ClasspathEntry objects currently available for the requested classpath
   * @param cp the name of the requested classpath
   * @param hostmanager the classpath manager the requested ClasspathEntry is for
   * @param sourcedata the source bundle data of the requested ClasspathEntry
   * @param sourcedomain the source domain of the requested ClasspathEntry
   * @return true if a ClasspathEntry has been added to cpEntries
   */
  def addClassPathEntry(cpEntries: ArrayList[ClasspathEntry], cp: String, hostmanager: ClasspathManager, sourcedata: BaseData, sourcedomain: ProtectionDomain): Boolean = {
    if (sourcedata.getSymbolicName() != "org.eclipse.swt")
      return false
    log.debug("Attach JavaFX class path to SWT bundle.")
    cpEntries.add(hostmanager.getExternalClassPath(library.toString(), sourcedata, sourcedomain))
    true
  }
  /**
   * Gets called by a base data during {@link BundleData#findLibrary(String)}.
   * A base data will call this method for each configured class loading hook until one
   * class loading hook returns a non-null value.  If no class loading hook returns
   * a non-null value then the base data will return null.
   * @param data the base data to find a native library for.
   * @param libName the name of the native library.
   * @return The absolute path name of the native library or null.
   */
  def findLibrary(data: BaseData, libName: String): String = null
  /**
   * Gets called by the adaptor during {@link FrameworkAdaptor#getBundleClassLoaderParent()}.
   * The adaptor will call this method for each configured class loading hook until one
   * class loading hook returns a non-null value.  If no class loading hook returns
   * a non-null value then the adaptor will perform the default behavior.
   * @return the parent classloader to be used by all bundle classloaders or null.
   */
  def getBundleClassLoaderParent(): ClassLoader = null
  /**
   * Gets called by a base data during
   * {@link BundleData#createClassLoader(ClassLoaderDelegate, BundleProtectionDomain, String[])}.
   * The BaseData will call this method for each configured class loading hook until one data
   * hook returns a non-null value.  If no class loading hook returns a non-null value then a
   * default implemenation of BundleClassLoader will be created.
   * @param parent the parent classloader for the BundleClassLoader
   * @param delegate the delegate for the bundle classloader
   * @param domain the domian for the bundle classloader
   * @param data the BundleData for the BundleClassLoader
   * @param bundleclasspath the classpath for the bundle classloader
   * @return a newly created bundle classloader
   */
  def createClassLoader(parent: ClassLoader, delegate: ClassLoaderDelegate, domain: BundleProtectionDomain, data: BaseData, bundleclasspath: Array[String]): BaseClassLoader = {
    log.debug("Attach JavaFX resources to " + data.getSymbolicName())
    new JavaFXHelper.ClassLoaderWithJavaFX(parent, delegate, domain, data, bundleclasspath, rtLoader)
  }
  /**
   * Gets called by a classpath manager at the end of
   * {@link ClasspathManager#initialize()}.
   * The classpath manager will call this method for each configured class loading hook after it
   * has been initialized.
   * @param baseClassLoader the newly created bundle classloader
   * @param data the BundleData associated with the bundle classloader
   */
  def initializedClassLoader(baseClassLoader: BaseClassLoader, data: BaseData) {}
}

object JavaFXHelper extends XLoggable {
  /** Get JavaFX library. */
  def getJavaFXRT(): Option[File] = {
    val home = new File(System.getProperty("java.home"))
    if (!home.exists()) {
      log.warn("Java home not exists")
      None
    } else {
      val lib = new File(home, "lib")
      if (!lib.exists()) {
        log.warn("Java library path not exists")
        None
      } else {
        val javafxPath = new File(lib, "jfxrt.jar")
        if (!javafxPath.exists()) {
          log.warn("jfxrt.jar at '%s' not found".format(javafxPath))
          None
        } else
          Some(javafxPath)
      }
    }
  }

  /**
   * OSGi hook
   * For example: osgi.hook.configurators.include=org.digimead.digi.launcher.osgi.JavaFXHelper$OSGiHook
   */
  class OSGiHook extends HookConfigurator {
    def addHooks(hookRegistry: HookRegistry) = getJavaFXRT() match {
      case Some(library) ⇒
        hookRegistry.addClassLoadingHook(new JavaFXHelper(library))
      case None ⇒
        log.warn("jfxrt.jar not found... support disabled.")
    }
  }
  /**
   * SWT bundle custom class loader.
   */
  class ClassLoaderWithJavaFX(parent: ClassLoader, delegate: ClassLoaderDelegate, domain: ProtectionDomain, bundledata: BaseData, classpath: Array[String], rtLoader: ClassLoader)
    extends DefaultClassLoader(parent, delegate, domain, bundledata, classpath) {
    override def getResource(name: String) = super.getResource(name) match {
      case null ⇒ rtLoader.getResource(name)
      case result ⇒ result
    }
    override def getResources(name: String) = {
      val enumeration1 = super.getResources(name)
      val enumeration2 = rtLoader.getResources(name)
      if (enumeration1.hasMoreElements() && enumeration2.hasMoreElements())
        new DualEnumeration(enumeration1, enumeration2)
      else if (enumeration1.hasMoreElements())
        enumeration1
      else
        enumeration2
    }
    override def getResourceAsStream(name: String) = super.getResourceAsStream(name) match {
      case null ⇒ rtLoader.getResourceAsStream(name)
      case result ⇒ result
    }
  }
  /**
   * Enumeration that merges two children.
   */
  class DualEnumeration(one: java.util.Enumeration[URL], two: java.util.Enumeration[URL]) extends java.util.Enumeration[URL] {
    def hasMoreElements() = one.hasMoreElements() && two.hasMoreElements()
    def nextElement() = if (one.hasMoreElements()) one.nextElement() else two.nextElement()
  }
}
