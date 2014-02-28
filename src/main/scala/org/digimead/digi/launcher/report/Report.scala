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

import com.escalatesoft.subcut.inject.{ BindingModule, Injectable }
import java.io.{ BufferedInputStream, BufferedOutputStream, BufferedWriter, File, FileInputStream, FileOutputStream, FileWriter, FilenameFilter, InputStream, OutputStream, PrintWriter, StringWriter }
import java.lang.management.ManagementFactory
import java.text.{ DateFormat, SimpleDateFormat }
import java.util.{ Date, Properties }
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.GZIPOutputStream
import org.digimead.digi.lib.aop.log
import org.digimead.digi.lib.api.DependencyInjection
import org.digimead.digi.lib.log.api.{ Event, Level, Loggable }
import scala.annotation.tailrec
import scala.collection.JavaConversions.enumerationAsScalaIterator
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.implicitConversions

class Report(implicit val bindingModule: BindingModule) extends api.Report with Injectable with Loggable {
  /** Flag indicating whether stack trace generation enabled. */
  val allowGenerateStackTrace = injectOptional[Boolean]("Report.TraceFileEnabled") getOrElse true
  /** Copy buffer size. */
  val bufferSize: Int = injectOptional[Int]("Report.BufferSize") getOrElse 8196
  /** General information about application. */
  val info = getInfo()
  /** Number of saved log files. */
  val keepLogFiles: Int = injectOptional[Int]("Report.KeepLogFiles") getOrElse 4
  /** Quantity of saved trace files. */
  val keepTrcFiles: Int = injectOptional[Int]("Report.KeepTrcFiles") getOrElse 8
  /** Log file extension. */
  val logFileExtension: String = injectOptional[String]("Report.LogFileExtension") getOrElse "log" // t(z)log, z - compressed
  /** Log file extension prefix. */
  val logFileExtensionPrefix: String = injectOptional[String]("Report.LogFilePrefix") getOrElse "d"
  /** Path to report files. */
  val path: File = inject[File]("Report.LogPath")
  /** Process ID */
  val pid = ManagementFactory.getRuntimeMXBean().getName()
  /** Flag indicating if the submit process is on going */
  val submitInProgressLock = new AtomicBoolean(false)
  /** Trace file extension. */
  val traceFileExtension: String = injectOptional[String]("Report.TraceFileExtension") getOrElse "trc"
  /** User ID */
  val uid = System.getProperty("user.name")
  /** Thread with cleaner process. */
  @volatile protected var cleanThread: Option[Thread] = None
  /** Exception listeners. */
  @volatile protected var listeners = Seq[Runnable]()

