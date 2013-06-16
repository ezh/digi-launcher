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

package org.digimead.tabuddy.desktop.launcher

import java.io.File
import java.io.IOException
import java.net.MalformedURLException
import java.net.URL
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong

import scala.Array.canBuildFrom
import scala.collection.JavaConversions._

import org.digimead.digi.lib.aop.log
import org.digimead.digi.lib.api.DependencyInjection
import org.digimead.digi.lib.log.api.Loggable
import org.eclipse.core.runtime.adaptor.EclipseStarter
import org.eclipse.core.runtime.adaptor.LocationManager
import org.eclipse.core.runtime.internal.adaptor.EclipseAdaptorMsg
import org.eclipse.core.runtime.internal.adaptor.MessageHelper
import org.eclipse.osgi.framework.adaptor.FrameworkAdaptor
import org.eclipse.osgi.framework.internal.core.ConsoleManager
import org.eclipse.osgi.framework.internal.core.FrameworkProperties
import org.eclipse.osgi.framework.log.FrameworkLogEntry
import org.eclipse.osgi.internal.baseadaptor.BaseStorageHook
import org.eclipse.osgi.internal.profile.Profile
import org.eclipse.osgi.service.resolver.BundleDescription
import org.eclipse.osgi.service.resolver.BundleSpecification
import org.eclipse.osgi.service.resolver.ImportPackageSpecification
import org.eclipse.osgi.service.resolver.VersionConstraint
import org.eclipse.osgi.util.NLS
import org.osgi.framework.Bundle
import org.osgi.framework.BundleContext
import org.osgi.framework.BundleEvent
import org.osgi.framework.BundleListener
import org.osgi.framework.Constants
import org.osgi.framework.ServiceReference
import org.osgi.framework.ServiceRegistration
import org.osgi.service.log.LogEntry
import org.osgi.service.log.LogListener
import org.osgi.service.log.LogReaderService
import org.osgi.service.log.LogService
import org.osgi.util.tracker.ServiceTracker
import org.osgi.util.tracker.ServiceTrackerCustomizer
import org.slf4j.LoggerFactory

import com.escalatesoft.subcut.inject.BindingModule

/**
 * Framework launcher that is used by Application launcher.
 */
class FrameworkLauncher extends BundleListener with Loggable {
  /** Contains last bundle event. */
  lazy val lastBundleEvent = new AtomicLong()
  /** OSGi log bridge */
  lazy val logBridge = new FrameworkLauncher.OSGiLogBridge
  /** Helper with bundle location logic */
  lazy val supportLocator = new osgi.SupportBundleLocator()
  /** Helper with framework loading logic */
  lazy val supportLoader = new osgi.SupportBundleLoader(supportLocator)
  /** Dependency Injection service */
  @volatile protected var dependencyInjectionService: Option[FrameworkLauncher.DependencyInjectionService] = None
  /** Dependency Injection service */
  @volatile protected var dependencyInjectionRegistration: Option[ServiceRegistration[DependencyInjection]] = None

