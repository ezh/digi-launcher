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
import java.net.{ MalformedURLException, URL }
import java.util.StringTokenizer
import org.digimead.digi.lib.aop.log
import org.digimead.digi.lib.log.api.XLoggable
import org.eclipse.core.runtime.adaptor.EclipseStarter
import org.eclipse.core.runtime.internal.adaptor.LocationHelper
import org.eclipse.osgi.framework.internal.core.FrameworkProperties
import org.osgi.framework.Bundle
import scala.collection.mutable
import scala.util.control.Breaks.{ break, breakable }

/**
 * Helper routines that contain bundle location logic.
 */
class SupportBundleLocator extends XLoggable {
  def getBundleByLocation(location: String, bundles: Array[Bundle]): Option[Bundle] =
    bundles.find(bundle ⇒ location.equalsIgnoreCase(bundle.getLocation()))
  /** Get the path where the OSGi Framework implementation is located. */
  @log
  def getSysPath(): String = Option(FrameworkProperties.getProperty(EclipseStarter.PROP_SYSPATH)) match {
    case Some(path) ⇒
      path
    case None ⇒
      val path = getSysPathFromURL(FrameworkProperties.getProperty(EclipseStarter.PROP_FRAMEWORK)) orElse
        getSysPathFromCodeSource() getOrElse {
          throw new IllegalStateException("Can not find the system path."); //$NON-NLS-1$
        }
      // WTF? Hack from Eclipse team?
      val result = if (Character.isUpperCase(path.charAt(0))) {
        var chars = path.toCharArray()
        chars(0) = Character.toLowerCase(chars(0))
        new String(chars)
      } else path
      FrameworkProperties.setProperty(EclipseStarter.PROP_SYSPATH, result)
      result
  }
  /** Get the path where the OSGi Framework implementation is located. */
  @log
  def getSysPathFromURL(urlSpec: String): Option[String] = Option(urlSpec) flatMap (urlSpec ⇒
    Option(LocationHelper.buildURL(urlSpec, false)) map (url ⇒
      new File(url.getFile()).getCanonicalFile().getParentFile().getAbsolutePath()))
  /** Get the path where the OSGi Framework implementation is located. */
  @log
  def getSysPathFromCodeSource(): Option[String] = Option(classOf[EclipseStarter].getProtectionDomain()) flatMap (pd ⇒
    Option(pd.getCodeSource()) flatMap (cs ⇒
      Option(cs.getLocation()) map { url ⇒
        url.getFile() match {
          case url if url.endsWith(".jar") ⇒
            val result = url.substring(0, url.lastIndexOf('/'))
            if ("folder".equals(FrameworkProperties.getProperty(EclipseStarter.PROP_FRAMEWORK_SHAPE))) //$NON-NLS-1$
              result.substring(0, result.lastIndexOf('/'))
            else
              url
          case url ⇒
            // really shit. and no comments from originator
            val result = if (url.endsWith("/")) url.substring(0, url.length() - 1) else url
            val result1 = result.substring(0, result.lastIndexOf('/'))
            result1.substring(0, result1.lastIndexOf('/'))
        }
      }))
  /** Returns URL of bundle. */
  @log
  def searchForBundle(name: String, parent: String): URL = {
    log.debug(s"Search for bundle '${name}' at ${parent}")
    val searchCandidates = mutable.HashMap[String, Array[String]]()
    var url: URL = null
    var fileLocation: File = null
    var reference = false
    try {
      // quick check to see if the name is a valid URL
      new URL(name)
      url = new URL(new File(parent).toURI().toURL(), name)
    } catch {
      case e: MalformedURLException ⇒
        // TODO this is legacy support for non-URL names.  It should be removed eventually.
        // if name was not a URL then construct one.
        // Assume it should be a reference and that it is relative.  This support need not
        // be robust as it is temporary..
        val child = new File(name)
        fileLocation = if (child.isAbsolute()) child else new File(parent, name)
        url = new URL(Framework.REFERENCE_PROTOCOL, null, fileLocation.toURI().toURL().toExternalForm())
        reference = true
    }
    // if the name was a URL then see if it is relative.  If so, insert syspath.
    if (!reference) {
      var baseURL = url;
      // if it is a reference URL then strip off the reference: and set base to the file:...
      if (url.getProtocol().equals(Framework.REFERENCE_PROTOCOL)) {
        reference = true;
        val baseSpec = url.getFile();
        if (baseSpec.startsWith(Framework.FILE_SCHEME)) {
          val child = new File(baseSpec.substring(5))
          baseURL = if (child.isAbsolute()) child.toURI().toURL() else new File(parent, child.getPath()).toURI().toURL()
        } else
          baseURL = new URL(baseSpec)
      }

      fileLocation = new File(baseURL.getFile())
      // if the location is relative, prefix it with the parent
      if (!fileLocation.isAbsolute())
        fileLocation = new File(parent, fileLocation.toString())
    }
    // If the result is a reference then search for the real result and
    // reconstruct the answer.
    if (reference) {
      searchFor(fileLocation.getName(), new File(fileLocation.getParent()).getAbsolutePath(), searchCandidates) match {
        case Some(result) ⇒
          url = new URL(Framework.REFERENCE_PROTOCOL, null, Framework.FILE_SCHEME + result)
        case None ⇒
          return null
      }
    }
    // finally we have something worth trying
    try {
      val result = url.openConnection()
      result.connect()
      return url
    } catch {
      case e: IOException ⇒
        return null
    }
  }

