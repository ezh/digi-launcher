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

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

import scala.Array.canBuildFrom
import scala.Option.option2Iterable
import scala.collection.JavaConversions._
import scala.collection.immutable
import scala.collection.mutable

import org.digimead.digi.launcher.report.Report
import org.digimead.digi.lib.aop.log
import org.digimead.digi.lib.log.api.Loggable
import org.eclipse.core.runtime.adaptor.EclipseStarter
import org.eclipse.core.runtime.adaptor.LocationManager
import org.eclipse.core.runtime.internal.adaptor.EclipseAdaptorMsg
import org.eclipse.core.runtime.internal.adaptor.EclipseAppLauncher
import org.eclipse.osgi.framework.debug.FrameworkDebugOptions
import org.eclipse.osgi.framework.internal.core.ConsoleManager
import org.eclipse.osgi.framework.internal.core.FrameworkProperties
import org.osgi.framework.Bundle
import org.osgi.framework.BundleContext
import org.osgi.framework.BundleException
import org.osgi.framework.wiring.BundleWiring
import org.osgi.util.tracker.ServiceTracker

import com.escalatesoft.subcut.inject.BindingModule
import com.escalatesoft.subcut.inject.Injectable

import language.reflectiveCalls

/**
 * Application launcher spawned from RootClassLoader.
 */
