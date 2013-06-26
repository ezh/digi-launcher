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

import scala.collection.immutable

import org.digimead.digi.launcher.report.Report
import org.digimead.digi.launcher.report.ReportAppender
import org.digimead.digi.lib.DependencyInjection
import org.digimead.digi.lib.log.api.Appender
import org.digimead.digi.lib.log.appender.Console

import com.escalatesoft.subcut.inject.NewBindingModule

package object report {
  lazy val default = new NewBindingModule(module => {
    module.bind[Report.Interface] toModuleSingle { implicit module => new Report }
    module.bind[Boolean] identifiedBy "Report.TraceFileEnabled" toSingle { true }
    module.bind[Int] identifiedBy "Report.KeepLogFiles" toSingle { 4 }
    module.bind[Int] identifiedBy "Report.KeepTrcFiles" toSingle { 8 }
    module.bind[String] identifiedBy "Report.LogFileExtension" toSingle { "log" }
    module.bind[String] identifiedBy "Report.TraceFileExtension" toSingle { "trc" }
    module.bind[immutable.HashSet[Appender]] identifiedBy "Log.BufferedAppenders" toSingle { immutable.HashSet[Appender](Console, ReportAppender) }
  })
  DependencyInjection.setPersistentInjectable("org.digimead.digi.launcher.report.Report$DI$")
}