  /**
   * Compares version strings.
   * @return result of comparison, as integer;
   * <code><0</code> if left < right;
   * <code>0</code> if left == right;
   * <code>>0</code> if left > right;
   */
  protected def compareVersion(left: Array[AnyRef], right: Array[AnyRef]): Int = {
    if (left == null)
      return -1
    var result = (left(0).asInstanceOf[Integer]).compareTo(right(0).asInstanceOf[Integer]) // compare major
    if (result != 0)
      return result

    result = (left(1).asInstanceOf[Integer]).compareTo(right(1).asInstanceOf[Integer]); // compare minor
    if (result != 0)
      return result

    result = (left(2).asInstanceOf[Integer]).compareTo(right(2).asInstanceOf[Integer]); // compare service
    if (result != 0)
      return result

    return (left(3).asInstanceOf[String]).compareTo(right(3).asInstanceOf[String]); // compare qualifier
  }
  /**
   * Do a quick parse of version identifier so its elements can be correctly compared.
   * If we are unable to parse the full version, remaining elements are initialized
   * with suitable defaults.
   * @return an array of size 4; first three elements are of type Integer (representing
   * major, minor and service) and the fourth element is of type String (representing
   * qualifier).  A value of null is returned if there are no valid Integers.  Note, that
   * returning anything else will cause exceptions in the caller.
   */
  protected def getVersionElements(version: String): Array[AnyRef] = {
    var result = Array[AnyRef](new Integer(-1), new Integer(-1), new Integer(-1), "")
    val t = new StringTokenizer(version, ".")
    var token = ""
    for (i ← 0 until 4 if t.hasMoreTokens()) {
      token = t.nextToken()
      if (i < 3) {
        // major, minor or service ... numeric values
        try {
          result(i) = new Integer(token)
        } catch {
          case e: Exception ⇒
            if (i == 0)
              return null // return null if no valid numbers are present
          // invalid number format - use default numbers (-1) for the rest
          //break;
        }
      } else {
        // qualifier ... string value
        result(i) = token
      }
    }
    result
  }
  /**
   * Searches for the given target directory immediately under
   * the given start location.  If one is found then this location is returned;
   * otherwise an exception is thrown.
   *
   * @param start the location to begin searching
   * @return the location where target directory was found
   */
  protected def searchFor(target: String, start: String, searchCandidates: mutable.HashMap[String, Array[String]]): Option[String] = {
    val candidates = searchCandidates.get(start) match {
      case Some(candidates) ⇒
        candidates
      case None ⇒
        var startFile = new File(start)
        // Pre-check if file exists, if not, and it contains escape characters,
        // try decoding the path
        if (!startFile.exists() && start.indexOf('%') >= 0) {
          val decodePath = FrameworkProperties.decode(start)
          val f = new File(decodePath)
          if (f.exists())
            startFile = f
        }
        val candidates = startFile.list()
        if (candidates != null) {
          searchCandidates.put(start, candidates)
          candidates
        } else
          return None
    }
    var result: String = null
    var maxVersion: Array[AnyRef] = null
    var resultIsFile = false
    breakable {
      for (candidateName ← candidates) {
        if (candidateName.startsWith(target)) breakable {
          var simpleJar = false
          val versionSep = if (candidateName.length() > target.length()) candidateName.charAt(target.length()) else 0
          if (candidateName.length() > target.length() && versionSep != '_' && versionSep != '-') {
            // make sure this is not just a jar with no (_|-)version tacked on the end
            if (candidateName.length() == 4 + target.length() && candidateName.endsWith(".jar")) //$NON-NLS-1$
              simpleJar = true
            else
              // name does not match the target properly with an (_|-) version at the end
              break
          }
          // Note: directory with version suffix is always > than directory without version suffix
          val version = if (candidateName.length() > target.length() + 1 && (versionSep == '_' || versionSep == '-'))
            candidateName.substring(target.length() + 1) else ""; //$NON-NLS-1$
          val currentVersion = getVersionElements(version)
          if (currentVersion != null && compareVersion(maxVersion, currentVersion) < 0) {
            val candidate = new File(start, candidateName);
            val candidateIsFile = candidate.isFile();
            // if simple jar; make sure it is really a file before accepting it
            if (!simpleJar || candidateIsFile) {
              result = candidate.getAbsolutePath();
              resultIsFile = candidateIsFile;
              maxVersion = currentVersion;
            }
          }
        }
      }
    }
    Option(result).map(_.replace(File.separatorChar, '/') + (if (resultIsFile) "" else "/"))
  }
}