class ApplicationLauncher(implicit val bindingModule: BindingModule)
  extends Loggable with Injectable with Runnable {
  checkBeforeInitialization

  //
  // DI parameters
  //
  /** The system architecture. */
  val arch = injectOptional[String](EclipseStarter.PROP_ARCH)
  /** Path to the directory with OSGi bundles. */
  val bundles = inject[File]("Launcher.Bundles")
  /** The configuration location. */
  val configArea = injectOptional[URL](LocationManager.PROP_CONFIG_AREA)
  /** The console and port */
  val console = injectOptional[Option[Int]](org.eclipse.core.runtime.adaptor.EclipseStarter.PROP_CONSOLE)
  /** Path to the directory with application data. */
  val data = inject[File]("Launcher.Data")
  /** A boolean flag indicating whether or not to be debugging enabled. */
  val debug = injectOptional[Boolean]("Launcher.Debug") getOrElse false
  /** Look for the debug mode and option file location. */
  val debugFile = injectOptional[Option[File]](org.eclipse.core.runtime.adaptor.EclipseStarter.PROP_DEBUG)
  /** TCP port of equinox console. */
  val debugPort = injectOptional[Int]("Launcher.Debug.Port") getOrElse console.getOrElse(None).getOrElse(12345)
  /**
   * Bundles which are specified on the osgi.bundles list can specify a particular startlevel. If they
   * do not specify a startlevel then they default to the value of osgi.bundles.defaultStartLevel
   */
  val defaultBundlesStartLevel = injectOptional[Int](EclipseStarter.PROP_BUNDLES_STARTLEVEL) getOrElse 4
  /**
   * The start level when the fwl start. This level is passed to the StartLevel service.
   */
  val defaultInitialStartLevel = injectOptional[Int](EclipseStarter.PROP_INITIAL_STARTLEVEL) getOrElse 6
  /** The DI keys validator Fn(keyClass, loaderClass) */
  val dependencyValidator = injectOptional[(Manifest[_], Option[String], Class[_]) => Boolean]("Launcher.DI.Validator")
  /** The development mode and class path entries. */
  val dev = injectOptional[Boolean](EclipseStarter.PROP_DEV)
  /** The development bundle symbolic name list. */
  val devBundles = injectOptional[Seq[String]]("Launcher.Dev.Bundles") getOrElse (Seq())
  /** Digi application entry point interface/service. */
  val digiMainService = inject[String]("Launcher.Digi.Service")
  /** Extension bundles that added to 'osgi.bundles'. 'osgi.bundles' have more priority.  */
  val extensionBundles = injectOptional[Array[String]](EclipseStarter.PROP_EXTENSIONS) getOrElse Array()
  /** A boolean flag indicating whether or not to be framework restarted if there are installed/uninstalled bundles. */
  val forcedRestart = injectOptional[Boolean]("osgi.forcedRestart") getOrElse false
  /** OSGi framework launcher class. */
  val frameworkLauncherClass = injectOptional[Class[FrameworkLauncher]] getOrElse classOf[FrameworkLauncher]
  /** The data location for this instance. */
  val instanceArea = injectOptional[URL](LocationManager.PROP_INSTANCE_AREA)
  /** The launcher location. */
  val launcher = injectOptional[String](osgi.Framework.PROP_LAUNCHER)
  /** Maximum operation (start/stop/restart) duration */
  val maximumDuration = injectOptional[Int]("Launcher.Maximum.Duration") getOrElse 10000
  /** The nationality/language. */
  val nl = injectOptional[String](org.eclipse.core.runtime.adaptor.EclipseStarter.PROP_NL)
  /** The locale extensions. */
  val nlExtensions = injectOptional[String](osgi.Framework.PROP_NL_EXTENSIONS)
  /** The operating system. */
  val os = injectOptional[String](org.eclipse.core.runtime.adaptor.EclipseStarter.PROP_OS)
  /** Shutdown framework after application completed. */
  val shutdownFrameworkOnExit = injectOptional[Boolean]("Launcher.ShutdownFrameworkOnExit") getOrElse true
  /** The user location for this instance. */
  val userArea = injectOptional[URL](LocationManager.PROP_USER_AREA)
  /** The window system. */
  val ws = injectOptional[String](org.eclipse.core.runtime.adaptor.EclipseStarter.PROP_WS)

  //
  // Implementation variables
  //
  /** Platform debug log redirector */
  lazy val debugLogRedirector = new ApplicationLauncher.DebugLogRedirector()
  /** Platform debug log redirector thread */
  lazy val debugLogRedirectorThread = new Thread(debugLogRedirector, "Platform debug log redirector")
  /** Framework launcher instance loaded with current class loader */
  val frameworkLauncher: FrameworkLauncher = getClass.getClassLoader().
    loadClass(frameworkLauncherClass.getName).newInstance().asInstanceOf[FrameworkLauncher]
  /** Location of osgi.configuration.area, ../configuration */
  val locationConfigurationArea = new File(data, "configuration")
  /** Location of config.ini in osgi.configuration.area */
  val locationConfigurationAreaConfig = new File(locationConfigurationArea, LocationManager.CONFIG_FILE)
  /** Location of .options file with debug options */
  val locationDebugOptions = new File(locationConfigurationArea, ".options")
  /** User shutdown hook. */
  @volatile protected var userShutdownHook: Option[Runnable] = None
  /** Location of script with DI settings. */
  @volatile protected var applicationDIScript: Option[File] = None

  checkAfterInitialization
  // IMPORTANT.
  //   Initialize all launcher singletons before main loop.
  //   DI is modified after framework is started.
  assert(Report.inner != null, "Report module is not available.") // Initialize

  /** Validate class state before initialization. */
  def checkBeforeInitialization {
    assert(getClass.getClassLoader.getClass.getName() == classOf[RootClassLoader].getName,
      "Framework loader may be instantiated only from Framework class loader")
  }
  /** Validate class state after initialization. */
  def checkAfterInitialization {
    assert(bundles.isDirectory() && bundles.canRead() && bundles.isAbsolute(), s"Bundles directory '${bundles}' is inaccessable or relative.")
    assert(data.isDirectory() && data.canRead() && data.isAbsolute(), s"Data directory '${data}' is inaccessable or relative.")
  }
  /** Prepare OSGi framework settings. */
  @log
  def initialize(applicationDIScript: Option[File]) = if (ApplicationLauncher.initialized.compareAndSet(false, true)) {
    if (ApplicationLauncher.running.get)
      throw new IllegalStateException(EclipseAdaptorMsg.ECLIPSE_STARTUP_ALREADY_RUNNING)
    log.info("Initialize application launcher.")
    this.applicationDIScript = applicationDIScript
    val diProperties = initializeDIProperties
    val baseProperties = immutable.HashMap[String, String](
      "osgi.checkConfiguration" -> "true", // timestamps are check in the configuration cache to ensure that the cache is up to date
      "osgi.classloader.singleThreadLoads" -> "false", //
      EclipseStarter.PROP_DEBUG -> locationDebugOptions.getAbsolutePath(), // search .options file at the provided location
      "osgi.install.area" -> data.getAbsoluteFile.toURI.toURL.toString,
      "osgi.noShutdown" -> "true", // the OSGi Framework will not be shut down after the application has ended
      // This tells framework to use our classloader as parent, so it can see classes that we see
      // org.eclipse.osgi.baseadaptor.BaseAdaptor
      "osgi.parentClassloader" -> "fwk",
      // IMPORTANT. Allow to pass first loading request to parent (FWK) class loader.
      // Without this the logic of BundleLoader would be "self-first", not "parent-first".
      // Look for more at http://frankkieviet.blogspot.ru/2009/03/javalanglinkageerror-loader-constraint.html
      // So we would get java.lang.LinkageError at least on scala.* classes.
      "org.osgi.framework.bootdelegation" -> "*", // Should we fix it with new implementation of BundleLoader?
      "osgi.requiredJavaVersion" -> "1.6", // the minimum java version that is required to launch application
      "osgi.resolver.usesMode" -> "aggressive", // aggressively seeks a solution with no class space inconsistencies
      // The osgi.syspath is set by ApplicationLauncher/EclipseStarter to be the parent directory of the
      // framework (osgi.framework). It is a path string, not a URL. It is currently used
      // by the Eclipse adaptor, to find initial bundles to install (when osgi.bundles
      // contains symbolic names provided instead of URLs).
      "osgi.syspath" -> bundles.getAbsolutePath) ++ diProperties
    if (debug) {
      frameworkLauncher.Properties.setInitial(baseProperties ++
        immutable.HashMap(
          EclipseStarter.PROP_CONSOLE -> debugPort.toString,
          "osgi.console.enable.builtin" -> true.toString))
    } else {
      frameworkLauncher.Properties.setInitial(baseProperties)
    }
    FrameworkProperties.initializeProperties()
    LocationManager.initializeLocations()
    FrameworkDebugOptions.getDefault()
    /*
     * IMPORTANT. We must call initializeEquinoxClasses AFTER configuration.
     * Class forName invoke initialization.
     */
    initializeEquinoxClasses()
  } else {
    throw new IllegalStateException("Platform is already initialized.")
  }
  /** ApplicationLauncher main loop method. */
  @log
  def run(waitForTermination: Boolean, shutdownHandler: Option[Runnable]) {
    userShutdownHook = shutdownHandler
    // This is also SWT main thread
    val thread = new Thread(this, "Digi Launcher main thread")
    thread.start()
    ApplicationLauncher.applicationThread = Some(thread)
    if (waitForTermination)
      thread.join()
  }

  /** Convert DI settings to Map for FrameworkProperties */
  @log
  protected def initializeDIProperties(): immutable.Map[String, String] = {
    var properties = mutable.HashMap[String, String]()
    console.foreach(arg => properties(EclipseStarter.PROP_CONSOLE) = arg.toString)
    configArea.foreach(arg => properties(LocationManager.PROP_CONFIG_AREA) = arg.toString)
    instanceArea.foreach(arg => properties(LocationManager.PROP_INSTANCE_AREA) = arg.toString)
    userArea.foreach(arg => properties(LocationManager.PROP_USER_AREA) = arg.toString)
    launcher.foreach(arg => properties(osgi.Framework.PROP_LAUNCHER) = arg.toString)
    dev.foreach(arg => properties(EclipseStarter.PROP_DEV) = arg.toString)
    debugFile.foreach(arg => properties(EclipseStarter.PROP_DEBUG) = arg.toString)
    ws.foreach(arg => properties(EclipseStarter.PROP_WS) = arg.toString)
    os.foreach(arg => properties(EclipseStarter.PROP_OS) = arg.toString)
    arch.foreach(arg => properties(EclipseStarter.PROP_ARCH) = arg.toString)
    nl.foreach(arg => properties(EclipseStarter.PROP_NL) = arg.toString)
    nlExtensions.foreach(arg => properties(osgi.Framework.PROP_NL_EXTENSIONS) = arg.toString)
    properties(EclipseStarter.PROP_BUNDLES_STARTLEVEL) = defaultBundlesStartLevel.toString
    properties(EclipseStarter.PROP_EXTENSIONS) = extensionBundles.mkString(",")
    properties(EclipseStarter.PROP_INITIAL_STARTLEVEL) = defaultInitialStartLevel.toString
    properties(osgi.Framework.PROP_FORCED_RESTART) = forcedRestart.toString
    properties.toMap
  }
  /** Load stuff from Equinox with hacks of OSGi etalon realization. */
  protected def initializeEquinoxClasses() {
    log.info("Preload OSGi classes with Equinox hacks.")
    val skipPreload = Seq("org.osgi.service.log.package-info")
    Option(classOf[LocationManager].getProtectionDomain().getCodeSource()) match {
      case Some(equinoxSrc) =>
        val jar = equinoxSrc.getLocation();
        val zip = new ZipInputStream(jar.openStream())
        val iter = Iterator.continually { zip.getNextEntry() }
        iter.takeWhile(_ != null).foreach {
          case entry if entry.getName().endsWith(".class") =>
            val className = entry.getName().substring(0, entry.getName().length() - 6).replaceAll("""/""", ".")
            if (!skipPreload.contains(className))
              Class.forName(className)
          case entry =>
        }
      case None =>
        log.fatal("Unable to find Equinox framework contents.")
        return
    }
  }
  /** Run OSGi framework and application. */
  @log
  protected def run() {
    log.info("Start application.")
    if (!ApplicationLauncher.initialized.get)
      throw new IllegalStateException("Platform is not initialized")
    if (!ApplicationLauncher.running.compareAndSet(false, true))
      throw new IllegalStateException(EclipseAdaptorMsg.ECLIPSE_STARTUP_ALREADY_RUNNING)
    val running = new AtomicBoolean(true)
    val shutdownHandler = new Runnable {
      def run = running.synchronized {
        running.set(false)
        running.notifyAll()
      }
    }
    prepare()

    // now we replace EclipseStarter.run(equinoxArgs, new ApplicationLauncher.SplashHandler)
    // with more reliable implementation

    // 0 iteration - run if everything OK or restart or exit
    // 1 iteration - fix inconsistency of 0 iteration if forcedRestart
    // 2 iteration - confirm inconsistency of 1 iteration if forcedRestart
    for (retry <- 0 until 3 if running.get) {
      log.info(s"Enter application startup sequence main loop. Retry N: ${retry}.")
      val (framework, shutdownListeners) = frameworkLauncher.launch(Seq(shutdownHandler))
      // If everything OK, we would have here:
      //   1. framework with STARTING system bundle,
      //   2. console if needed (optional, change code yourself with copy'n'paste)
      //   3. captured debug. Fucking framework designers :-/ This is ugly hack. Shame!
      //   4. preloaded/cached bundles in RESOLVED state.
      //     Run sequence is restarted if SupportLoader.refreshPackages detect installed/uninstalled bundles and there is isForcedRestart
      //   5. new URL protocol handler: reference. IMPORTANT
      frameworkLauncher.check(framework)
      val restart = frameworkLauncher.loadBundles(defaultBundlesStartLevel, framework) match {
        case ((true, lazyActivationBundles, toStartBundles)) =>
          // System bundle is STARTING, all other bundles are RESOLVED and framework is consistent
          // Initialize DI for our OSGi infrastructure.
          applicationDIScript.foreach(frameworkLauncher.initializeDI(_, dependencyValidator, framework))
          // Start bundles after DI initialization.
          frameworkLauncher.startBundles(defaultInitialStartLevel, lazyActivationBundles, toStartBundles, framework)
          false
        case ((false, lazyActivationBundles, toStartBundles)) =>
          if (retry < 2) {
            // cannot continue; loadBundles caused refreshPackages to shutdown the framework
            log.info("Restart initiated: loadBundles caused refreshPackages to shutdown the framework.")
            if (!frameworkLauncher.waitForConsitentState(maximumDuration, framework))
              log.errorWhere(s"Unable to stay in inconsistent state more than ${maximumDuration / 1000}s. Shutting down anyway.")
            // we are restarting, not shutting down, remove listeners before restart
            frameworkLauncher.finish(shutdownListeners, None, framework)
            framework.close()
            framework.waitForStop(maximumDuration)
            log.info("OSGi framework is stopped. Restart.")
            true
          } else {
            log.info("Unable to get consistent OSGi environment. Start application with available one.")
            frameworkLauncher.logUnresolvedBundles(framework)
            // System bundle is STARTING, all other bundles are RESOLVED or INSTALLED
            // Initialize DI for our OSGi infrastructure.
            applicationDIScript.foreach(frameworkLauncher.initializeDI(_, dependencyValidator, framework))
            // Start bundles after DI initialization
            frameworkLauncher.startBundles(defaultInitialStartLevel, lazyActivationBundles, toStartBundles, framework)
            false
          }
      }
      if (!restart) {
        val commandProvider = new osgi.Commands(framework.getSystemBundleContext())
        commandProvider.start()
        // start console
        val consoleMgr = if (console.nonEmpty || debug)
          Some(ConsoleManager.startConsole(framework))
        else
          None
        consoleMgr.foreach { consoleMgr =>
          // In the case where the built-in console is disabled we should try to start the console bundle.
          try { consoleMgr.checkForConsoleBundle() } catch {
            case e: BundleException => log.error(e.getMessage(), e)
          }
        }
        //
        // Run
        //
        ApplicationLauncher.applicationFramework = Some(framework)
        log.info("Framework is prepared. Initiate platform application.")
        // wait for consistency
        if (!frameworkLauncher.waitForConsitentState(maximumDuration, framework))
          log.errorWhere(s"Unable to stay in inconsistent state more than ${maximumDuration / 1000}s. Running anyway.")
        try { runDigiApp(framework) } catch {
          case e: Throwable => log.error(e.getMessage, e)
        }
        ApplicationLauncher.applicationFramework = None
        //
        // Shutdown
        //
        if (shutdownFrameworkOnExit) {
          log.info("Application is completed. Shutdown framework.")
          commandProvider.stop()
          framework.getSystemBundleContext().getBundle().stop()
        } else
          log.info("Application is completed.")
        running.synchronized {
          while (running.get())
            running.wait()
        }
        if (shutdownFrameworkOnExit)
          frameworkLauncher.finish(shutdownListeners, consoleMgr, framework)
        log.info("Stop application")
      }
    }
    userShutdownHook.foreach(hook => new Thread(hook).start())
  }

  /** Run Digi application. */
  @log
  protected def digiRun(context: BundleContext, timeout: Int) {
    val serviceTracker = new ServiceTracker[AnyRef, AnyRef](context, digiMainService, null)
    serviceTracker.open()
    Option(serviceTracker.waitForService(timeout)) match {
      case Some(main: Runnable) =>
        // block here
        log.debug("Start Digi application: " + main)
        Report.start(context)
        try {
          main.run()
        } catch {
          case e: Throwable =>
            log.error("Application terminated: " + e.getMessage, e)
        }
        Report.stop(context)
        log.debug(s"Digi application $main is completed.")
      case Some(_) =>
        log.error(s"Unable to process incorrect service '$digiMainService'")
      case None =>
        log.error(s"Unable to get service for '$digiMainService'")
    }
    serviceTracker.close()
  }
  /** Get bundle class. */
  @log
  protected def getBundleClass(bundleSymbolicName: String, singletonClassName: String): Class[_] = {
    val framework = ApplicationLauncher.applicationFramework getOrElse
      { throw new IllegalStateException("OSGi framework is not ready.") }
    val bundle = framework.getSystemBundleContext().getBundles.find(_.getSymbolicName() == bundleSymbolicName) getOrElse
      { throw new IllegalStateException(s"OSGi bundle with symbolic name '$bundleSymbolicName' is not found.") }
    val classLoader = bundle.adapt(classOf[BundleWiring]).getClassLoader()
    classLoader.loadClass(singletonClassName)
  }
  /** Collect all bundles that are directories, save lastModified for each file in bundle. */
  protected def initializeDevelopmentMonitor(framework: osgi.Framework): immutable.HashMap[Long, immutable.HashMap[File, Long]] = {
    log.debug("Development mode. Initialize monitor.")
    val context = framework.getSystemBundleContext()
    val entries = context.getBundles().map { bundle =>
      try {
        val location = new File(bundle.getLocation().replaceFirst("^initial@reference:file:", ""))
        if (location.isDirectory()) {
          log.debug(s"Development mode. Add ${bundle.getSymbolicName()} to monitor.")
          Some(bundle.getBundleId() -> immutable.HashMap(
            recursiveListFiles(location).map(file => (file, file.lastModified())): _*))
        } else
          None
      }
    }
    immutable.HashMap(entries.flatten: _*)
  }
  /** Prepare OSGi framework. */
  @log
  protected def prepare() {
    log.info("Prepare environemnt for OSGi framework.")
    val locationConfigurationAreaConfigState = if (locationConfigurationAreaConfig.exists()) "found" else "not found"
    log.info("OSGi framework configuration:\n    %s %s".format(locationConfigurationAreaConfig, locationConfigurationAreaConfigState))
    val locationDebugOptionsState = if (locationDebugOptions.exists()) "found" else "not found"
    log.info("OSGi framework debug options:\n    %s %s".format(locationDebugOptions, locationDebugOptionsState))
    if (debug) {
      // Redirect org.eclipse.osgi.framework.debug.Debug output to application log
      val debugFile = new File(locationConfigurationArea, "debug.log")
      debugFile.delete
      if (debugFile.createNewFile())
        FrameworkDebugOptions.getDefault().setFile(debugFile)
      debugLogRedirectorThread.setDaemon(true)
      debugLogRedirectorThread.start
      debugLogRedirector.ready.await()

      // Dump startup properties
      val buffer = new StringWriter()
      val writer = new PrintWriter(buffer)
      val properties = FrameworkProperties.getProperties()
      writer.println("\n-- listing OSGi startup properties --")
      properties.stringPropertyNames().toList.sorted.foreach { name =>
        writer.println("%s=%s".format(name, properties.getProperty(name)))
      }
      writer.close()
      log.debug(buffer.toString)
    }
  }
  /** Refresh bundle if there is at least one modified file. */
  protected def processDevelopmentMonitor(monitor: immutable.HashMap[Long, immutable.HashMap[File, Long]], framework: osgi.Framework, forceReload: Boolean): Boolean = {
    val context = framework.getSystemBundleContext()
    val modified = monitor.keys.flatMap { id =>
      Option(framework.getBundle(id)) match {
        case Some(bundle) =>
          val location = new File(bundle.getLocation().replaceFirst("^initial@reference:file:", ""))
          if (location.isDirectory()) {
            val newState = immutable.HashMap(recursiveListFiles(location).map(file => (file, file.lastModified())): _*)
            val oldState = monitor(id)
            if (newState.size != oldState.size)
              Some(id)
            else {
              if (forceReload) {
                log.debug(s"Development mode. Bundle ${bundle.getSymbolicName()} with ID $id is forced for reload.")
                Some(id)
              } else {
                if (newState.forall { case (file, time) => oldState(file) == time }) {
                  log.debug(s"Development mode. Bundle ${bundle.getSymbolicName()} with ID $id is unmodified.")
                  None
                } else {
                  log.debug(s"Development mode. Bundle ${bundle.getSymbolicName()} with ID $id is changed.")
                  Some(id)
                }
              }
            }
          } else {
            log.warn(s"Development mode. Unable to find bundle $id directory: " + location)
            Some(id)
          }
        case None =>
          log.warn(s"Development mode. Bundle $id is absent.")
          Some(id)
      }
    }
    if (modified.nonEmpty) {
      log.warn(s"Development mode. Refresh bundles with IDs (${modified.mkString(", ")})")
      frameworkLauncher.refreshBundles(modified, maximumDuration, framework)
    } else
      false
  }
  /** Digi application main loop. */
  protected def runDigiApp(framework: osgi.Framework) {
    ApplicationLauncher.digiApp.set(true)
    val wait = 60000
    if (!ApplicationLauncher.running.get)
      throw new IllegalStateException(EclipseAdaptorMsg.ECLIPSE_STARTUP_NOT_RUNNING)
    val context = framework.getSystemBundleContext()
    digiRun(context, wait)
    /*
     * Development mode
     */
    // map for directory bundles: bundle id -> map(file in directory, modification time)
    var monitor: immutable.HashMap[Long, immutable.HashMap[File, Long]] = null
    var forceReload = false
    while (ApplicationLauncher.development.get()) {
      forceReload = false
      if (monitor == null) {
        // If it is the first time.
        monitor = if (devBundles.nonEmpty)
          // We are not interested in monitor - we have an explicit bundle list.
          immutable.HashMap()
        else
          // Collect bundle modification time.
          initializeDevelopmentMonitor(framework)
      } else {
        if (ApplicationLauncher.digiApp.get) {
          try {
            digiRun(context, wait)
          } catch {
            case e: ClassNotFoundException =>
              log.warn("Digi application halted(recompilation?): " + e.getMessage, e)
              Thread.sleep(5000)
              forceReload = true
            case e: Throwable =>
              log.error("Digi application halted: " + e.getMessage, e)
              Thread.sleep(1000)
          }
        } else
          ApplicationLauncher.development.synchronized { ApplicationLauncher.development.wait() }
      }
      if (devBundles.isEmpty) {
        // devBundles is empty
        // We search for modified files in bundle directories
        log.warn(s"Development mode. Refresh bundles only modified bundles.")
        if (processDevelopmentMonitor(monitor, framework, forceReload))
          monitor = initializeDevelopmentMonitor(framework)
      } else {
        // devBundles is not empty.
        // Force to reload all development bundles.
        val toReload = framework.getSystemBundleContext().getBundles().filter(b => devBundles.exists(_ == b.getSymbolicName()))
        if (toReload.size != devBundles.size) {
          val lost = devBundles.filterNot(b => toReload.exists(_.getSymbolicName() == b))
          System.err.println("Not all development bundles found. Lost: " + lost.mkString(","))
          log.error("Not all development bundles found. Lost: " + lost.mkString(","))
        }
        val modified = toReload.map(_.getBundleId())
        log.warn(s"Development mode. Refresh bundles with IDs (${modified.mkString(", ")})")
        frameworkLauncher.refreshBundles(modified, maximumDuration, framework)
        // Apply new DI
        // Initialize DI for our OSGi infrastructure.
        applicationDIScript.foreach(frameworkLauncher.initializeDI(_, dependencyValidator, framework))
      }
    }
  }
  /**
   * Runs the application for which the platform was started. The platform
   * must be running.
   * <p>
   * The given argument is passed to the application being run.  If it is <code>null</code>
   * then the command line arguments used in starting the platform, and not consumed
   * by the platform code, are passed to the application as a <code>String[]</code>.
   * </p>
   * @param argument the argument passed to the application. May be <code>null</code>
   * @return the result of running the application
   * @throws Exception if anything goes wrong
   */
  protected def runEclipseApp(framework: osgi.Framework) {
    if (!ApplicationLauncher.running.get)
      throw new IllegalStateException(EclipseAdaptorMsg.ECLIPSE_STARTUP_NOT_RUNNING)
    try {
      val launchDefault = FrameworkProperties.getProperty(osgi.Framework.PROP_APPLICATION_LAUNCHDEFAULT, "true").toBoolean
      // create the ApplicationLauncher and register it as a service
      val context = framework.getSystemBundleContext()
      val allowRelaunch = FrameworkProperties.getProperty(osgi.Framework.PROP_ALLOW_APPRELAUNCH, "false").toBoolean
      val frameworkLogger = framework.frameworkAdaptor.getFrameworkLog()
      val appLauncher = new EclipseAppLauncher(context, allowRelaunch, launchDefault, frameworkLogger)
      val appLauncherRegistration = context.registerService(classOf[org.eclipse.osgi.service.runnable.ApplicationLauncher].getName(), appLauncher, null)
      // must start the launcher AFTER service registration because this method
      // blocks and runs the application on the current thread.  This method
      // will return only after the application has stopped.
      appLauncher.start(null)
      while (ApplicationLauncher.development.get) {
        // A. org.eclipse.e4.ui.workbench.swt.util.BindingProcessingAddon
        // never use 'dispose' method
        //
        // B. even after stop/start of org.eclipse.equinox.event there is a garbage like:
        // Exception while dispatching event org.osgi.service.event.Event [topic=org/eclipse/e4/ui...
        // ...
        // at org.eclipse.osgi.framework.eventmgr.EventManager.dispatchEvent(EventManager.java:230)
        // at org.eclipse.osgi.framework.eventmgr.ListenerQueue.dispatchEventSynchronous(ListenerQueue.java:148)
        // at org.eclipse.equinox.internal.event.EventAdminImpl.dispatchEvent(EventAdminImpl.java:135)
        // at org.eclipse.equinox.internal.event.EventAdminImpl.sendEvent(EventAdminImpl.java:78)
        // at org.eclipse.equinox.internal.event.EventComponent.sendEvent(EventComponent.java:39)
        // at org.eclipse.e4.ui.services.internal.events.EventBroker.send(EventBroker.java:81)
        // at org.eclipse.e4.ui.internal.workbench.UIEventPublisher.notifyChanged(UIEventPublisher.java:58)
        // at org.eclipse.emf.common.notify.impl.BasicNotifierImpl.eNotify(BasicNotifierImpl.java:374)
        // ...
        // with invalid handlers that point to disposed objects
        //
        // C,D,E .... full pack of shit :-( Eclipse 4 is a big pack of shit. Always the same.
        appLauncher.reStart(null)
      }
      appLauncherRegistration.unregister()
    } catch {
      case e: Exception =>
        log.error("Unable to start application.", e)
        // context can be null if OSGi failed to launch (bug 151413)
        try { frameworkLauncher.logUnresolvedBundles(framework) } catch { case e: Throwable => }
        throw e
    }
  }

  // We are unable to use FileUtil here because of class loader restriction.
  /**
   * Recursively list files.
   * @param f Initial directory
   */
  private def recursiveListFiles(f: File): Array[File] = {
    val these = f.listFiles
    these ++ these.filter(_.isDirectory).flatMap(recursiveListFiles(_))
  }
}

