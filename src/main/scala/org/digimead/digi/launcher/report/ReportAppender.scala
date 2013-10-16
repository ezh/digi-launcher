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

package org.digimead.digi.launcher.report

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter

import org.digimead.digi.launcher.report.Report.report2implementation
import org.digimead.digi.lib.aop.log
import org.digimead.digi.lib.api.DependencyInjection
import org.digimead.digi.lib.log.Logging
import org.digimead.digi.lib.log.Logging.Logging2implementation
import org.digimead.digi.lib.log.api.Message
import org.digimead.digi.lib.log.api.Appender

// Write your own appender or submit at issue report if you want to change this to something expendable
/**
 * Report appender
 */
object ReportAppender extends Appender {
  @volatile private var file: Option[File] = None
  @volatile private var output: Option[PrintWriter] = None
  /** Counter that limits log file size */
  @volatile private var counter1 = 0
  /** Counter that prevents clean() operation */
  @volatile private var counter2 = 0
  /** Appender filter */
  @volatile private var filter: Message ⇒ Boolean = (record) ⇒ true

  protected var f = (records: Array[Message]) ⇒ synchronized {
    // rotate
    for {
      output ← output
      file ← file
    } {
      counter1 += records.size
      if (counter1 > DI.checkEveryNLines) {
        counter1 = 0
        if (file.length > DI.fileLimit)
          openLogFile()
      }
    }
    // write
    output.foreach {
      output ⇒
        records.foreach { r ⇒
          if (filter(r)) {
            output.write(r.toString)
            r.throwable.foreach { t ⇒
              output.println()
              try {
                t.printStackTrace(output)
              } catch {
                case e: Throwable ⇒
                  output.append("\nstack trace \"" + t.getMessage + "\" unaviable")
              }
            }
            output.println()
          }
        }
        output.flush
    }
  }

  /** Change report appender singleton filter */
  def apply(filter: Message ⇒ Boolean): ReportAppender.type = {
    this.filter = filter
    this
  }
  @log
  override def init() = synchronized {
    openLogFile()
    output.foreach(_.flush)
  }
  override def deinit() = synchronized {
    try {
      // close output if any
      output.foreach(_.close)
      output = None
      file = None
    } catch {
      case e: Throwable ⇒
        Logging.commonLogger.error(e.getMessage, e)
    }
  }
  override def flush() = synchronized {
    try { output.foreach(_.flush) } catch { case e: Throwable ⇒ }
  }

  private def getLogFileName() =
    Report.filePrefix + "." + Report.logFileExtensionPrefix + Report.logFileExtension
  /** Close and compress the previous log file, prepare and open new one */
  private def openLogFile() = try {
    deinit
    // open new
    file = {
      val file = new File(Report.path, getLogFileName)
      if (!Report.path.exists)
        Report.path.mkdirs
      if (file.exists) {
        Logging.commonLogger.debug("Open new log file " + file)
        Some(file)
      } else if (file.createNewFile) {
        Logging.commonLogger.info("Create new log file " + file)
        Some(file)
      } else {
        Logging.commonLogger.error("Unable to create log file " + file)
        None
      }
    }
    output = file.map(f ⇒ {
      // write header
      // the PrintWriter is swallow the exceptions. It is fine.
      val writer = new PrintWriter(new BufferedWriter(new FileWriter(f, true)))
      writer.write("=== TA-Buddy desktop (if you have a question or suggestion, email ezh@ezh.msk.ru) ===\n")
      writer.write("report path: " + Report.path + "\n")
      writer.write(s"os: ${Report.info.os}\narch: ${Report.info.arch}\nplatform: ${Report.info.platform}\n" +
        Report.info.component.map(c ⇒ s"${c.name}: version: ${c.version}, build: ${Report.dateString(c.build)} (${c.rawBuild})").
        sorted.mkString("\n") + "\n")
      writer.write("=====================================================================================\n\n")
      // -rw-r--r--
      f.setReadable(true, false)
      writer
    })
    Report.compress()
    counter2 += 1
    if (counter2 > 10) {
      counter2 = 0
      Report.clean()
    }
  } catch {
    case e: Throwable ⇒
      Logging.commonLogger.error(e.getMessage, e)
  }
  /**
   * Dependency injection routines.
   */
  private object DI extends DependencyInjection.PersistentInjectable {
    /** Log file size limit. */
    lazy val fileLimit: Int = injectOptional[Int]("Report.LogFileSize") getOrElse 409600 * 3 // 1.5Mb or ~100kb compressed
    /** Check for size every N lines. */
    lazy val checkEveryNLines = injectOptional[Int]("Report.LogCheckNLines") getOrElse 1000
  }
}
