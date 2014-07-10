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

import java.io.{ File, IOException }
import java.net.URL
import org.digimead.digi.launcher.ApplicationLauncher
import org.digimead.digi.lib.aop.log
import org.digimead.digi.lib.log.api.XLoggable
import org.eclipse.core.runtime.adaptor.LocationManager
import org.eclipse.core.runtime.internal.adaptor.{ EclipseAdaptorMsg, Semaphore }
import org.eclipse.osgi.framework.adaptor.{ FilePath, StatusException }
import org.eclipse.osgi.util.NLS
import org.osgi.framework.{ Bundle, BundleEvent, BundleException, FrameworkEvent, FrameworkListener, SynchronousBundleListener }
import org.osgi.framework.wiring.FrameworkWiring
import org.osgi.service.startlevel.StartLevel
import org.osgi.util.tracker.ServiceTracker
import scala.collection.JavaConversions.seqAsJavaList

/**
 * Helper routines that contain OSGi loading logic.
 */
class SupportBundleLoader(val supportLocator: SupportBundleLocator) extends XLoggable {
  @log
  def ensureBundlesActive(bundles: Array[Bundle], framework: Framework) {
    val context = framework.getSystemBundleContext()
    var tracker: ServiceTracker[StartLevel, StartLevel] = null
    try {
      for (bundle ← bundles) {
        if (bundle.getState() != Bundle.ACTIVE) {
          if (bundle.getState() == Bundle.INSTALLED) {
            log.error(NLS.bind(EclipseAdaptorMsg.ECLIPSE_STARTUP_ERROR_BUNDLE_NOT_RESOLVED, bundle.getLocation()))
          } else {
            // check that the startlevel allows the bundle to be active (111550)
            if (tracker == null) {
              tracker = new ServiceTracker[StartLevel, StartLevel](context, classOf[StartLevel].getName(), null)
              tracker.open()
            }
            val sl = tracker.getService()
            if (sl != null && (sl.getBundleStartLevel(bundle) <= sl.getStartLevel()))
              log.error(NLS.bind(EclipseAdaptorMsg.ECLIPSE_STARTUP_ERROR_BUNDLE_NOT_ACTIVE, bundle))
          }
        }
      }
    } finally {
      if (tracker != null)
        tracker.close()
    }
  }
  /** Get the list of currently installed initial bundles from the framework */
  @log
  def getCurrentBundles(includeInitial: Boolean, framework: Framework): Array[Bundle] = {
    log.debug("Collect current bundles.")
    val installed = framework.getSystemBundleContext().getBundles()
    var initial = Array[Bundle]()
    for (i ← 0 until installed.length) {
      val bundle = installed(i)
      if (bundle.getLocation().startsWith(Framework.INITIAL_LOCATION)) {
        if (includeInitial)
          initial = initial :+ bundle
      } else if (!includeInitial && bundle.getBundleId() != 0)
        initial = initial :+ bundle
    }
    return initial
  }
  /** Get the initial bundle list from the PROP_EXTENSIONS and PROP_BUNDLES */
  @log
  def getInitialBundles(bundles: String, filterNames: Seq[String], defaultStartLevel: Int): Array[ApplicationLauncher.InitialBundle] = {
    log.debug("Collect initial bundles.")
    val initialBundles: Array[String] = bundles.split(",").map(_.trim).filterNot(_.isEmpty())
    // should canonicalize the syspath.
    val syspath = try {
      new File(supportLocator.getSysPath()).getCanonicalPath()
    } catch {
      case e: IOException ⇒
        supportLocator.getSysPath()
    }
    log.trace("Loading...")
    for (initialBundle ← initialBundles) yield {
      var level = defaultStartLevel
      var start = false
      var index = initialBundle.lastIndexOf('@')
      val name = if (index >= 0) {
        for (attribute ← initialBundle.substring(index + 1, initialBundle.length()).split(":").map(_.trim)) {
          if (attribute.equals("start"))
            start = true
          else {
            try {
              level = Integer.parseInt(attribute);
            } catch { // bug 188089 - can't launch an OSGi bundle if the path of its plugin project contains the character "@"
              case e: NumberFormatException ⇒
                index = initialBundle.length()
            }
          }
        }
        initialBundle.substring(0, index)
      } else initialBundle
      try {
        if (!name.trim().startsWith("#")) // skip bundles, that started with comment sign '#'
          Option(supportLocator.searchForBundle(name, syspath)) match {
            case Some(location) ⇒
              val relative = makeRelative(LocationManager.getInstallLocation().getURL(), location)
              val locationString = Framework.INITIAL_LOCATION + relative.toExternalForm()
              Some(ApplicationLauncher.InitialBundle(initialBundle, locationString, location, getBundleNameFromArgument(name), level, start))
            case None ⇒
              log.error(NLS.bind(EclipseAdaptorMsg.ECLIPSE_STARTUP_BUNDLE_NOT_FOUND, initialBundle))
              None
          }
        else
          None
      } catch {
        case e: IOException ⇒
          log.error(e.getMessage(), e)
          None
      }
    }
  }.flatten.filterNot(bundle ⇒ filterNames.contains(bundle.name))
  /** Extract bundle name from string argument from config.ini, PROP_BUNDLES or PROP_EXTENSIONS */
  def getBundleNameFromArgument(argument: String): String = {
    // cut suffix after @
    val internal = argument.lastIndexOf("@")
    val entry = if (internal > -1)
      argument.substring(0, internal)
    else
      argument
    // get full name (like my-library_2.10-0.0.1.0-SNAPSHOT.jar) from entry in possible
    val fullName = try {
      // sometimes it maybe like file:lib/library-N.N.jar
      new File(new URL(entry).getFile().split(":").last).getName.trim
    } catch {
      case e: Throwable ⇒
        try {
          new File(entry).getName.trim
        } catch {
          case e: Throwable ⇒
            entry.trim
        }
    }
    // chop version
    fullName.replaceAll("""_\d+.*$""", "").replaceAll("""-\d+.*$""", "").replaceAll(""".jar$""", "")
  }
  /**
   * Install the initialBundles that are not already installed.
   *
   * @return list of bundles to lazy activation, to start, to refresh
   */
  @log
  def installBundles(initial: Array[ApplicationLauncher.InitialBundle], current: Array[Bundle],
    framework: Framework): (Array[Bundle], Array[Bundle], Array[Bundle]) = {
    var lazyActivationBundles = Array[Bundle]()
    var startBundles = Array[Bundle]()
    var toRefresh = Array[Bundle]()
    val context = framework.getSystemBundleContext()
    val reference = context.getServiceReference(classOf[StartLevel].getName())
    var startService: StartLevel = null
    if (reference != null)
      startService = context.getService(reference).asInstanceOf[StartLevel]
    try {
      for (initialBundle ← initial) {
        try {
          // don't need to install if it is already installed
          val osgiBundle = current.find(bundle ⇒ initialBundle.locationString.equalsIgnoreCase(bundle.getLocation())) getOrElse {
            val in = initialBundle.location.openStream()
            val bundle = try {
              context.installBundle(initialBundle.locationString, in)
            } catch {
              case e: BundleException ⇒
                e match {
                  case status: StatusException if status.getStatusCode() == StatusException.CODE_OK && status.getStatus().isInstanceOf[Bundle] ⇒
                    status.getStatus().asInstanceOf[Bundle]
                  case err ⇒
                    throw err
                }
            }
            // only check for lazy activation header if this is a newly installed bundle and is not marked for persistent start
            if (!initialBundle.start && framework.hasLazyActivationPolicy(bundle))
              lazyActivationBundles = lazyActivationBundles :+ bundle
            bundle
          }
          // always set the startlevel incase it has changed (bug 111549)
          // this is a no-op if the level is the same as previous launch.
          if ((osgiBundle.getState() & Bundle.UNINSTALLED) == 0 && initialBundle.level >= 0 && startService != null)
            startService.setBundleStartLevel(osgiBundle, initialBundle.level);
          // if this bundle is supposed to be started then add it to the start list
          if (initialBundle.start)
            startBundles = startBundles :+ osgiBundle
          // include basic bundles in case they were not resolved before
          if ((osgiBundle.getState() & Bundle.INSTALLED) != 0)
            toRefresh = toRefresh :+ osgiBundle
        } catch {
          case e: BundleException ⇒
            log.error(NLS.bind(EclipseAdaptorMsg.ECLIPSE_STARTUP_FAILED_INSTALL, initialBundle.location), e)
          case e: IOException ⇒
            log.error(NLS.bind(EclipseAdaptorMsg.ECLIPSE_STARTUP_FAILED_INSTALL, initialBundle.location), e)
        }
      }
    } finally {
      if (reference != null)
        context.ungetService(reference)
    }
    (lazyActivationBundles, startBundles, toRefresh)
  }
  // returns true if the refreshPackages operation caused the framework to shutdown
  @log
  def refreshPackages(bundles: Array[Bundle], framework: Framework): Boolean = {
    val context = framework.getSystemBundleContext()
    // TODO this is such a hack it is silly.  There are still cases for race conditions etc
    // but this should allow for some progress...
    val semaphore = new Semaphore(0)
    val listener = new SupportBundleLoader.StartupEventListener(semaphore, FrameworkEvent.PACKAGES_REFRESHED)
    context.addFrameworkListener(listener)
    context.addBundleListener(listener)
    val frameworkWiring = context.getBundle(0).adapt(classOf[FrameworkWiring])
    frameworkWiring.refreshBundles(bundles.toSeq, listener)
    //updateSplash(semaphore, listener)
    Framework.isForcedRestart()
  }
  @log
  def setStartLevel(frameworkStartLevel: Int, framework: Framework) {
    val context = framework.getSystemBundleContext()
    val reference = context.getServiceReference(classOf[StartLevel].getName())
    val startLevel = if (reference != null) context.getService(reference).asInstanceOf[StartLevel] else null
    if (startLevel != null) {
      val semaphore = new Semaphore(0)
      val listener = new SupportBundleLoader.StartupEventListener(semaphore, FrameworkEvent.STARTLEVEL_CHANGED)
      context.addFrameworkListener(listener)
      context.addBundleListener(listener)
      startLevel.setStartLevel(frameworkStartLevel)
      context.ungetService(reference)
      //updateSplash(semaphore, listener)
    }
  }
  @log
  def startBundles(startBundles: Array[Bundle], lazyBundles: Array[Bundle]) {
    for (i ← 0 until startBundles.length)
      startBundle(startBundles(i), 0)
    for (i ← 0 until lazyBundles.length)
      startBundle(lazyBundles(i), Bundle.START_ACTIVATION_POLICY)
  }
  /**
   * Uninstall any of the currently installed bundles that do not exist in the
   * initial bundle list from installEntries.
   *
   * @return list of uninstalled bundles to refresh
   */
  @log
  def uninstallBundles(current: Array[Bundle], initial: Array[ApplicationLauncher.InitialBundle]): Array[Bundle] = {
    for (currentBundle ← current) yield {
      if (!initial.exists(initialBundle ⇒ currentBundle.getLocation().equalsIgnoreCase(initialBundle.locationString)))
        try {
          currentBundle.uninstall()
          Some(currentBundle)
        } catch {
          case e: BundleException ⇒
            log.error(NLS.bind(EclipseAdaptorMsg.ECLIPSE_STARTUP_FAILED_UNINSTALL, currentBundle.getLocation()), e)
            None
        }
      else
        None
    }
  }.flatten

