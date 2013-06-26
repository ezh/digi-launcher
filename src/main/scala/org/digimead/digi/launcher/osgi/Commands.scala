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

import org.digimead.digi.launcher.ApplicationLauncher
import org.digimead.digi.lib.aop.log
import org.digimead.digi.lib.log.api.Loggable
import org.eclipse.osgi.framework.console.CommandInterpreter
import org.eclipse.osgi.framework.console.CommandProvider
import org.osgi.framework.BundleContext
import org.osgi.framework.ServiceReference
import org.osgi.framework.ServiceRegistration
import org.osgi.framework.wiring.BundleWiring
import org.osgi.util.tracker.ServiceTracker

// ATTENTION. Using only core OSGi classes. Compendium is unavailable.
/**
 * Console commands that control launcher life cycle.
 */
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
    "lndisable" -> Array[String]("stop Digi application via api.Main service"))

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
  /** Get help for command provider. */
  def getHelp(): String = getHelp(None)
  /** Starts commands provider. */
  def start() {
    if (providerRegistration.nonEmpty)
      throw new IllegalStateException(getClass.getName + " already started")
    providerRegistration = Option(context.registerService(classOf[CommandProvider], this, null))
  }
  /** Stop commands provider. */
  def stop() {
    if (providerRegistration == Some(null))
      throw new IllegalStateException(getClass.getName + " already stoped")
    providerRegistration.foreach { registration =>
      registration.unregister()
      // Set an incorrect value that indicates whether it is stopped.
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
}