object ApplicationLauncher extends Loggable {
  @volatile private var applicationThread: Option[Thread] = None
  @volatile private var applicationFramework: Option[osgi.Framework] = None
  @volatile private var digiMainService: Option[String] = None
  /** Flag indicating whether the application is already initialized. */
  private lazy val initialized = new AtomicBoolean(false)
  /** Flag indicating whether the application is already running. */
  private lazy val running = new AtomicBoolean(false)
  /** Flag indicating whether the application in development. */
  private lazy val development = new AtomicBoolean(false)
  /** Flag indicating whether the Digi application is active. */
  private lazy val digiApp = new AtomicBoolean(false)

  /** Get main SWT/Launcher thread */
  def getMainThread() = applicationThread
  /** Enable development mode */
  @log
  def developmentOn(): Boolean = development.synchronized {
    if (development.compareAndSet(false, true)) {
      log.info("Development mode enabled")
      development.notifyAll()
      true
    } else false
  }
  /** Disable development mode */
  @log
  def developmentOff(): Boolean = development.synchronized {
    if (development.compareAndSet(true, false)) {
      log.info("Development mode enabled")
      development.notifyAll()
      true
    } else false
  }
  /** Start Digi application */
  @log
  def digiStart(): Boolean = synchronized {
    if (digiApp.compareAndSet(false, true)) {
      development.synchronized { development.notifyAll() }
      true
    } else
      false
  }
  /** Stop Digi application. */
  @log
  def digiStop(context: BundleContext, force: Boolean = false): Boolean = synchronized {
    if (digiApp.compareAndSet(true, false) || force) {
      if (digiMainService.isEmpty)
        throw new IllegalStateException("Digi main service is unknown.")
      digiApp.set(false)
      val serviceTracker = new ServiceTracker[AnyRef, AnyRef](context, digiMainService.get, null)
      serviceTracker.open()
      Option(serviceTracker.getService()) match {
        case Some(main: Runnable) => try {
          main.asInstanceOf[{ def stop() }].stop()
        } catch {
          case e: Throwable =>
            log.error(s"Unable to process incorrect service '${digiMainService.get}': " + e.getMessage(), e)
        }
        case Some(_) =>
          log.error(s"Unable to process incorrect service '${digiMainService.get}'")
        case None =>
          log.error(s"Unable to get service for '${digiMainService.get}'")
      }
      serviceTracker.close()
      true
    } else
      false
  }

