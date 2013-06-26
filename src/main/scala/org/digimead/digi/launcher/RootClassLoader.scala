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

import java.net.URL
import java.net.URLClassLoader
import java.net.URLStreamHandlerFactory

import org.digimead.digi.lib.log.api.Loggable

/**
 * Standard parent-first class loader with additional search over delegationLoader
 */
class RootClassLoader(
  /** The URLs from which to load classes and resources. */
  val urls: Array[URL],
  /** The parent class loader for delegation. */
  val parent: ClassLoader,
  /** The URLStreamHandlerFactory to use when creating URLs. */
  val factory: URLStreamHandlerFactory,
  /** The boot delegation class loader for custom delegation expression */
  val delegationLoader: ClassLoader)
  extends URLClassLoader(urls, parent, factory) with RootClassLoader.Interface with Loggable {
  /** List of regular expressions with propagated entities from this class loader to OSGi. */
  @volatile protected var bootDelegations = Set[String]()

  /** Add class with associated class loader to boot class list. */
  def addBootDelegationExpression(expression: String): Unit = {
    if (delegationLoader == null)
      throw new IllegalStateException("Unable to add boot delegation expression without boot delegation class loader")
    bootDelegations = bootDelegations + expression
  }
  /** List all boot delegation expressions. */
  def listBootDelegation(): Set[String] = {
    if (delegationLoader == null)
      throw new IllegalStateException("Unable to list boot delegation expression without boot delegation class loader")
    bootDelegations
  }
  /** Remove expression from boot delegation expressions. */
  def removeBootDelegationExpression(expression: String): Option[String] = {
    if (delegationLoader == null)
      throw new IllegalStateException("Unable to remove boot delegation expression without boot delegation class loader")
    if (bootDelegations(expression)) {
      bootDelegations = bootDelegations - expression
      Some(expression)
    } else {
      None
    }
  }

  // It is never returns null, as the specification defines
  /** Loads the class with the specified binary name. */
  override protected def loadClass(name: String, resolve: Boolean): Class[_] = {
    // Try to load from parent loader.
    try {
      if (parent != null)
        return parent.loadClass(name)
    } catch {
      case _: ClassNotFoundException =>
    }
    // Try to load from this loader.
    try {
      return super.loadClass(name, resolve)
    } catch {
      case _: ClassNotFoundException =>
    }
    // Try to load from delegation loader.
    if (delegationLoader != null) {
      val iterator = bootDelegations.iterator
      while (iterator.hasNext)
        if (name.matches(iterator.next)) try {
          return delegationLoader.loadClass(name)
        } catch {
          case _: ClassNotFoundException =>
        }
    }

    throw new ClassNotFoundException(name)
  }
}

/**
 * Application root class loader
 */
object RootClassLoader {
  /**
   * Root class loader interface
   */
  trait Interface extends URLClassLoader {
    /** The URLs from which to load classes and resources. */
    val urls: Array[URL]
    /** The parent class loader for delegation. */
    val parent: ClassLoader
    /** The URLStreamHandlerFactory to use when creating URLs. */
    val factory: URLStreamHandlerFactory
    /** The boot delegation class loader for custom delegation expression */
    val delegationLoader: ClassLoader

    /** Add class with associated class loader to boot class list. */
    def addBootDelegationExpression(expression: String): Unit
    /** List all boot delegation expressions. */
    def listBootDelegation(): Set[String]
    /** Remove expression from boot delegation expressions. */
    def removeBootDelegationExpression(expression: String): Option[String]
  }
}
