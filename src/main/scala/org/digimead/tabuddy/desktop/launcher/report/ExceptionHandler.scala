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

package org.digimead.tabuddy.desktop.launcher.report

import java.lang.Thread.UncaughtExceptionHandler

import org.digimead.digi.lib.log.api.Loggable

class ExceptionHandler extends Loggable {
  ExceptionHandler // initiate lazy initialization

  def register() {
    // don't register again if already registered
    val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
    if (currentHandler.isInstanceOf[ExceptionHandler.Default])
      return
    if (currentHandler != null) {
      log.debug("Append default uncaught exceptions handler for application.")
      log.debug("Previous default uncaught exceptions handler was " + currentHandler.getClass.getName())
    } else
      log.debug("Install new default uncaught exceptions handler for application.")
    // register default exceptions handler
    Thread.setDefaultUncaughtExceptionHandler(new ExceptionHandler.Default(Option(currentHandler)))
  }
}

object ExceptionHandler extends Loggable {
  @annotation.tailrec
  def retry[T](n: Int, timeout: Int = -1)(fn: => T): T = {
    val r = try { Some(fn) } catch { case e: Exception if n > 1 => None }
    r match {
      case Some(x) => x
      case None =>
        if (timeout >= 0) Thread.sleep(timeout)
        log.warn("retry #" + (n - (n - 1)))
        retry(n - 1, timeout)(fn)
    }
  }

  class Default(val defaultExceptionHandler: Option[UncaughtExceptionHandler]) extends UncaughtExceptionHandler with Loggable {
    // Default exception handler
    def uncaughtException(t: Thread, e: Throwable) {
      log.error("Unhandled exception in %s: %s".format(t, e), e)
      // call original handler, handler blown up if java.lang.Throwable.getStackTrace return null :-)
      try {
        defaultExceptionHandler.foreach(_.uncaughtException(t, e))
      } catch {
        // catch all exceptions
        case e: Throwable =>
      }
    }
  }
}