  case class InitialBundle(
    /** Original representation */
    val originalString: String,
    /** Relative path */
    val locationString: String,
    /** Full path */
    val location: URL,
    val name: String,
    /** Start level */
    val level: Int,
    /** Start flag */
    val start: Boolean)
  /** Redirects the platform debug output to the application logger */
  class DebugLogRedirector extends Runnable {
    val ready = new CountDownLatch(1)
    /** These variables are accessed only from this thread. */
    private var debugOutputStream: PipedOutputStream = null
    private var debugInputStream: PipedInputStream = null
    private var debugPrintStream: PrintStream = null

    /** Inject stream into OSGi framework. */
    def inject() {
      try { org.eclipse.osgi.framework.debug.Debug.out.flush() } catch { case e: Throwable => }
      debugOutputStream = new PipedOutputStream()
      debugInputStream = new PipedInputStream(debugOutputStream)
      debugPrintStream = new PrintStream(debugOutputStream)
      org.eclipse.osgi.framework.debug.Debug.out = debugPrintStream
      org.eclipse.osgi.framework.debug.Debug.println("Debug redirector installed")
    }
    /** Thread main loop. */
    def run() = {
      inject()
      ready.countDown()
      readDebugInformation()
    }
    /** Redirect the input stream to the launcher logger */
    protected def readDebugInformation() = try {
      val in = new BufferedReader(new InputStreamReader(debugInputStream))
      var line: String = null
      do {
        line = in.readLine()
        if (line != null)
          log.debug("<FWK> " + line)
      } while (line != null)
    } catch {
      case e: IOException =>
        if (ApplicationLauncher.applicationFramework.forall(fw => try {
          val context = fw.getSystemBundleContext()
          context.checkValid()
          val state = context.getBundle().getState()
          Seq(Bundle.STARTING, Bundle.ACTIVE).exists(_ == state)
        } catch {
          case e: IllegalStateException => false
        })) {
          log.debug("<FWK> WTF? Where is specification? Shity framework closes stream, reinject it.")
          inject()
        }
    }
  }
  /** Splash thread stub. */
  class SplashHandler extends Thread {
    override def run() {}
  }
}
