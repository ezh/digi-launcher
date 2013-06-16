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

import java.util.concurrent.atomic.AtomicBoolean

import org.digimead.digi.lib.log.api.Loggable
import org.eclipse.osgi.framework.adaptor.FrameworkAdaptor
import org.eclipse.osgi.framework.internal.core.FrameworkProperties
import org.osgi.framework.Bundle
import org.osgi.framework.BundleEvent
import org.osgi.framework.BundleListener
import org.osgi.framework.SynchronousBundleListener

/*
 * Fucking EclipseStarter designers. What a reason to hide the FINAL immutable variable like REFERENCE_PROTOCOL or REFERENCE_SCHEME? Hide shit?
 * Very wise, stupid assholes :-/ Fucking secrets... Junkies. And then they discuss about bugs, poor tests coverage and complex architecture.
 * Look for Apache Felix source code at contrast.
 * But I must admit that there are really wise people in Eclipse infrastructure, especially in SWT team.
 *   Ezh.
 */

class Framework(val frameworkAdaptor: FrameworkAdaptor)
  extends org.eclipse.osgi.framework.internal.core.Framework(frameworkAdaptor) {
  /**
   * Register a framework shutdown handler. <p>
   * A handler implements the {@link Runnable} interface.  When the framework is shutdown
   * the {@link Runnable#run()} method is called for each registered handler.  Handlers should
   * make no assumptions on the thread it is being called from.  If a handler object is
   * registered multiple times it will be called once for each registration.
   * <p>
   * At the time a handler is called the framework is shutdown.  Handlers must not depend on
   * a running framework to execute or attempt to load additional classes from bundles
   * installed in the framework.
   * @param handler the framework shutdown handler
   */
  def registerShutdownHandlers(shutdownHandler: Runnable): Unit =
    registerShutdownHandlers(Seq(shutdownHandler))
  /**
   * Register a framework shutdown handlers. <p>
   * A handler implements the {@link Runnable} interface.  When the framework is shutdown
   * the {@link Runnable#run()} method is called for each registered handler.  Handlers should
   * make no assumptions on the thread it is being called from.  If a handler object is
   * registered multiple times it will be called once for each registration.
   * <p>
   * At the time a handler is called the framework is shutdown.  Handlers must not depend on
   * a running framework to execute or attempt to load additional classes from bundles
   * installed in the framework.
   * @param handler the framework shutdown handler
   */
  def registerShutdownHandlers(shutdownHandlers: Seq[Runnable]): Seq[BundleListener] =
    for (handler <- shutdownHandlers) yield {
      val listener = new SynchronousBundleListener() {
        val processed = new AtomicBoolean(false)
        def bundleChanged(event: BundleEvent) {
          if (event.getBundle() == systemBundle && event.getType() == BundleEvent.STOPPED)
            if (processed.compareAndSet(false, true))
              new Thread(handler).start()
        }
      }
      getSystemBundleContext().addBundleListener(listener)
      listener
    }
  /**
   * Used by ServiceReferenceImpl for isAssignableTo
   * @param registrant Bundle registering service
   * @param client Bundle desiring to use service
   * @param className class name to use
   * @param serviceClass class of original service object
   * @return true if assignable given package wiring
   */
  override def isServiceAssignableTo(registrant: Bundle, client: Bundle, className: String, serviceClass: Class[_]): Boolean = {
    if (super.isServiceAssignableTo(registrant, client, className, serviceClass))
      return true
    // If service is registered in system bundle
    // And system bundle may load this class with FWK loader
    // Then client may load it too. :-)
    if (registrant.getBundleId() == 0)
      try { registrant.loadClass(className); true } catch { case e: ClassNotFoundException => false }
    else
      false
  }
}

object Framework extends Loggable {
  val FILE_SCHEME = "file:"
  val INITIAL_LOCATION = "initial@"
  val PROP_ALLOW_APPRELAUNCH = "eclipse.allowAppRelaunch"
  val PROP_APPLICATION_LAUNCHDEFAULT = "eclipse.application.launchDefault"
  val PROP_FORCED_RESTART = "osgi.forcedRestart"
  val PROP_LAUNCHER = "eclipse.launcher"
  val PROP_NL_EXTENSIONS = "osgi.nl.extensions"
  val REFERENCE_PROTOCOL = "reference"
  val REFERENCE_SCHEME = "reference:"

  def isForcedRestart(): Boolean =
    Option(FrameworkProperties.getProperty(PROP_FORCED_RESTART)).map(value => try {
      value.toBoolean
    } catch {
      case e: Throwable =>
        log.error("Invalid 'osgi.forcedRestart' value: " + value)
        false
    }) getOrElse true
}