  /** Clean report files */
  @log
  def clean(): Unit = if (!submitInProgressLock.get()) synchronized {
    if (cleanThread.nonEmpty) {
      log.warn("Cleaning in progress, skip.")
      return
    }
    cleanThread = Some(new Thread("report cleaner for " + Report.getClass.getName) {
      log.debug(s"New report cleaner thread ${this.getId.toString} is alive.")
      this.setDaemon(true)
      override def run() = try {
        toClean(path, Seq())._2.foreach(_.delete)
      } catch {
        case e: Throwable ⇒
          log.error(e.getMessage, e)
      } finally {
        cleanThread = None
      }
    })
    cleanThread.get.start
  }
  /** Clean report files except active */
  @log
  def cleanAfterReview(dir: File = path): Unit = if (!submitInProgressLock.get()) synchronized {
    log.debug("Clean reports after review.")
    val reports = Option(dir.list()).getOrElse(Array[String]())
    if (reports.isEmpty)
      return
    try {
      reports.foreach(name ⇒ {
        val report = new File(dir, name)
        val active = try {
          val name = report.getName
          val pid = name.split("""-""")(2).drop(1).reverse.dropWhile(_ != '.').drop(1).reverse
          this.pid == pid
        } catch {
          case e: Throwable ⇒
            log.error(s"Unable to find pid for ${report.getName()}: ${e.getMessage()}.", e)
            false
        }
        if (!active || !report.getName.endsWith(logFileExtension)) {
          log.info(s"Delete ${report.getName}.")
          report.delete
        }
      })
    } catch {
      case e: Throwable ⇒
        log.error(e.getMessage, e)
    }
  }
  /** Compress report logs */
  @log
  def compress(): Unit = synchronized {
    val reports: Array[File] = Option(path.listFiles(new FilenameFilter {
      def accept(dir: File, name: String) =
        name.toLowerCase.endsWith(logFileExtension) && !name.toLowerCase.endsWith("z" + logFileExtension)
    }).sortBy(_.getName).reverse).getOrElse(Array[File]())
    if (reports.isEmpty)
      return
    try {
      reports.foreach(report ⇒ {
        val reportName = report.getName
        val compressed = new File(path, reportName.substring(0, reportName.length - logFileExtension.length) + "z" + logFileExtension)
        val active = try {
          val pid = reportName.split("""-""")(2).drop(1).reverse.dropWhile(_ != '.').drop(1).reverse
          if (this.pid == pid) {
            // "-Pnnnnn.dlog"
            val suffix = reportName.substring(reportName.length - logFileExtensionPrefix.length - logFileExtension.length - 7)
            reports.find(_.getName.endsWith(suffix)) match {
              case Some(activeName) ⇒
                activeName.getName == reportName
              case None ⇒
                false
            }
          } else
            false
        } catch {
          case e: Throwable ⇒
            log.error(s"Unable to find pid for ${report.getName()}: ${e.getMessage()}.", e)
            false
        }
        if (!active && report.length > 0) {
          // compress log files
          log.info(s"Save compressed log file ${compressed.getName}.")
          val is = new BufferedInputStream(new FileInputStream(report))
          var zos: OutputStream = null
          try {
            zos = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(compressed)))
            copy(is, zos, true)
          } finally {
            if (zos != null)
              zos.close()
          }
          if (compressed.length > 0) {
            log.info(s"Delete uncompressed log file ${reportName}.")
            report.delete
          } else {
            log.warn(s"Unable to compress ${reportName}, delete broken archive.")
            compressed.delete
          }
        }
      })
    } catch {
      case e: Throwable ⇒
        log.error(e.getMessage, e)
    }
  }
  /** Returns file prefix */
  def filePrefix(): String = {
    val uid = "U" + this.uid
    val date = dateFile(new Date())
    val pid = "P" + this.pid
    Seq(uid, date, pid).map(_.replaceAll("""[/?*:\.;{}\\-]+""", "_")).mkString("-")
  }
  /** Generate the stack trace report */
  @log
  def generateStackTrace(pid: Int, tid: Long, message: String, e: Throwable, when: Date) {
    if (!path.exists())
      if (!path.mkdirs()) {
        log.fatal(s"Unable to create log path ${path}.")
        return
      }
    // take actual view
    // TODO user interaction
    // Main.execNGet { takeScreenshot() }
    // Here you should have a more robust, permanent record of problems
    val reportName = filePrefix + "." + logFileExtensionPrefix + traceFileExtension
    val result = new StringWriter()
    val printWriter = new PrintWriter(result)
    e.printStackTrace(printWriter)
    if (e.getCause() != null) {
      printWriter.println("\nCause:\n")
      e.getCause().printStackTrace(printWriter)
    }
    try {
      val file = new File(path, reportName)
      log.debug(s"Writing unhandled exception to: ${file}.")
      // Write the stacktrace to disk
      val bos = new BufferedWriter(new FileWriter(file))
      bos.write("date:%s; process: %s; thread: %s.\n".format(Report.dateString(when), pid.toString, tid.toString))
      if (message != null)
        bos.write(message + "\n\n")
      else
        bos.write("-\n\n")
      bos.write(result.toString())
      bos.flush()
      // Close up everything
      bos.close()
      // -rw-r--r--
      file.setReadable(true, false)
    } catch {
      // Nothing much we can do about this - the game is over
      case e: Throwable ⇒
        System.err.println("Fatal error " + e)
        e.printStackTrace()
    }
  }
  /** Prepare for upload. */
  def prepareForUpload(): Seq[File] = Seq()
  /** Register listener of outgoing log events that contains throwable. */
  def register(listener: Runnable) = synchronized {
    if (!listeners.contains(listener)) {
      log.debug(s"Register listener ${listener}.")
      listeners = listeners :+ listener
    } else
      throw new IllegalArgumentException(s"Listener ${listener} is already registered.")
  }
  /** Rotate log files. */
  def rotate() = synchronized { org.digimead.digi.lib.log.api.Logging.rotate }
  /** Start reporter log intercepter and application service. */
  @log
  def start() {
    log.info("Start reporter.")
    Event.subscribe(LogSubscriber)
    try {
      if (!path.exists())
        if (!path.mkdirs()) {
          log.fatal(s"Unable to create report log path ${path}.")
          return
        }
      clean()
      compress()
    } catch {
      case e: Throwable ⇒ log.error(e.getMessage, e)
    }
    Report.active = true
  }
  /** Stop reporter log intercepter and application service. */
  @log
  def stop() {
    log.info("Stop reporter.")
    Report.active = false
    Event.removeSubscription(LogSubscriber)
  }
  /** Unregister listener of outgoing log events. */
  @log
  def unregister(listener: Runnable) = synchronized {
    if (listeners.contains(listener)) {
      log.debug(s"Unregister listener ${listener}.")
      listeners = listeners.filterNot(_ == listener)
    }
  }

  /** Returns general information about application */
  protected def getInfo(): api.Report.Info = {
    // Get components information if any.
    val versionProperties = getClass.getClassLoader.getResources("version.properties")
    val components = for (resURL ← versionProperties) yield try {
      val properties = new Properties
      properties.load(resURL.openStream())
      Option(properties.getProperty("name")).map { name ⇒
        val version = Option(properties.getProperty("version")).getOrElse("0")
        val bundleSymbolicName = Option(properties.getProperty("bundleSymbolicName")).getOrElse("")
        Option(properties.getProperty("build")) match {
          case Some(rawBuild) ⇒
            val date = try { new Date(rawBuild.toLong * 1000) } catch { case e: Throwable ⇒ new Date(0) }
            api.Report.Component(name, version, date, rawBuild, bundleSymbolicName)
          case None ⇒
            api.Report.Component(name, version, new Date(0), "0", bundleSymbolicName)
        }
      }
    } catch {
      case e: Throwable ⇒ //
        log.error(s"Unable to load version.properties for ${resURL}.", e)
        None
    }
    // Get SWT information if any.
    // SWT native library is poisoned JVM. Reload of SWT bundle is doomed from the beginning by design
    //   so we are nothing to be afraid of: like class loader lock.
    val platform = try {
      Option(Class.forName("org.eclipse.swt.SWT").getMethod("getPlatform").invoke(null))
    } catch {
      case e: Throwable ⇒
        log.error(e.getMessage(), e)
        None
    }
    api.Report.Info(components.flatten.toSeq, Option(System.getProperty("os.name")).getOrElse("UNKNOWN"),
      Option(System.getProperty("os.arch")).getOrElse("UNKNOWN"), platform.map(_.toString()).getOrElse("UNKNOWN"))
  }
  /**
   * Build sequence of files to delete
   * @return keep suffixes, files to delete
   */
  private def toClean(dir: File, keep: Seq[String]): (Seq[String], Seq[File]) = try {
    var result: Seq[File] = Seq()
    val files = Option(dir.listFiles()).getOrElse(Array[File]()).map(f ⇒ f.getName.toLowerCase -> f)
    val traceFiles = files.filter(_._1.endsWith(traceFileExtension)).sortBy(_._1).reverse
    traceFiles.drop(keepTrcFiles).foreach {
      case (name, file) ⇒
        log.info(s"Delete outdated stacktrace file ${name}.")
        result = result :+ file
    }
    files.filter(_._1.endsWith(".description")).foreach {
      case (name, file) ⇒
        log.info(s"Delete outdated description file ${name}.")
        result = result :+ file
    }
    files.filter(_._1.endsWith(".png")).foreach {
      case (name, file) ⇒
        log.info(s"Delete outdated png file ${name}.")
        result = result :+ file
    }
    // sequence of name suffixes: Tuple2(uncompressed suffix, compressed suffix)
    val keepForTraceReport = traceFiles.take(keepTrcFiles).map(t ⇒ {
      val name = t._1
      val traceSuffix = name.substring(name.length - name.reverse.takeWhile(_ != '-').length - 1)
      Array(traceSuffix.takeWhile(_ != '.') + "." + logFileExtensionPrefix + logFileExtension,
        traceSuffix.takeWhile(_ != '.') + "." + logFileExtensionPrefix + "z" + logFileExtension)
    }).flatten.distinct
    val logFiles = files.filter(_._1.endsWith(logFileExtension)).sortBy(_._1).reverse
    // sequence of name suffixes: Tuple2(uncompressed suffix, compressed suffix)
    // keep all log files with PID == last run
    val keepLog = logFiles.take(keepLogFiles).map(_._1 match {
      case compressed if compressed.endsWith("z" + logFileExtension) ⇒
        // for example "-P0000.dzlog"
        Array(compressed.substring(compressed.length - compressed.reverse.takeWhile(_ != '-').length - 1))
      case plain ⇒
        // for example "-P0000.dlog"
        val logSuffix = plain.substring(plain.length - plain.reverse.takeWhile(_ != '-').length - 1)
        Array(logSuffix.takeWhile(_ != '.') + "." + logFileExtensionPrefix + logFileExtension,
          logSuffix.takeWhile(_ != '.') + "." + logFileExtensionPrefix + "z" + logFileExtension)
    }).flatten.distinct
    log.debug(s"Keep log files with suffixes: ${(keepLog ++ keepForTraceReport).mkString(", ")}.")
    val keepSuffixes = (keepLog ++ keepForTraceReport ++ keep).distinct
    logFiles.drop(keepLogFiles).foreach {
      case (name, file) ⇒
        if (!keepSuffixes.exists(name.endsWith)) {
          log.info(s"Delete outdated log file ${name}.")
          result = result :+ file
        }
    }
    (keepSuffixes, result)
  }
  /** Returns file name based on the specific date. */
  protected def dateFile(date: Date) = Report.dateString(date).replaceAll("""[:\.]""", "_").replaceAll("""\+""", "x")
  /** Copy streams. */
  protected def copy(in: InputStream, out: OutputStream, close: Boolean) = try {
    val buffer = new Array[Byte](bufferSize)
    @tailrec
    def read() {
      val byteCount = in.read(buffer)
      if (byteCount >= 0) {
        out.write(buffer, 0, byteCount)
        read()
      }
    }
    read()
  } finally { if (close) in.close }

  object LogSubscriber extends Event.Sub {
    val lock = new ReentrantLock
    def notify(pub: Event.Pub, event: Event) = if (!lock.isLocked()) {
      event match {
        case event: Event.Outgoing ⇒
          if (event.record.throwable.nonEmpty && event.record.level == Level.Error) {
            Future {
              if (lock.tryLock()) try {
                if (allowGenerateStackTrace)
                  generateStackTrace(event.record.pid, event.record.tid, event.record.tag + ": " + event.record.message, event.record.throwable.get, event.record.date)
                listeners.foreach(_.run())
              } finally {
                lock.unlock()
              }
            } onFailure {
              case e: Exception ⇒ log.error(e.getMessage(), e)
              case e ⇒ log.error(e.toString())
            }
          }
        case _ ⇒
      }
    }
  }
}

object Report extends Loggable {
  implicit def report2implementation(r: Report.type): api.Report = r.inner
  @volatile private var active: Boolean = false

  /** Returns string representation of the specific date. */
  def dateString(date: Date) = DI.df.format(date)
  /** Report implementation. */
  def inner() = DI.implementation

  /**
   * Dependency injection routines
   */
  private object DI extends DependencyInjection.PersistentInjectable {
    /** Date representation format. */
    val df = injectOptional[DateFormat]("Report.DateFormat") getOrElse new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ")
    /** Report implementation */
    val implementation = injectOptional[api.Report] getOrElse new Report
  }
}
