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

package org.digimead.digi.launcher.report.api

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

import org.osgi.framework.BundleActivator

trait Report {
  /** General information about application. */
  val info: String
  /** Number of saved log files. */
  val keepLogFiles: Int
  /** Quantity of saved trace files. */
  val keepTrcFiles: Int
  /** Log file extension. */
  val logFileExtension: String
  /** Log file extension prefix. */
  val logFileExtensionPrefix: String
  /** Path to report files. */
  val path: File
  /** Process ID. */
  val pid: String
  /** Trace file extension. */
  val traceFileExtension: String
  /** User ID. */
  val uid: String

  /** Clean report files. */
  def clean(): Unit
  /** Clean report files after review. */
  def cleanAfterReview(dir: File = path): Unit
  /** Compress report logs. */
  def compress(): Unit
  /** Returns file prefix. */
  def filePrefix(): String
  /** Prepare for upload. */
  def prepareForUpload(): Seq[File]
  /** Register listener. */
  def register(listener: Runnable)
  /** Register listener. */
  def unregister(listener: Runnable)
}