  /**
   * Returns a URL which is equivalent to the given URL relative to the
   * specified base URL. Works only for file: URLs
   * @throws MalformedURLException
   */
  protected def makeRelative(base: URL, location: URL): URL = {
    if (base == null)
      return location
    if (!"file".equals(base.getProtocol())) //$NON-NLS-1$
      return location
    if (!location.getProtocol().equals(Framework.REFERENCE_PROTOCOL))
      return location // we can only make reference urls relative
    val nonReferenceLocation = new URL(location.getPath())
    // if some URL component does not match, return the original location
    if (!base.getProtocol().equals(nonReferenceLocation.getProtocol()))
      return location
    val locationPath = new File(nonReferenceLocation.getPath())
    // if location is not absolute, return original location
    if (!locationPath.isAbsolute())
      return location
    val relativePath = makeRelative(new File(base.getPath()), locationPath)
    var urlPath = relativePath.getPath()
    if (File.separatorChar != '/')
      urlPath = urlPath.replace(File.separatorChar, '/')
    if (nonReferenceLocation.getPath().endsWith("/")) //$NON-NLS-1$
      // restore original trailing slash
      urlPath += '/'
    // couldn't use File to create URL here because it prepends the path with user.dir
    var relativeURL = new URL(base.getProtocol(), base.getHost(), base.getPort(), urlPath)
    // now make it back to a reference URL
    relativeURL = new URL(Framework.REFERENCE_SCHEME + relativeURL.toExternalForm())
    relativeURL
  }
  protected def makeRelative(base: File, location: File): File =
    if (!location.isAbsolute())
      location
    else
      new File(new FilePath(base).makeRelative(new FilePath(location)))
  @log
  protected def startBundle(bundle: Bundle, options: Int) {
    try {
      options match {
        case Bundle.START_TRANSIENT ⇒
          log.debug("Start TRANSIENT bundle " + bundle)
          bundle.start(options)
        case Bundle.START_ACTIVATION_POLICY ⇒
          log.debug("Start LAZY bundle " + bundle)
          bundle.start(options)
        case _ ⇒
          log.debug("Start bundle " + bundle)
          bundle.start(options)
      }
    } catch {
      case e: BundleException ⇒
        if ((bundle.getState() & Bundle.RESOLVED) != 0) {
          // only log errors if the bundle is resolved
          log.error(NLS.bind(EclipseAdaptorMsg.ECLIPSE_STARTUP_FAILED_START, bundle.getLocation()), e)
        }
    }
  }
}

object SupportBundleLoader {
  class StartupEventListener(semaphore: Semaphore, frameworkEventType: Int) extends SynchronousBundleListener with FrameworkListener {
    def bundleChanged(event: BundleEvent) {
      if (event.getBundle().getBundleId() == 0 && event.getType() == BundleEvent.STOPPING)
        semaphore.release();
    }
    def frameworkEvent(event: FrameworkEvent) {
      if (event.getType() == frameworkEventType)
        semaphore.release();
    }
  }
}