  /** Check OSGi framework launch. */
  @log
  def check(framework: osgi.Framework): Boolean = {
    val bundles = framework.getSystemBundleContext().getBundles()
    val broken = bundles.filterNot(b => b.getState() == Bundle.RESOLVED || b.getState() == Bundle.STARTING || b.getState() == Bundle.ACTIVE).map { broken =>
      val state = broken.getState() match {
        case Bundle.UNINSTALLED => "UNINSTALLED"
        case Bundle.INSTALLED => "INSTALLED"
        case Bundle.RESOLVED => "RESOLVED"
        case Bundle.STARTING => "STARTING"
        case Bundle.STOPPING => "STOPPING"
        case Bundle.ACTIVE => "ACTIVE"
        case unknown => "UNKNOWN " + unknown
      }
      s"\tUnexpected state $state for bundle $broken"
    }
    if (broken.nonEmpty) {
      log.error(s"Framework coherence is absent:\n" + broken.mkString("\n"))
      false
    } else
      true
  }
  /** Finish processes of OSGi framework. */
  @log
  def finish(shutdownListeners: Seq[BundleListener], console: Option[ConsoleManager], framework: osgi.Framework) {
    log.info("Finish processes of OSGi framework.")
    (shutdownListeners :+ this).foreach(l => try { framework.getSystemBundleContext().removeBundleListener(l) } catch { case e: Throwable => })
    console.foreach(console => try { console.stopConsole() } catch { case e: Throwable => log.warn("Unable to stop OSGi console: " + e, e) })
    try { unregisterLogService(framework) } catch { case e: Throwable => log.warn("Unable to unregister bridge OSGi log service: " + e, e) }
    dependencyInjectionRegistration = None
    dependencyInjectionService = None
  }
  /** Initialize DI for OSGi framework. */
  @log
  def initializeDI(diScript: File, framework: osgi.Framework) {
    log.debug("Initialize DI for OSGi.")
    val diInjector = new osgi.DI
    diInjector.initialize(framework).foreach { diClassLoader =>
      diInjector.evaluate(diScript, diClassLoader).foreach { di =>
        // di is initialized within diClassLoader environment
        log.info("Inject DI settings from " + diScript)
        dependencyInjectionService = Some(new FrameworkLauncher.DependencyInjectionService(di))
        dependencyInjectionRegistration = Some(framework.getSystemBundleContext().registerService(classOf[DependencyInjection], dependencyInjectionService.get, null))
      }
    }
  }
  /** Launch OSGi framework. */
  @log
  def launch(shutdownHandlers: Seq[Runnable]): (osgi.Framework, Seq[BundleListener]) = {
    log.info("Launch OSGi framework.")
    Properties.initialize()
    // after this: system bundle RESOLVED, all bundles INSTALLED
    val framework = create()
    val shutdownListeners = framework.registerShutdownHandlers(shutdownHandlers)
    framework.getSystemBundleContext().addBundleListener(this)
    // after this: system bundle STARTING, all bundles RESOLVED/INSTALLED
    framework.launch()
    registerLogService(framework)
    (framework, shutdownListeners)
  }
  /**
   * Ensure all basic bundles are installed, resolved and scheduled to start. Returns a sequence containing
   * all basic bundles that are marked to start.
   * Returns None if the framework has been shutdown as a result of refreshPackages
   */
  @log
  def loadBundles(defaultStartLevel: Int, framework: osgi.Framework): (Boolean, Array[Bundle], Array[Bundle]) = {
    log.info("Load OSGi bundles.")
    log.debug("Loading OSGi bundles.")
    val primaryBundles = FrameworkProperties.getProperty(EclipseStarter.PROP_BUNDLES)
    val initialPrimaryBundles = supportLoader.getInitialBundles(primaryBundles, Seq(Launcher.OSGiPackage), defaultStartLevel)
    val secondaryBundles = FrameworkProperties.getProperty(EclipseStarter.PROP_EXTENSIONS)
    val initialSecondaryBundles = supportLoader.getInitialBundles(secondaryBundles, Seq(Launcher.OSGiPackage), defaultStartLevel)
    val initialBundles = initialPrimaryBundles ++ initialSecondaryBundles.
      filterNot(secondary => initialPrimaryBundles.exists(_.name == secondary.name))
    val availableBundles = initialBundles.map(_.originalString.trim)
    FrameworkProperties.setProperty(EclipseStarter.PROP_BUNDLES, availableBundles.mkString(","))
    log.trace("Complete initial bundle list:\n\t" + availableBundles.sorted.mkString("\n\t"))
    val currentBundles = supportLoader.getCurrentBundles(true, framework)
    log.debug(s"Initial bundles:\n\t${initialBundles.map(_.originalString).mkString("\n\t")}")
    log.debug(s"Current bundles:\n\t${currentBundles.mkString("\n\t")}")
    val uninstalledBundles = supportLoader.uninstallBundles(currentBundles, initialBundles)
    val (toLazyActivation, toStart, installedBundles) = supportLoader.installBundles(initialBundles, currentBundles, framework)
    log.debug(s"""Uninstalled bundles: (${uninstalledBundles.mkString(", ")})""")
    log.debug(s"""Installed bundles: (${installedBundles.mkString(", ")})""")
    log.debug(s"""Lazy activation bundles: (${toLazyActivation.mkString(", ")})""")
    log.debug(s"""To start bundles: (${toStart.mkString(", ")})""")
    val toRefresh = (uninstalledBundles ++ installedBundles).distinct

    // If we installed/uninstalled something, force a refresh of all installed/uninstalled bundles
    if (!toRefresh.isEmpty && supportLoader.refreshPackages(toRefresh, framework))
      (false, toLazyActivation, toStart) // refreshPackages try to shutdown the framework
    else
      (true, toLazyActivation, toStart)
  }
  def logUnresolvedBundles(framework: osgi.Framework) = {
    val bundles = framework.getSystemBundleContext().getBundles()
    val state = framework.frameworkAdaptor.getState()
    val logService = framework.frameworkAdaptor.getFrameworkLog()
    val stateHelper = framework.frameworkAdaptor.getPlatformAdmin().getStateHelper()

    // first lets look for missing leaf constraints (bug 114120)
    val leafConstraints = stateHelper.getUnsatisfiedLeaves(state.getBundles())
    // hash the missing leaf constraints by the declaring bundles
    val missing = new java.util.HashMap[BundleDescription, java.util.List[VersionConstraint]]()
    for (leafConstraint <- leafConstraints) {
      // only include non-optional and non-dynamic constraint leafs
      leafConstraint match {
        case leafConstraint: BundleSpecification if leafConstraint.isOptional =>
        case leafConstraint: BundleSpecification =>
          val bundle = leafConstraint.getBundle();
          val constraints = Option(missing.get(bundle)).getOrElse {
            val constraints = new java.util.ArrayList[VersionConstraint]()
            missing.put(bundle, constraints)
            constraints
          }
          constraints.add(leafConstraint)
        case leafConstraint: ImportPackageSpecification =>
          if (!ImportPackageSpecification.RESOLUTION_OPTIONAL.equals(leafConstraint.getDirective(Constants.RESOLUTION_DIRECTIVE)) &&
            !ImportPackageSpecification.RESOLUTION_DYNAMIC.equals(leafConstraint.getDirective(Constants.RESOLUTION_DIRECTIVE))) {
            val bundle = leafConstraint.getBundle();
            val constraints = Option(missing.get(bundle)).getOrElse {
              val constraints = new java.util.ArrayList[VersionConstraint]()
              missing.put(bundle, constraints)
              constraints
            }
            constraints.add(leafConstraint)
          }
      }
    }
    // found some bundles with missing leaf constraints; log them first
    if (missing.size() > 0) {
      val rootChildren = new Array[FrameworkLogEntry](missing.size())
      var rootIndex = 0
      for (description <- missing.keySet()) {
        val symbolicName = Option(description.getSymbolicName()) getOrElse (FrameworkAdaptor.FRAMEWORK_SYMBOLICNAME)
        val generalMessage = NLS.bind(EclipseAdaptorMsg.ECLIPSE_STARTUP_ERROR_BUNDLE_NOT_RESOLVED, description.getLocation());
        val constraints = missing.get(description)
        val logChildren = new Array[FrameworkLogEntry](constraints.size())
        for (i <- 0 until logChildren.length)
          logChildren(i) = new FrameworkLogEntry(symbolicName, FrameworkLogEntry.WARNING, 0, MessageHelper.getResolutionFailureMessage(constraints.get(i)), 0, null, null)
        rootChildren(rootIndex) = new FrameworkLogEntry(FrameworkAdaptor.FRAMEWORK_SYMBOLICNAME, FrameworkLogEntry.WARNING, 0, generalMessage, 0, null, logChildren)
        rootIndex += 1
      }
      logService.log(new FrameworkLogEntry(FrameworkAdaptor.FRAMEWORK_SYMBOLICNAME, FrameworkLogEntry.WARNING, 0, EclipseAdaptorMsg.ECLIPSE_STARTUP_ROOTS_NOT_RESOLVED, 0, null, rootChildren))
    }
    // There may be some bundles unresolved for other reasons, causing the system to be unresolved
    // log all unresolved constraints now
    val allChildren = new java.util.ArrayList[FrameworkLogEntry]()
    for (bundle <- bundles)
      if (bundle.getState() == Bundle.INSTALLED) {
        val symbolicName = Option(bundle.getSymbolicName()) getOrElse (FrameworkAdaptor.FRAMEWORK_SYMBOLICNAME)
        val generalMessage = NLS.bind(EclipseAdaptorMsg.ECLIPSE_STARTUP_ERROR_BUNDLE_NOT_RESOLVED, bundle)
        val description = state.getBundle(bundle.getBundleId())
        // for some reason, the state may does not know about that bundle
        if (description != null) {
          var logChildren: Array[FrameworkLogEntry] = null
          val unsatisfied = stateHelper.getUnsatisfiedConstraints(description)
          if (unsatisfied.length > 0) {
            // the bundle wasn't resolved due to some of its constraints were unsatisfiable
            logChildren = new Array[FrameworkLogEntry](unsatisfied.length)
            for (j <- 0 until unsatisfied.length)
              logChildren(j) = new FrameworkLogEntry(symbolicName, FrameworkLogEntry.WARNING, 0, MessageHelper.getResolutionFailureMessage(unsatisfied(j)), 0, null, null)
          } else {
            val resolverErrors = state.getResolverErrors(description)
            if (resolverErrors.length > 0) {
              logChildren = new Array[FrameworkLogEntry](resolverErrors.length)
              for (j <- 0 until resolverErrors.length)
                logChildren(j) = new FrameworkLogEntry(symbolicName, FrameworkLogEntry.WARNING, 0, resolverErrors(j).toString(), 0, null, null)
            }
          }
          allChildren.add(new FrameworkLogEntry(FrameworkAdaptor.FRAMEWORK_SYMBOLICNAME, FrameworkLogEntry.WARNING, 0, generalMessage, 0, null, logChildren));
        }
      }
    if (allChildren.size() > 0)
      logService.log(new FrameworkLogEntry(FrameworkAdaptor.FRAMEWORK_SYMBOLICNAME, FrameworkLogEntry.WARNING, 0, EclipseAdaptorMsg.ECLIPSE_STARTUP_ALL_NOT_RESOLVED, 0, null, allChildren.toArray(new Array[FrameworkLogEntry](allChildren.size()))))
  }
  /** Create bridge between OSGi log service and application logger */
  @log
  def registerLogService(framework: osgi.Framework) =
    logBridge.register(framework.getSystemBundleContext())
  /** Schedule all bundles to be started */
  @log
  def startBundles(initialStartLevel: Int, lazyActivationBundles: Array[Bundle], toStartBundles: Array[Bundle], framework: osgi.Framework) {
    log.info("Start OSGi bundles")
    supportLoader.startBundles(toStartBundles, lazyActivationBundles)
    // set the framework start level to the ultimate value.  This will actually start things
    // running if they are persistently active.
    supportLoader.setStartLevel(initialStartLevel, framework)
    // they should all be active by this time
    supportLoader.ensureBundlesActive(toStartBundles, framework)
  }
  /** Destroy bridge between OSGi log service and application logger */
  def unregisterLogService(framework: osgi.Framework) =
    logBridge.unregister()
  /** Wait for consistent state of framework (all bundles loaded and resolver). */
  @log
  def waitForConsitentState(timeout: Long, framework: osgi.Framework): Boolean = {
    val frame = 400 // 0.4s for decision
    /* Bundle.STARTING is here because:
	 *   If the bundle has a {@link Constants#ACTIVATION_LAZY lazy activation
	 *   policy}, then the bundle may remain in this state for some time until the
	 *   activation is triggered.
	 */
    val consistent = Seq(Bundle.INSTALLED, Bundle.STARTING, Bundle.RESOLVED, Bundle.ACTIVE)
    val context = framework.getSystemBundleContext()
    val ts = System.currentTimeMillis() + timeout
    def isConsistent = {
      val lastEventTS = System.currentTimeMillis() - lastBundleEvent.get
      val stateIsConsistent = context.getBundles().forall(b => b.getBundleId() == 0 || consistent.contains(b.getState()))
      val stateIsPersistent = lastEventTS > frame // All bundles are stable and there is $frame ms without new BundleEvent
      /*    Too much noise.
      if (!stateIsConsistent)
        context.getBundles().map(b => (b, b.getState())).filterNot(b => b._1.getBundleId() == 0 || consistent.contains(b._2)).foreach {
          case (bundle, state) => log.trace(s"There is $bundle in inconsistent state $state.")
        }
      if (stateIsPersistent)
        log.trace(s"Last BundleEvent was ${lastEventTS}ms ago")*/
      stateIsConsistent && stateIsPersistent
    }
    // Count from NOW
    lastBundleEvent.set(System.currentTimeMillis())
    while ((isConsistent match {
      case true => return true
      case false => true
    }) && (ts - System.currentTimeMillis > 0)) {
      val timeout = math.min(ts - System.currentTimeMillis, frame)
      lastBundleEvent.synchronized { lastBundleEvent.wait(timeout) }
      if (lastBundleEvent.get < frame)
        Thread.sleep(frame) // Something happen, wait 100ms
    }
    isConsistent
  }

