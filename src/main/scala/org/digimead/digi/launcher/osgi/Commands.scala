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

package org.digimead.digi.launcher.osgi

import scala.collection.mutable
import org.digimead.digi.lib.log.api.Loggable
import org.eclipse.osgi.framework.console.CommandInterpreter
import org.eclipse.osgi.framework.console.CommandProvider
import org.osgi.framework.BundleContext
import org.osgi.framework.ServiceReference
import org.osgi.framework.ServiceRegistration
import org.osgi.framework.wiring.BundleWiring
import org.osgi.service.application.ApplicationDescriptor
import org.osgi.service.application.ApplicationHandle
import org.osgi.service.application.ScheduledApplication
import org.osgi.util.tracker.ServiceTracker
import org.digimead.digi.launcher.ApplicationLauncher

class Commands(context: BundleContext) extends CommandProvider with Loggable {
  protected val LAUNCHABLE_APP_FILTER = "(&(application.locked=false)(application.launchable=true)(application.visible=true))"
  protected val ACTIVE_APP_FILTER = "(!(application.state=STOPPING))"
  protected val LOCKED_APP_FILTER = "(application.locked=true)"
  protected val NEW_LINE = "\r\n"
  protected val TAB = "\t"

  // holds the mappings from command name to command arguments and command description
  protected val commandsHelp = mutable.LinkedHashMap(
    "lndevoff" -> Array[String]("disable development mode"),
    "lndevon" -> Array[String]("enable development mode"),
    "lnenable" -> Array[String]("start Digi application via api.Main service"),
    "lndisable" -> Array[String]("stop Digi application via api.Main service"),
    "lnstopeapp" -> Array[String]("<application id>", "terminate Eclipse application from SWT thread"))

  protected val applicationDescriptors = new ServiceTracker(context, classOf[ApplicationDescriptor], null)
  protected val applicationHandles = new ServiceTracker(context, classOf[ApplicationHandle], null)
  protected val scheduledApplications = new ServiceTracker(context, classOf[ScheduledApplication], null)
  protected val launchableApp = context.createFilter(LAUNCHABLE_APP_FILTER)
  protected val activeApp = context.createFilter(ACTIVE_APP_FILTER)
  protected val lockedApp = context.createFilter(LOCKED_APP_FILTER)
  @volatile protected var providerRegistration: Option[ServiceRegistration[CommandProvider]] = None

  def _lndevoff(intp: CommandInterpreter) =
    if (ApplicationLauncher.developmentOff)
      intp.println("Disable development mode.")
    else
      intp.println("Development mode is already disabled.")
  def _lndevon(intp: CommandInterpreter) =
    if (ApplicationLauncher.developmentOn)
      intp.println("Enable development mode.")
    else
      intp.println("Development mode is already enabled.")
  /** Start Digi application */
  def _lnenable(intp: CommandInterpreter) = if (ApplicationLauncher.digiStart()) {
    intp.println("Digi application is enabled.")
    log.error("Digi application is enabled.")
  } else {
    intp.println("Digi application is already enabled.")
    log.error("Digi application is already enabled.")
  }
  /** Stop Digi application */
  def _lndisable(intp: CommandInterpreter) = if (ApplicationLauncher.digiStop(context)) {
    intp.println("Digi application is disabled.")
    log.error("Digi application is disabled.")
  } else {
    intp.println("Digi application is already disabled.")
    log.error("Digi application is already disabled.")
  }
  /**
   * Stop Eclipse application.
   * But there is no working dispose logic in Eclipse 4 at all.
   * After stop you will have a garbage that only usage is waste of space before JVM shutdown.
   * There is no restart possibility. Read E4Aplication source code and compare initialization/deinitialization methods.
   */
  def _lnstopeapp(intp: CommandInterpreter) {
    val appId = intp.nextArgument()
    intp.println("Stop requested for application instance: " + appId)
    log.info("Stop required for application instance: " + appId)
    // first search for the application instance id
    getApplication(applicationHandles.getServiceReferences(), appId, ApplicationHandle.APPLICATION_PID, false) orElse
      getApplication(applicationHandles.getServiceReferences(), appId, ApplicationHandle.APPLICATION_DESCRIPTOR, false) match {
        case Some(application) =>
          if (activeApp.`match`(getServiceProps(application))) {
            try {
              val appHandle = context.getService(application).asInstanceOf[ApplicationHandle]
              // Try to shutdown via SWT/Launcher main thread
              context.getBundles().find(bundle => bundle.getSymbolicName() == "org.eclipse.swt") match {
                case Some(bundle) =>
                  val loader = bundle.adapt(classOf[BundleWiring]).getClassLoader()
                  val displayClazz = loader.loadClass("org.eclipse.swt.widgets.Display")
                  val findDisplayMethod = displayClazz.getDeclaredMethod("findDisplay", classOf[Thread])
                  ApplicationLauncher.getMainThread.flatMap(thread => Option(findDisplayMethod.invoke(null, thread))) match {
                    case Some(display) =>
                      val asyncMethod = displayClazz.getDeclaredMethod("asyncExec", classOf[Runnable])
                      asyncMethod.invoke(display, new Runnable {
                        def run = appHandle.destroy()
                      })
                      intp.println("Application instance is stopped.")
                      log.info("Application instance is stopped.")
                    case None =>
                      intp.println("Unable to find SWT Display for thread " + ApplicationLauncher.getMainThread)
                      log.error("Unable to find SWT Display for thread " + ApplicationLauncher.getMainThread)
                  }
                case None =>
                  intp.println("Unable to find 'org.eclipse.swt' bundle")
                  log.error("Unable to find 'org.eclipse.swt' bundle")
              }
            } catch {
              case e: Throwable =>
                intp.println("Unable to restart application instance: " + e.getMessage)
                log.error("Unable to restart application instance: " + e.getMessage, e)
            } finally {
              context.ungetService(application)
            }
          } else {
            intp.println("Application instance is already stopping: " + application.getProperty(ApplicationHandle.APPLICATION_PID))
            log.error("Application instance is already stopping: " + application.getProperty(ApplicationHandle.APPLICATION_PID))
          }
          return ;
        case None =>
          intp.println("\"" + appId + "\" does not exist, is not running or is ambigous.")
      }
  }
  /** Get help for command provider. */
  def getHelp(): String = getHelp(None)
  /** Starts commands provider. */
  def start() {
    if (providerRegistration.nonEmpty)
      throw new IllegalStateException(getClass.getName + " already started")
    applicationDescriptors.open()
    applicationHandles.open()
    scheduledApplications.open()
    providerRegistration = Option(context.registerService(classOf[CommandProvider], this, null))
  }
  /** Stop commands provider. */
  def stop() {
    if (providerRegistration == Some(null))
      throw new IllegalStateException(getClass.getName + " already stoped")
    providerRegistration.foreach { registration =>
      registration.unregister()
      applicationDescriptors.close()
      applicationHandles.close()
      scheduledApplications.close()
      // Set an incorrect value that indicates where it already stopped
      providerRegistration = Some(null)
    }
  }

  /** Private helper method for getHelp. Formats the command descriptions. */
  protected def addCommand(command: String, description: String) =
    TAB + command + " - " + description + NEW_LINE
  /** Private helper method for getHelp. Formats the command descriptions with command arguments. */
  protected def addCommand(command: String, parameters: String, description: String) =
    TAB + command + " " + parameters + " - " + description + NEW_LINE
  /** Helper method for getHelp. Formats the help headers. */
  protected def addHeader(header: String) =
    "---" + header + "---" + NEW_LINE
  /** Get ServiceReference for targetId/idKey. */
  protected def getApplication(apps: Array[ServiceReference[ApplicationHandle]], targetId: String, idKey: String,
    perfectMatch: Boolean): Option[ServiceReference[ApplicationHandle]] = {
    if (apps == null || targetId == null) {
      None
    } else {
      var result: ServiceReference[ApplicationHandle] = null
      var ambigous = false;
      for (i <- 0 until apps.length) {
        val id = apps(i).getProperty(idKey).asInstanceOf[String]
        if (targetId.equals(id))
          return Some(apps(i)) // always return a perfect match
        if (!perfectMatch) {
          if (id.indexOf(targetId) >= 0) {
            if (result != null)
              ambigous = true
            result = apps(i)
          }
        }
      }
      if (ambigous) None else Some(result)

    }
  }
  /**
   * This method either returns the help message for a particular command,
   * or returns the help messages for all commands (if commandName is null)
   */
  protected def getHelp(commandName: Option[String] = None): String = {
    commandName match {
      case Some(name) =>
        commandsHelp.get(name) match {
          case Some(Array(description)) =>
            addCommand(name, description)
          case Some(Array(argument, description)) =>
            addCommand(name, argument, description)
          case None =>
            ""
        }
      case None =>
        addHeader("Launcher Admin Commands") + {
          for { (name, value) <- commandsHelp }
            yield (name, value) match {
            case (name, Array(description)) =>
              addCommand(name, description)
            case (name, Array(argument, description)) =>
              addCommand(name, argument, description)
          }
        }.mkString
    }
  }
  /** Get service properties dictionary. */
  protected def getServiceProps(ref: ServiceReference[ApplicationHandle]): java.util.Dictionary[String, AnyRef] = {
    val keys = ref.getPropertyKeys()
    val props = new java.util.Hashtable[String, AnyRef](keys.length)
    for (key <- keys)
      props.put(key, ref.getProperty(key))
    props
  }
}