  /**
   * Receives notification that a bundle has had a lifecycle change.
   *
   * @param event The {@code BundleEvent}.
   */
  protected def bundleChanged(event: BundleEvent) = event.getType() match {
    case BundleEvent.INSTALLED =>
      log.debug("bundle %s is installed from context %s".
        format(event.getBundle().getSymbolicName(), event.getOrigin().getSymbolicName()))
      lastBundleEvent.synchronized {
        lastBundleEvent.set(System.currentTimeMillis())
        lastBundleEvent.notifyAll()
      }
    case BundleEvent.STARTED =>
      log.debug("bundle %s is started".format(event.getBundle().getSymbolicName()))
      lastBundleEvent.synchronized {
        lastBundleEvent.set(System.currentTimeMillis())
        lastBundleEvent.notifyAll()
      }
    case BundleEvent.STOPPED =>
      log.debug("bundle %s is stopped".format(event.getBundle().getSymbolicName()))
      lastBundleEvent.synchronized {
        lastBundleEvent.set(System.currentTimeMillis())
        lastBundleEvent.notifyAll()
      }
    case BundleEvent.UPDATED =>
      log.debug("bundle %s is updated".format(event.getBundle().getSymbolicName()))
      lastBundleEvent.synchronized {
        lastBundleEvent.set(System.currentTimeMillis())
        lastBundleEvent.notifyAll()
      }
    case BundleEvent.UNINSTALLED =>
      log.debug("bundle %s is uninstalled".format(event.getBundle().getSymbolicName()))
      lastBundleEvent.synchronized {
        lastBundleEvent.set(System.currentTimeMillis())
        lastBundleEvent.notifyAll()
      }
    case BundleEvent.RESOLVED =>
      log.debug("bundle %s is resolved".format(event.getBundle().getSymbolicName()))
      lastBundleEvent.synchronized {
        lastBundleEvent.set(System.currentTimeMillis())
        lastBundleEvent.notifyAll()
      }
    case BundleEvent.UNRESOLVED =>
      log.warn("bundle %s is unresolved".format(event.getBundle().getSymbolicName()))
      lastBundleEvent.synchronized {
        lastBundleEvent.set(System.currentTimeMillis())
        lastBundleEvent.notifyAll()
      }
    case BundleEvent.STARTING =>
      log.debug("bundle %s is starting".format(event.getBundle().getSymbolicName()))
      lastBundleEvent.synchronized {
        lastBundleEvent.set(System.currentTimeMillis())
        lastBundleEvent.notifyAll()
      }
    case BundleEvent.STOPPING =>
      log.debug("bundle %s is stopping".format(event.getBundle().getSymbolicName()))
      lastBundleEvent.synchronized {
        lastBundleEvent.set(System.currentTimeMillis())
        lastBundleEvent.notifyAll()
      }
    case BundleEvent.LAZY_ACTIVATION =>
      log.debug("bundle %s lazy activation".format(event.getBundle().getSymbolicName()))
      lastBundleEvent.synchronized {
        lastBundleEvent.set(System.currentTimeMillis())
        lastBundleEvent.notifyAll()
      }
  }
  /**  Creates and returns OSGi framework   */
  @log
  protected def create(): osgi.Framework = {
    // the osgi.adaptor (org.eclipse.osgi.baseadaptor.BaseAdaptor by default)
    val adaptorClassName = FrameworkProperties.getProperty(EclipseStarter.PROP_ADAPTOR, EclipseStarter.DEFAULT_ADAPTOR_CLASS)
    val adaptorClass = Class.forName(adaptorClassName)
    val constructor = adaptorClass.getConstructor(classOf[Array[String]])
    val adapter = constructor.newInstance(Array[String]()).asInstanceOf[FrameworkAdaptor]
    new osgi.Framework(adapter)
  }
  // WTF? So shity code from Eclipse. Disappointed.
  protected def waitForShutdown(framework: osgi.Framework) {
    if (!osgi.Framework.isForcedRestart())
      return
    // wait for the system bundle to stop
    val systemBundle = framework.getBundle(0)
    var i = 0
    while (i < 5000 && (systemBundle.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
      i += 200
      try {
        Thread.sleep(200)
      } catch {
        case e: InterruptedException =>
          i == 5000
      }
    }
  }
  object Properties {
    def initialize() {
      FrameworkProperties.initializeProperties()
      LocationManager.initializeLocations()
      loadConfigurationInfo()
      finalizeInitialization()
      if (Profile.PROFILE)
        Profile.initProps() // catch any Profile properties set in eclipse.properties...
    }
    /**
     * Sets the initial properties for the platform.
     * This method must be called before calling the {@link  #run(String[], Runnable)} or
     * {@link #startup(String[], Runnable)} methods for the properties to be used in
     * a launched instance of the platform.
     * <p>
     * If the specified properties contains a null value then the key for that value
     * will be cleared from the properties of the platform.
     * </p>
     * @param initialProperties the initial properties to set for the platform.
     */
    def setInitial(initialProperties: Map[String, String]) {
      if (initialProperties.isEmpty) {
        log.warn("Initial properties is empty.")
      } else {
        for ((key, value) <- initialProperties) if (value != null)
          FrameworkProperties.setProperty(key, value)
      }
    }
    protected def finalizeInitialization() {
      // if check config is unknown and we are in dev mode,
      if (FrameworkProperties.getProperty(EclipseStarter.PROP_DEV) != null && FrameworkProperties.getProperty(EclipseStarter.PROP_CHECK_CONFIG) == null)
        FrameworkProperties.setProperty(EclipseStarter.PROP_CHECK_CONFIG, "true") //$NON-NLS-1$
    }
    protected def load(location: URL): Properties = {
      val result = new Properties()
      if (location == null)
        return result
      try {
        val in = location.openStream()
        try {
          result.load(in)
        } finally {
          in.close()
        }
      } catch {
        case e: IOException =>
        // its ok if there is no file.  We'll just use the defaults for everything
        // TODO but it might be nice to log something with gentle wording (i.e., it is not an error)
      }
      substituteVars(result)
    }
    protected def loadConfigurationInfo() {
      Option(LocationManager.getConfigurationLocation()) foreach { configArea =>
        var location: URL = null
        try {
          location = new URL(configArea.getURL().toExternalForm() + LocationManager.CONFIG_FILE)
        } catch {
          case e: MalformedURLException =>
          // its ok.  This should never happen
        }
        merge(FrameworkProperties.getProperties(), load(location))
      }
    }
    protected def merge(destination: Properties, source: Properties) =
      for {
        entry <- source.entrySet().iterator()
        key <- Option(entry.getKey()).map(_.asInstanceOf[String])
        value <- Option(entry.getValue()).map(_.asInstanceOf[String])
      } if (destination.getProperty(key) == null) destination.setProperty(key, value)
    protected def substituteVars(result: Properties): Properties =
      if (result == null) {
        //nothing todo.
        null
      } else {
        for (key <- result.keys()) key match {
          case key: String =>
            val value = result.getProperty(key)
            if (value != null)
              result.put(key, BaseStorageHook.substituteVars(value))
          case other =>
        }
        result
      }
  }
}

object FrameworkLauncher {
  /** DI service that pass actual value to Digi-Lib activator */
  class DependencyInjectionService(di: BindingModule) extends DependencyInjection {
    /** Returns actual DI. From the user diScript, builder with special class loader. */
    def getDependencyInjection() = di
  }
  class OSGiLogBridge extends LogListener with ServiceTrackerCustomizer[LogReaderService, LogReaderService] with Loggable {
    @volatile protected var context: Option[BundleContext] = None
    @volatile protected var logReaderTracker: Option[ServiceTracker[LogReaderService, LogReaderService]] = None

    def register(context: BundleContext) {
      log.debug("Register OSGi LogService bridge.")
      this.context = Some(context)
      val logReaderTracker = new ServiceTracker[LogReaderService, LogReaderService](context, classOf[LogReaderService].getName(), this)
      logReaderTracker.open()
      this.logReaderTracker = Some(logReaderTracker)
    }
    def unregister() {
      log.debug("Unregister OSGi LogService bridge.")
      this.logReaderTracker.foreach(_.close())
      this.logReaderTracker = None
      this.context = None
    }
    /** Transfer logEntry to log. */
    def logged(logEntry: LogEntry) {
      val bundle = logEntry.getBundle()
      val symbolicName = bundle.getSymbolicName().replaceAll("-", "_")
      val log = LoggerFactory.getLogger("@." + symbolicName)
      val message = Option(logEntry.getServiceReference()) match {
        case Some(serviceReference) => logEntry.getMessage() + " fromRef:" + serviceReference.toString()
        case None => logEntry.getMessage()
      }
      logEntry.getLevel() match {
        case LogService.LOG_DEBUG =>
          Option(logEntry.getException()) match {
            case Some(exception) => log.debug(message, exception)
            case None => log.debug(message)
          }
        case LogService.LOG_INFO =>
          Option(logEntry.getException()) match {
            case Some(exception) => log.info(message, exception)
            case None => log.info(message)
          }
        case LogService.LOG_WARNING =>
          Option(logEntry.getException()) match {
            case Some(exception) => log.warn(message, exception)
            case None => log.warn(message)
          }
        case LogService.LOG_ERROR =>
          Option(logEntry.getException()) match {
            case Some(exception) => log.error(message, exception)
            case None => log.error(message)
          }
      }
    }
    /** Subscribe to new LogReaderService */
    def addingService(serviceReference: ServiceReference[LogReaderService]): LogReaderService = {
      context.map { context =>
        val logReaderService = context.getService(serviceReference)
        log.debug("Subscribe log listener to " + logReaderService.getClass.getName)
        logReaderService.addLogListener(this)
        logReaderService
      } getOrElse null
    }
    def modifiedService(serviceReference: ServiceReference[LogReaderService], logReaderService: LogReaderService) {}
    /** Unsubscribe from disposed LogReaderService */
    def removedService(serviceReference: ServiceReference[LogReaderService], logReaderService: LogReaderService) {
      log.debug("Unsubscribe log listener from " + logReaderService.getClass.getName)
      logReaderService.removeLogListener(this)
    }
  }
}

